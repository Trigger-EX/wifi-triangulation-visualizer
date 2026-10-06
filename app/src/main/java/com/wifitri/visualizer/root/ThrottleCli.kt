package com.wifitri.visualizer.root

import android.os.IBinder

/**
 * Runs as a ROOT process (never inside the app): `su -c "CLASSPATH=<apk> app_process /system/bin <this class> get|set 0|1"`.
 * Talks straight to the system WiFi service, which is what Developer options -> "Wi-Fi scan throttling" does,
 * instead of writing a Settings key that many ROMs ignore. Prints `THROTTLE=true|false` (true = throttling ON) or `ERR=...`.
 */
object ThrottleCli {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "wifi") as? IBinder
            if (binder == null) { println("ERR=wifi service not found"); return }
            val stub = Class.forName("android.net.wifi.IWifiManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
            if (args.firstOrNull() == "set") {
                val enable = args.getOrNull(1) == "1"
                val m = stub.javaClass.methods.firstOrNull { it.name == "setScanThrottleEnabled" }
                    ?: run { println("ERR=no setScanThrottleEnabled; throttle-related methods=" + names(stub)); return }
                m.invoke(stub, *argsFor(m.parameterTypes, enable))
            }
            val g = stub.javaClass.methods.firstOrNull { it.name == "isScanThrottleEnabled" }
                ?: run { println("ERR=no isScanThrottleEnabled; throttle-related methods=" + names(stub)); return }
            println("THROTTLE=" + g.invoke(stub, *argsFor(g.parameterTypes, true)))
        } catch (t: Throwable) {
            val root = generateSequence(t) { it.cause }.last()
            println("ERR=" + root.javaClass.simpleName + ": " + root.message)
        }
    }

    // Some Android versions add extra parameters (e.g. a calling package name); fill those best-effort.
    private fun argsFor(types: Array<Class<*>>, enable: Boolean): Array<Any?> = Array(types.size) { i ->
        when (types[i]) {
            java.lang.Boolean.TYPE -> enable
            String::class.java -> "com.android.shell"
            else -> null
        }
    }

    private fun names(stub: Any) = stub.javaClass.methods.map { it.name }.filter { it.contains("hrottle", true) }.distinct().toString()
}
