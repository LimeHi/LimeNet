package com.limeprod.limenet

/** Starts Xray with the TUN descriptor duplicated to FD 3 before exec. */
internal object NativeXray {
    init {
        System.loadLibrary("tunlauncher")
    }

    @JvmStatic external fun launch(
        tunFd: Int,
        binary: String,
        config: String,
        assets: String,
        log: String,
    ): Int

    /** Returns an exit code, a negative errno, or Int.MIN_VALUE while running. */
    @JvmStatic external fun waitFor(pid: Int, timeoutMs: Long): Int

    @JvmStatic external fun terminate(pid: Int, force: Boolean): Boolean
}
