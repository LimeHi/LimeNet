package app.limenet.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.limenet.core.Mode
import app.limenet.desktop.ui.LimeNetApp
import app.limenet.resources.Res
import app.limenet.resources.limenet_icon
import app.limenet.ui.APP_NAME
import org.jetbrains.compose.resources.painterResource
import java.awt.Dimension

/**
 * Нужно ли при запуске просить права администратора: режим TUN сохранён в настройках,
 * прав ещё нет, и мы не перезапущены с UAC (метка [Platform.ELEVATED_FLAG] защищает от цикла,
 * например если проверка прав ошибочно говорит «нет»).
 */
private fun shouldElevate(args: Array<String>): Boolean {
    if (!Platform.isWindows) return false
    if (args.any { it.equals(Platform.ELEVATED_FLAG, ignoreCase = true) }) return false
    val mode = runCatching { Store().load().settings.mode }.getOrDefault(Mode.TUN)
    return mode == Mode.TUN && !Platform.isElevated()
}

fun main(args: Array<String>) {
    val link = args.firstOrNull { it.startsWith("limenet://", ignoreCase = true) }

    // Если приложение уже открыто — просто передаём ему ссылку и выходим.
    if (link != null && DeepLinks.send(link)) return

    // TUN требует прав администратора: просим UAC сразу при запуске и открываем новый экземпляр.
    // Если UAC отклонён — работаем без прав, в приложении остаётся кнопка «перезапустить».
    if (shouldElevate(args)) {
        val result = Platform.relaunchElevated(extraArgs = listOfNotNull(link))
        if (result.isSuccess) return
    }

    runApp(link)
}

private fun runApp(link: String?) = application {
    val scope = rememberCoroutineScope()
    val controller = remember { Controller(scope) }
    val windowState = rememberWindowState(width = 1080.dp, height = 780.dp)

    DisposableEffect(Unit) {
        DeepLinks.listen { controller.handleDeepLink(it) }
        onDispose { controller.shutdown() }
    }

    if (link != null) {
        LaunchedEffect(link) { controller.handleDeepLink(link) }
    }

    Window(
        onCloseRequest = {
            controller.shutdown()
            exitApplication()
        },
        title = APP_NAME,
        icon = painterResource(Res.drawable.limenet_icon),
        state = windowState,
    ) {
        window.minimumSize = Dimension(920, 640)
        LimeNetApp(controller)
    }
}
