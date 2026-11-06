package com.nova5.xboxcontrol

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.view.KeyEvent
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs

const val DASHBOARD_PORT = 29999
const val MOVE_PORT = 30003
private const val DEADZONE = 0.20f
private const val TRIGGER_THRESHOLD = 0.5f
private const val LOOP_PERIOD_MS = 50L // 20 Hz, same as the Python script

/** Minimal client for Dobot's ASCII TCP-IP protocol. */
class Dobot(private val socket: Socket) {
    fun send(cmd: String): String {
        socket.getOutputStream().apply {
            write(cmd.toByteArray(Charsets.UTF_8))
            flush()
        }
        return try {
            val buf = ByteArray(1024)
            val n = socket.getInputStream().read(buf)
            if (n < 0) throw IOException("Robot closed the connection")
            String(buf, 0, n, Charsets.UTF_8)
        } catch (e: SocketTimeoutException) {
            ""
        }
    }

    fun close() {
        try { socket.close() } catch (_: IOException) {}
    }
}

/**
 * Open a socket, preferring a Wi-Fi/Ethernet network. Wi-Fi networks without
 * internet (typical for a robot LAN) are otherwise bypassed by Android in favour
 * of mobile data.
 */
@Suppress("DEPRECATION")
private fun openSocket(ctx: Context, ip: String, port: Int): Socket {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val net = cm.allNetworks.firstOrNull {
        val caps = cm.getNetworkCapabilities(it)
        caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }
    val s = net?.socketFactory?.createSocket() ?: Socket()
    s.tcpNoDelay = true
    s.soTimeout = 400
    s.connect(InetSocketAddress(ip, port), 3000)
    return s
}

private fun signOrNull(v: Float): String? = when {
    v > DEADZONE -> "+"
    v < -DEADZONE -> "-"
    else -> null
}

