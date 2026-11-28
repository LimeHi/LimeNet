package com.limeprod.limenet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MainActivity : ComponentActivity() {

    private var deepLink by mutableStateOf<String?>(null)
    private var tileConnectPending = false

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            controller.launchVpnService()
        } else {
            controller.vpnDenied()
        }
    }

    private val notifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidEnv.attach(application)
        controller.onNeedVpn = { requestVpn() }
        deepLink = intent?.dataString
        tileConnectPending = intent?.action == ACTION_TILE_CONNECT

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MobileApp(controller, deepLink)
        }
    }

    override fun onResume() {
        super.onResume()
        controller.onAppEntered()
        handlePendingTileConnect()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLink = intent.dataString
        tileConnectPending = intent.action == ACTION_TILE_CONNECT
        if (tileConnectPending && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            handlePendingTileConnect()
        }
    }

    private fun handlePendingTileConnect() {
        if (!tileConnectPending) return
        tileConnectPending = false
        controller.toggleConnection()
    }

    private fun requestVpn() {
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            vpnPermission.launch(prepare)
        } else {
            controller.launchVpnService()
        }
    }

    companion object {
        const val ACTION_TILE_CONNECT = "com.limeprod.limenet.TILE_CONNECT"

        /** Контроллер живёт вместе с процессом: сервис и активность делят состояние. */
        @Volatile
        private var shared: AndroidController? = null

        private fun obtain(): AndroidController =
            shared ?: synchronized(this) {
                shared ?: AndroidController(
                    CoroutineScope(SupervisorJob() + Dispatchers.Default),
                ).also { shared = it }
            }
    }

    private val controller: AndroidController get() = obtain()
}
