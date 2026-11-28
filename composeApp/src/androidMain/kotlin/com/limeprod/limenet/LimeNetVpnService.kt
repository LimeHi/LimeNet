package com.limeprod.limenet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.util.concurrent.TimeUnit

/**
 * VPN-сервис: поднимает системный TUN, отдаёт его дескриптор ядру Xray через
 * переменную окружения `XRAY_TUN_FD` через JNI-launcher и держит ядро живым в foreground.
 *
 * На Android применяется только режим TUN.
 */
class LimeNetVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    @Volatile private var processPid: Int? = null

    /** Поколение запуска: сторож старого ядра молчит, если ядро уже перезапустили. */
    private var generation = 0
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    if (screenPauseEnabled() && state(this@LimeNetVpnService) == STATE_RUNNING) {
                        pauseForScreen()
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    if (state(this@LimeNetVpnService) == STATE_PAUSED) {
                        if (!screenPauseEnabled() || isScreenInteractive()) resumeForScreen()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        AndroidEnv.attach(application)
        ensureChannel()
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            // ACTION_SCREEN_ON/OFF are protected system broadcasts; EXPORTED is
            // needed on some Android releases for broadcasts sent by system_server.
            registerReceiver(screenReceiver, screenFilter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenReceiver, screenFilter)
        }
        AndroidEnv.vpnLog("service: onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AndroidEnv.vpnLog("service: onStartCommand action=${intent?.action ?: "-"} startId=$startId")
        if (intent?.action == ACTION_STOP) {
            stopEverything("стоп по кнопке")
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_PAUSE_FOR_SCREEN) {
            goForeground(
                when (state(this)) {
                    STATE_RUNNING -> connectedNotificationText()
                    STATE_PAUSED -> getString(R.string.vpn_screen_paused)
                    else -> getString(R.string.vpn_connecting)
                },
            )
            if (state(this) == STATE_RUNNING && screenPauseEnabled()) {
                pauseForScreen()
            } else if (state(this) == STATE_STOPPED) {
                tearDownForeground()
                stopSelfResult(startId)
            }
            return START_STICKY
        }
        if (intent?.action == ACTION_RESUME_FOR_SCREEN) {
            goForeground(
                if (state(this) == STATE_PAUSED) getString(R.string.vpn_screen_paused)
                else if (state(this) == STATE_RUNNING) connectedNotificationText()
                else getString(R.string.vpn_connecting),
            )
            if (state(this) == STATE_PAUSED && (!screenPauseEnabled() || isScreenInteractive())) {
                resumeForScreen()
            } else if (state(this) == STATE_PAUSED) {
                goForeground(getString(R.string.vpn_screen_paused))
            } else if (state(this) == STATE_STOPPED) {
                tearDownForeground()
                stopSelfResult(startId)
            }
            return START_STICKY
        }

        if (intent == null && state(this) == STATE_PAUSED) {
            if (!screenPauseEnabled() || isScreenInteractive()) {
                resumeForScreen()
            } else {
                if (!VpnBridge.screenPaused) {
                    VpnBridge.running = false
                    VpnBridge.starting = false
                    VpnBridge.screenPaused = true
                    goForeground(getString(R.string.vpn_screen_paused))
                }
            }
            return START_STICKY
        }

        val vpn = intent?.getBooleanExtra(EXTRA_VPN, true) ?: true
        getSharedPreferences("limenet", MODE_PRIVATE).edit().putBoolean(KEY_SCREEN_PAUSED, false).apply()
        VpnBridge.screenPaused = false
        VpnBridge.serverName = intent?.getStringExtra(EXTRA_SERVER_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: VpnBridge.serverName
            ?: getSharedPreferences("limenet", MODE_PRIVATE).getString("last_server_name", null)
        VpnBridge.starting = true
        setState(this, STATE_STARTING)
        LimeNetQuickTileService.requestRefresh(this)
        VpnBridge.message = null
        goForeground(getString(R.string.vpn_connecting))
        startCore(vpn)
        return START_STICKY
    }

    override fun onRevoke() {
        AndroidEnv.log("vpn: разрешение отозвано системой")
        AndroidEnv.vpnLog("service: onRevoke — VPN отозван системой")
        stopEverything("VPN отозван системой")
        super.onRevoke()
    }

    override fun onDestroy() {
        AndroidEnv.vpnLog("service: onDestroy")
        runCatching { unregisterReceiver(screenReceiver) }
        val preserveScreenPause = state(this) == STATE_PAUSED &&
            getSharedPreferences("limenet", MODE_PRIVATE).getBoolean(KEY_SCREEN_PAUSED, false)
        if (preserveScreenPause) {
            newGeneration()
            stopCore()
            closeTun()
            VpnBridge.reset()
            // Keep the sticky paused state so Android can recreate the service;
            // its null-intent start will resume when the display is interactive.
            LimeNetQuickTileService.requestRefresh(this)
            AndroidEnv.vpnLog("service: уничтожен системой во время паузы; сохранили намерение возобновить VPN")
        } else {
            stopEverything("сервис уничтожен")
        }
        super.onDestroy()
    }

    private fun screenPauseEnabled(): Boolean = getSharedPreferences("limenet", MODE_PRIVATE)
        .getBoolean(KEY_PAUSE_VPN_WITH_SCREEN, false)

    private fun isScreenInteractive(): Boolean = runCatching {
        val power = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        @Suppress("DEPRECATION")
        power.isInteractive
    }.getOrDefault(true)

    /** Останавливает ядро/TUN, но оставляет сервис и пользовательское намерение подключиться активными. */
    private fun pauseForScreen() {
        if (!screenPauseEnabled() || state(this) != STATE_RUNNING) return
        val vpnInterface = tun ?: return
        // Persist intent first: if stopping the native child process takes time,
        // packets must not continue escaping through the physical network.
        getSharedPreferences("limenet", MODE_PRIVATE).edit().putBoolean(KEY_SCREEN_PAUSED, true).apply()
        setState(this, STATE_PAUSED)
        LimeNetQuickTileService.requestRefresh(this)
        runCatching { vpnInterface.close() }
        tun = null
        newGeneration()
        VpnBridge.running = false
        VpnBridge.starting = false
        VpnBridge.screenPaused = true
        Thread({ stopCore() }, "limenet-screen-pause").apply {
            isDaemon = true
            start()
        }
        goForeground(getString(R.string.vpn_screen_paused))
        AndroidEnv.vpnLog("screen: VPN приостановлен до включения экрана")
    }

    private fun resumeForScreen() {
        val prefs = getSharedPreferences("limenet", MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_SCREEN_PAUSED, false)) return
        if (!prefs.getBoolean("last_config_is_tun", false) || !AndroidEnv.configFile.isFile) {
            stopEverything("конфигурация VPN отсутствует при возобновлении")
            return
        }
        prefs.edit().putBoolean(KEY_SCREEN_PAUSED, false).apply()
        VpnBridge.screenPaused = false
        VpnBridge.starting = true
        setState(this, STATE_STARTING)
        LimeNetQuickTileService.requestRefresh(this)
        goForeground(getString(R.string.vpn_connecting))
        startCore(vpn = true)
        AndroidEnv.vpnLog("screen: возобновляем VPN после включения экрана")
    }

    // ------------------------------------------------------------------ ядро

    private fun startCore(vpn: Boolean) {
        val gen = newGeneration()
        Thread {
            stopCore()

            val config = AndroidEnv.configFile
            if (!config.exists()) {
                fail("конфиг не найден")
                return@Thread
            }
            val bin = AndroidEnv.xrayBin
            if (!bin.exists()) {
                fail("ядро не найдено: ${bin.absolutePath}")
                return@Thread
            }
            AndroidEnv.ensureAssets()
            AndroidEnv.vpnLog("core: старт, vpn=$vpn, bin=${bin.absolutePath}")

            var fd: Int? = null
            if (vpn) {
                val prefs = getSharedPreferences("limenet", MODE_PRIVATE)
                val builder = Builder()
                    .setSession("LimeNet")
                    .addAddress("10.10.10.2", 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer(prefs.getString("last_tun_dns", "8.8.8.8") ?: "8.8.8.8")
                // Авто использует стандартные 1500 и должно совпадать с MTU Xray.
                val mtu = prefs.getInt("last_tun_mtu", 0).takeIf { it > 0 } ?: 1500
                builder.setMtu(mtu.coerceIn(1280, 1500))
                // Xray запущен дочерним процессом с тем же UID. Исключение
                // приложения защищает все его сокеты от петли через TUN;
                // VpnService.protect(fd) здесь не подходит — сокеты создаёт
                // отдельный нативный процесс, их FD нам недоступны.
                try {
                    builder.addDisallowedApplication(packageName)
                    AndroidEnv.vpnLog("tun: приложение Xray исключено из VPN ($packageName)")
                } catch (error: Exception) {
                    fail("не удалось исключить LimeNet из VPN: ${error.message}")
                    return@Thread
                }
                // Прямые приложения исключаем на уровне Android VPN API. Xray
                // получает из TUN только IP-пакеты, не UID приложения.
                val directApps = prefs.getStringSet(KEY_DIRECT_APPS, emptySet()).orEmpty()
                directApps.filter { it != packageName }.forEach { appPackage ->
                    runCatching { builder.addDisallowedApplication(appPackage) }
                        .onFailure { AndroidEnv.vpnLog("tun: не удалось исключить приложение $appPackage: $it") }
                }
                // IPv6 тоже внутрь туннеля, иначе утечёт мимо VPN
                runCatching {
                    builder.addAddress("fd00::2", 128)
                    builder.addRoute("::", 0)
                }

                val pfd = try {
                    builder.establish()
                } catch (error: Exception) {
                    fail("не удалось поднять TUN: ${error.message}")
                    return@Thread
                }
                if (pfd == null) {
                    fail("VPN-разрешение не выдано")
                    return@Thread
                }
                closeTun()
                tun = pfd
                fd = pfd.fd
                AndroidEnv.vpnLog("tun: создан, fd=$fd")
            }

            runCatching { AndroidEnv.logFile.writeText("") }
            val pid = NativeXray.launch(
                fd ?: -1,
                bin.absolutePath,
                config.absolutePath,
                AndroidEnv.assetsDir.absolutePath,
                AndroidEnv.logFile.absolutePath,
            )
            if (pid < 0) {
                fail("не удалось запустить ядро: ${if (pid == Int.MIN_VALUE) "ошибка JNI" else "errno=${-pid}"}")
                return@Thread
            }
            processPid = pid
            AndroidEnv.vpnLog("core: процесс запущен, pid=$pid")

            val earlyExit = NativeXray.waitFor(pid, 1_200)
            if (generation != gen) {
                AndroidEnv.vpnLog("core: поколение сменилось, гасим промежуточный процесс")
                NativeXray.terminate(pid, false)
                NativeXray.waitFor(pid, -1)
                if (processPid == pid) processPid = null
                return@Thread
            }
            if (earlyExit != Int.MIN_VALUE) {
                processPid = null
                AndroidEnv.vpnLog("core: завершилось сразу после старта, статус=$earlyExit")
                fail(AndroidEnv.tailLog(3).ifBlank { "ядро завершилось сразу после старта (код $earlyExit)" })
                return@Thread
            }

            VpnBridge.starting = false
            VpnBridge.running = true
            setState(this@LimeNetVpnService, STATE_RUNNING)
            LimeNetQuickTileService.requestRefresh(this@LimeNetVpnService)
            VpnBridge.startedAt = System.currentTimeMillis()
            VpnBridge.message = null
            AndroidEnv.vpnLog("core: работает")
            goForeground(connectedNotificationText())
            if (screenPauseEnabled() && !isScreenInteractive()) {
                runCatching {
                    startService(Intent(this@LimeNetVpnService, LimeNetVpnService::class.java).setAction(ACTION_PAUSE_FOR_SCREEN))
                }.onFailure { AndroidEnv.vpnLog("screen: не удалось поставить VPN на паузу после старта: $it") }
            }

            // сторож: ядро остановилось само
            val code = NativeXray.waitFor(pid, -1)
            if (generation != gen) {
                AndroidEnv.vpnLog("core: поколение сменилось, сторож молчит")
                return@Thread
            }
            val tail = AndroidEnv.tailLog(3)
            AndroidEnv.vpnLog("core: процесс завершился, код=$code tail=$tail")
            processPid = null
            closeTun()
            VpnBridge.running = false
            VpnBridge.starting = false
            setState(this@LimeNetVpnService, STATE_STOPPED)
            LimeNetQuickTileService.requestRefresh(this@LimeNetVpnService)
            VpnBridge.reportCoreFailure(tail.ifBlank { "ядро остановилось (код $code)" })
            tearDownForeground()
            stopSelf()
        }.apply { isDaemon = true }.start()
    }

    private fun fail(reason: String) {
        AndroidEnv.log("vpn: $reason")
        AndroidEnv.vpnLog("core: сбой — $reason")
        stopCore()
        closeTun()
        VpnBridge.running = false
        VpnBridge.starting = false
        VpnBridge.screenPaused = false
        getSharedPreferences("limenet", MODE_PRIVATE).edit().putBoolean(KEY_SCREEN_PAUSED, false).apply()
        setState(this, STATE_STOPPED)
        LimeNetQuickTileService.requestRefresh(this)
        VpnBridge.reportCoreFailure(reason)
        tearDownForeground()
        stopSelf()
    }

    private fun stopEverything(reason: String) {
        AndroidEnv.vpnLog("stop: $reason")
        newGeneration()
        stopCore()
        closeTun()
        VpnBridge.reset()
        getSharedPreferences("limenet", MODE_PRIVATE).edit().putBoolean(KEY_SCREEN_PAUSED, false).apply()
        setState(this, STATE_STOPPED)
        LimeNetQuickTileService.requestRefresh(this)
        tearDownForeground()
        stopSelf()
    }

    private fun newGeneration(): Int = ++generation

    @Synchronized
    private fun stopCore() {
        val pid = processPid
        processPid = null
        if (pid != null) {
            NativeXray.terminate(pid, false)
            val result = NativeXray.waitFor(pid, TimeUnit.SECONDS.toMillis(2))
            if (result == Int.MIN_VALUE) {
                NativeXray.terminate(pid, true)
                NativeXray.waitFor(pid, -1)
            }
        }
    }

    private fun closeTun() {
        runCatching { tun?.close() }
        tun = null
    }

    // ---------------------------------------------------------- уведомление

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.vpn_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, LimeNetVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.vpn_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.vpn_stop),
                    stop,
                ).build(),
            )
            .build()
    }

    private fun connectedNotificationText(): String = VpnBridge.serverName
        ?.takeIf { it.isNotBlank() }
        ?.let { getString(R.string.vpn_connected_server, it) }
        ?: getString(R.string.vpn_connected)

    private fun goForeground(text: String) {
        runCatching {
            val notification = buildNotification(text)
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        }.onFailure {
            AndroidEnv.vpnLog("service: startForeground упал: $it")
        }
    }

    private fun tearDownForeground() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    companion object {
        const val ACTION_STOP = "com.limeprod.limenet.STOP"
        private const val ACTION_PAUSE_FOR_SCREEN = "com.limeprod.limenet.PAUSE_FOR_SCREEN"
        private const val ACTION_RESUME_FOR_SCREEN = "com.limeprod.limenet.RESUME_FOR_SCREEN"
        const val EXTRA_VPN = "vpn"
        const val EXTRA_SERVER_NAME = "server_name"

        const val STATE_STOPPED = "stopped"
        const val STATE_STARTING = "starting"
        const val STATE_RUNNING = "running"
        const val STATE_STOPPING = "stopping"
        const val STATE_PAUSED = "paused_screen"
        const val KEY_SERVICE_STATE = "vpn_service_state"
        private const val KEY_SERVICE_STATE_CHANGED_AT = "vpn_service_state_changed_at"
        const val KEY_DIRECT_APPS = "vpn_direct_apps"
        const val KEY_PAUSE_VPN_WITH_SCREEN = "pause_vpn_with_screen"
        /** Узел, для которого записан `xray-android.json`; защита от запуска старого сервера. */
        const val KEY_CONFIG_NODE_ID = "last_config_node_id"
        private const val KEY_SCREEN_PAUSED = "vpn_screen_paused"
        private const val START_STATE_GRACE_MS = 20_000L

        private const val CHANNEL_ID = "limenet-vpn"
        private const val NOTIF_ID = 1

        fun start(context: Context, vpn: Boolean, serverName: String? = null) {
            setState(context, STATE_STARTING)
            val intent = Intent(context, LimeNetVpnService::class.java)
                .putExtra(EXTRA_VPN, vpn)
            if (!serverName.isNullOrBlank()) intent.putExtra(EXTRA_SERVER_NAME, serverName)
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
            }
                .onFailure {
                    setState(context, STATE_STOPPED)
                    VpnBridge.starting = false
                    VpnBridge.reportCoreFailure("не удалось запустить VPN-сервис: ${it.message ?: it.javaClass.simpleName}")
                    AndroidEnv.log("vpn: не удалось запустить сервис: $it")
                }
        }

        fun stop(context: Context) {
            setState(context, STATE_STOPPING)
            val intent = Intent(context, LimeNetVpnService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
                .onFailure {
                    setState(context, STATE_STOPPED)
                    AndroidEnv.log("vpn: не удалось остановить сервис: $it")
                }
        }

        /** Restore an automatically paused VPN when the user disables the screen-pause option. */
        fun resumeIfPauseDisabled(context: Context) {
            val prefs = context.getSharedPreferences("limenet", Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_PAUSE_VPN_WITH_SCREEN, false) ||
                !prefs.getBoolean(KEY_SCREEN_PAUSED, false)
            ) return
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(Intent(context, LimeNetVpnService::class.java).setAction(ACTION_RESUME_FOR_SCREEN))
                } else {
                    context.startService(Intent(context, LimeNetVpnService::class.java).setAction(ACTION_RESUME_FOR_SCREEN))
                }
            }.onFailure { AndroidEnv.log("vpn: не удалось возобновить после отключения экран-паузы: $it") }
        }

        /** Apply the newly enabled setting immediately if the display is already off. */
        fun pauseIfScreenOff(context: Context) {
            val prefs = context.getSharedPreferences("limenet", Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_PAUSE_VPN_WITH_SCREEN, false) || state(context) != STATE_RUNNING) return
            val interactive = runCatching {
                val power = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                @Suppress("DEPRECATION")
                power.isInteractive
            }.getOrDefault(true)
            if (interactive) return
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(Intent(context, LimeNetVpnService::class.java).setAction(ACTION_PAUSE_FOR_SCREEN))
                } else {
                    context.startService(Intent(context, LimeNetVpnService::class.java).setAction(ACTION_PAUSE_FOR_SCREEN))
                }
            }.onFailure { AndroidEnv.log("vpn: не удалось поставить VPN на паузу при выключенном экране: $it") }
        }

        fun state(context: Context): String = context.getSharedPreferences("limenet", Context.MODE_PRIVATE)
            .getString(KEY_SERVICE_STATE, STATE_STOPPED) ?: STATE_STOPPED

        /** Clear state left behind if Android killed the process without callbacks. */
        fun reconciledState(context: Context): String {
            val prefs = context.getSharedPreferences("limenet", Context.MODE_PRIVATE)
            val current = state(context)
            if (current == STATE_STOPPED) return current

            val serviceAlive = runCatching {
                val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                manager.getRunningServices(Int.MAX_VALUE).any {
                    it.service?.className == LimeNetVpnService::class.java.name
                }
            }.getOrDefault(true)
            if (serviceAlive) return current

            val changedAt = prefs.getLong(KEY_SERVICE_STATE_CHANGED_AT, 0L)
            val stateAge = System.currentTimeMillis() - changedAt
            val stale = when (current) {
                STATE_STARTING -> stateAge > START_STATE_GRACE_MS
                STATE_RUNNING, STATE_STOPPING -> true
                // A paused service is sticky and must retain the pending resume intent
                // while Android recreates its process with the display still off.
                STATE_PAUSED -> false
                else -> false
            }
            if (stale) setState(context, STATE_STOPPED)
            return state(context)
        }

        fun setState(context: Context, state: String) {
            context.getSharedPreferences("limenet", Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SERVICE_STATE, state)
                .putLong(KEY_SERVICE_STATE_CHANGED_AT, System.currentTimeMillis())
                .apply()
            LimeNetQuickTileService.requestRefresh(context)
        }
    }
}