class Teleop(
    private val ctx: Context,
    private val ip: String,
    private val scriptA: String,
    private val scriptB: String,
    private val j5Invert: Boolean,
    private val j3Invert: Boolean,
    private val startSpeed: Int,
    private val pad: PadState,
    private val log: (String) -> Unit,
    private val onEnded: () -> Unit,
) : Thread("nova5-teleop") {

    @Volatile private var running = true
    private val commands = ConcurrentLinkedQueue<String>()

    fun shutdown() { running = false }
    fun requestEnable() { commands.add("enable") }
    fun requestDisable() { commands.add("disable") }

    override fun run() {
        var dash: Dobot? = null
        var move: Dobot? = null
        try {
            log("Connecting to $ip ...")
            dash = Dobot(openSocket(ctx, ip, DASHBOARD_PORT))
            move = Dobot(openSocket(ctx, ip, MOVE_PORT))
            log("Connected.")

            var speed = startSpeed.coerceIn(1, 100)
            log("SpeedFactor($speed): " + dash.send("SpeedFactor($speed)").trim())
            log("Ready. Press Start to enable the robot.")

            var cartesian = true
            var currentJog = ""
            var paused = false
            var prevKeys: Set<Int> = emptySet()
            var prevHy = 0

            while (running) {
                val t0 = SystemClock.elapsedRealtime()

                // ---- On-screen buttons ----
                while (true) {
                    when (commands.poll() ?: break) {
                        "enable" -> log(dash.send("EnableRobot()").trim().ifEmpty { "EnableRobot() sent" })
                        "disable" -> {
                            move.send("MoveJog()")
                            currentJog = ""
                            log(dash.send("DisableRobot()").trim().ifEmpty { "DisableRobot() sent" })
                        }
                    }
                }

                // ---- Buttons (edge-triggered) ----
                val down = HashSet(pad.keys)
                fun pressed(k: Int) = k in down && k !in prevKeys

                if (pressed(KeyEvent.KEYCODE_BUTTON_START)) {
                    log(dash.send("EnableRobot()").trim().ifEmpty { "EnableRobot() sent" })
                }
                if (pressed(KeyEvent.KEYCODE_BUTTON_SELECT)) { // Back / View
                    move.send("MoveJog()")
                    currentJog = ""
                    log(dash.send("DisableRobot()").trim().ifEmpty { "DisableRobot() sent" })
                }
                if (pressed(KeyEvent.KEYCODE_BUTTON_Y)) {
                    cartesian = !cartesian
                    log("Mode: " + if (cartesian) "Cartesian" else "Joint")
                }
                if (pressed(KeyEvent.KEYCODE_BUTTON_B)) {
                    log(dash.send("ClearError()").trim().ifEmpty { "ClearError() sent" })
                }
                if (pressed(KeyEvent.KEYCODE_BUTTON_A)) {
                    log(dash.send("RunScript($scriptA)").trim().ifEmpty { "RunScript($scriptA) sent" })
                }
                if (pressed(KeyEvent.KEYCODE_BUTTON_X)) {
                    log(dash.send("RunScript($scriptB)").trim().ifEmpty { "RunScript($scriptB) sent" })
                }
                if (pressed(KeyEvent.KEYCODE_BUTTON_THUMBL)) {
                    log(dash.send("StopScript()").trim().ifEmpty { "StopScript() sent" })
                    paused = false
                }
                if (pressed(KeyEvent.KEYCODE_BUTTON_THUMBR)) {
                    val cmd = if (paused) "ContinueScript()" else "PauseScript()"
                    log(dash.send(cmd).trim().ifEmpty { "$cmd sent" })
                    paused = !paused
                }

                // ---- D-pad: hat axis or DPAD key events ----
                var hx = when {
                    pad.hatX > 0.5f -> 1
                    pad.hatX < -0.5f -> -1
                    else -> 0
                }
                var hy = when { // flip: Android hat up is -1; here up = +1
                    pad.hatY < -0.5f -> 1
                    pad.hatY > 0.5f -> -1
                    else -> 0
                }
                if (hx == 0) {
                    if (KeyEvent.KEYCODE_DPAD_RIGHT in down) hx = 1
                    else if (KeyEvent.KEYCODE_DPAD_LEFT in down) hx = -1
                }
                if (hy == 0) {
                    if (KeyEvent.KEYCODE_DPAD_UP in down) hy = 1
                    else if (KeyEvent.KEYCODE_DPAD_DOWN in down) hy = -1
                }
                if (hy != prevHy) {
                    if (hy == 1) {
                        speed = (speed + 10).coerceAtMost(100)
                        dash.send("SpeedFactor($speed)")
                        log("Speed: $speed%")
                    } else if (hy == -1) {
                        speed = (speed - 10).coerceAtLeast(1)
                        dash.send("SpeedFactor($speed)")
                        log("Speed: $speed%")
                    }
                    prevHy = hy
                }

                // ---- Pick one axis to jog (same priority as the Python script) ----
                val lx = pad.lx
                val ly = -pad.ly
                val rx = pad.rx
                val ry = -pad.ry
                val names = if (cartesian) listOf("X", "Y", "Z", "Rz", "Rx", "Ry")
                else listOf("J1", "J2", "J3", "J4", "J5", "J6")

                var desired: String? = null
                signOrNull(lx)?.let { desired = names[0] + it }
                if (desired == null) signOrNull(ly)?.let { desired = names[1] + it }
                if (desired == null) signOrNull(ry)?.let { desired = names[2] + it }
                if (desired == null) signOrNull(rx)?.let { desired = names[3] + it }
                if (desired == null) {
                    // D-pad left/right always jogs joint J3, in both modes.
                    if (hx == 1) desired = if (j3Invert) "J3-" else "J3+"
                    else if (hx == -1) desired = if (j3Invert) "J3+" else "J3-"
                }
                if (desired == null) {
                    val plus = if (j5Invert) "-" else "+"
                    val minus = if (j5Invert) "+" else "-"
                    if (pad.rt > TRIGGER_THRESHOLD && pad.rt >= pad.lt) desired = names[4] + plus
                    else if (pad.lt > TRIGGER_THRESHOLD) desired = names[4] + minus
                }
                if (desired == null) {
                    if (KeyEvent.KEYCODE_BUTTON_R1 in down) desired = names[5] + "+"
                    else if (KeyEvent.KEYCODE_BUTTON_L1 in down) desired = names[5] + "-"
                }

                val want = desired ?: ""
                if (want != currentJog) {
                    move.send("MoveJog($want)")
                    currentJog = want
                    log(if (want.isEmpty()) "Jog: stop" else "Jog: $want")
                }

                prevKeys = down
                val elapsed = SystemClock.elapsedRealtime() - t0
                if (elapsed < LOOP_PERIOD_MS) sleep(LOOP_PERIOD_MS - elapsed)
            }
        } catch (e: IOException) {
            log("Connection error: ${e.message}")
        } catch (e: InterruptedException) {
            // shutting down
        } finally {
            try { move?.send("MoveJog()") } catch (_: Exception) {}
            dash?.close()
            move?.close()
            log("Disconnected.")
            onEnded()
        }
    }
}
