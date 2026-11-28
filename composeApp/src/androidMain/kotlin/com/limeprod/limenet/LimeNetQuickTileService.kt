package com.limeprod.limenet

import android.content.ComponentName
import android.content.Intent
import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.net.VpnService

/** Quick Settings tile for connecting to or disconnecting LimeNet. */
class LimeNetQuickTileService : TileService() {

    override fun onCreate() {
        super.onCreate()
        // TileService can be started in a fresh process without MainActivity.
        AndroidEnv.attach(application)
    }

    override fun onStartListening() {
        super.onStartListening()
        syncTile()
    }

    override fun onClick() {
        super.onClick()
        val serviceState = LimeNetVpnService.reconciledState(this)
        if (serviceState == LimeNetVpnService.STATE_RUNNING ||
            serviceState == LimeNetVpnService.STATE_STARTING ||
            serviceState == LimeNetVpnService.STATE_PAUSED ||
            VpnBridge.running || VpnBridge.starting
        ) {
            LimeNetVpnService.setState(this, LimeNetVpnService.STATE_STOPPING)
            LimeNetVpnService.stop(this)
        } else if (serviceState == LimeNetVpnService.STATE_STOPPING) {
            // Ignore taps while the previous stop is still being completed.
        } else {
            val permissionRequired = VpnService.prepare(this) != null
            val prefs = getSharedPreferences("limenet", MODE_PRIVATE)
            val hasTunConfig = prefs.getBoolean("last_config_is_tun", false) &&
                runCatching { AndroidEnv.configFile.isFile }.getOrDefault(false)
            // Конфиг мог остаться от прежнего узла: тогда запуск плиткой поднял бы
            // старый сервер. В этом случае открываем приложение — оно пересоберёт конфиг.
            val selectedNode = runCatching { MobileStore().load().settings.selectedNodeId }.getOrNull()
            val configNode = prefs.getString(LimeNetVpnService.KEY_CONFIG_NODE_ID, null)
            val configIsCurrent = selectedNode == null || selectedNode == configNode
            if (!permissionRequired && hasTunConfig && configIsCurrent) {
                val serverName = getSharedPreferences("limenet", MODE_PRIVATE)
                    .getString("last_server_name", null)
                LimeNetVpnService.start(this, vpn = true, serverName = serverName)
            } else {
                // Android требует Activity для первого системного подтверждения VPN.
                val intent = Intent(this, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_TILE_CONNECT)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (Build.VERSION.SDK_INT >= 34) {
                    val pending = PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    startActivityAndCollapse(pending)
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(intent)
                }
            }
        }
        syncTile()
    }

    private fun syncTile() {
        qsTile?.let { tile ->
            val serviceState = LimeNetVpnService.reconciledState(this)
            val running = VpnBridge.running || serviceState == LimeNetVpnService.STATE_RUNNING
            val starting = VpnBridge.starting || serviceState == LimeNetVpnService.STATE_STARTING
            val paused = serviceState == LimeNetVpnService.STATE_PAUSED ||
                (VpnBridge.screenPaused && serviceState != LimeNetVpnService.STATE_STOPPED)
            val active = running || starting || paused || serviceState == LimeNetVpnService.STATE_STOPPING
            tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = when {
                running -> "LimeNet · Вкл"
                starting -> "LimeNet · запуск"
                paused -> "LimeNet · пауза"
                serviceState == LimeNetVpnService.STATE_STOPPING -> "LimeNet · стоп"
                else -> "LimeNet"
            }
            if (Build.VERSION.SDK_INT >= 29) {
                tile.subtitle = when {
                    running -> VpnBridge.serverName?.take(40) ?: "VPN подключён"
                    starting -> "Подключение…"
                    paused -> "На паузе до включения экрана"
                    serviceState == LimeNetVpnService.STATE_STOPPING -> "Отключение…"
                    else -> "Нажмите, чтобы подключить"
                }
            }
            tile.updateTile()
        }
    }

    companion object {
        fun requestRefresh(context: android.content.Context) {
            if (Build.VERSION.SDK_INT < 29) return
            runCatching {
                requestListeningState(
                    context,
                    ComponentName(context, LimeNetQuickTileService::class.java),
                )
            }
        }
    }
}
