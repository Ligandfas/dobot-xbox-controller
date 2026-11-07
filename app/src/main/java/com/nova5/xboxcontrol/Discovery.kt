package com.nova5.xboxcontrol

import android.content.Context
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Factory defaults of Dobot CR / Nova controllers (LAN1, LAN2, Wi-Fi). */
val DOBOT_DEFAULT_IPS = listOf("192.168.5.1", "192.168.1.6", "192.168.2.6", "192.168.9.1")

private val DOBOT_REPLY = Regex("""^\s*-?\d+\s*,\s*\{""")

/**
 * True if [ip] answers like a Dobot dashboard server: it accepts a connection on port 29999
 * and replies to RobotMode() in the "ErrorID,{value},RobotMode();" format. Only that
 * read-only query is sent, so probing never moves or changes the robot.
 */
fun probeDobot(ctx: Context, ip: String, connectTimeoutMs: Int = 400): Boolean {
    return try {
        openSocket(ctx, ip, DASHBOARD_PORT, connectTimeoutMs, readTimeoutMs = 1200).use { s ->
            s.getOutputStream().apply { write("RobotMode()".toByteArray()); flush() }
            val buf = ByteArray(256)
            val n = s.getInputStream().read(buf)
            n > 0 && String(buf, 0, n, Charsets.UTF_8).let { it.contains("RobotMode") || DOBOT_REPLY.containsMatchIn(it) }
        }
    } catch (_: Exception) {
        false
    }
}

/**
 * Looks for a robot on the phone's local networks. With [repeat], keeps retrying every few
 * seconds (e.g. until the phone joins the robot's Wi-Fi) until it finds one or is cancelled.
 */
class Discovery(
    private val ctx: Context,
    private val preferred: List<String>,
    private val repeat: Boolean,
    private val initialDelayMs: Long,
    private val log: (String) -> Unit,
    private val onFound: (String) -> Unit,
    private val onNotFound: () -> Unit,
) : Thread("nova5-discovery") {

    @Volatile private var running = true
    @Volatile private var pool: ExecutorService? = null

    fun cancel() {
        running = false
        pool?.shutdownNow()
        interrupt()
    }

    override fun run() {
        try {
            if (initialDelayMs > 0) sleep(initialDelayMs)
            var lastNetsKey = ""
            while (running) {
                val nets = localNets(ctx)
                val key = nets.joinToString { "${it.addr.hostAddress}/${it.prefix}" }
                if (key != lastNetsKey) {
                    log(if (nets.isEmpty()) "Auto-detect: no Wi-Fi/Ethernet network yet - waiting"
                        else "Auto-detect: searching on " + nets.joinToString { "${it.addr.hostAddress}/${it.prefix}" })
                    lastNetsKey = key
                }
                val ip = if (nets.isEmpty()) null else findOnce(nets)
                if (!running) return
                if (ip != null) { onFound(ip); return }
                if (!repeat) { onNotFound(); return }
                sleep(4000)
            }
        } catch (_: InterruptedException) {
            // cancelled
        }
    }

    private fun findOnce(nets: List<LocalNet>): String? {
        val own = nets.map { it.addr.hostAddress }.toSet()
        val first = preferred.filter { it.isNotBlank() && it !in own }.distinct()
        val rest = nets.flatMap(::hostsOf).distinct().filter { it !in own && it !in first }
        // Known addresses first (quick), then the whole subnet in parallel.
        return firstHit(first, threads = first.size.coerceAtLeast(1), timeoutS = 4)
            ?: if (running && rest.isNotEmpty()) firstHit(rest, threads = 64, timeoutS = 25) else null
    }

    private fun firstHit(ips: List<String>, threads: Int, timeoutS: Long): String? {
        if (ips.isEmpty() || !running) return null
        val p = Executors.newFixedThreadPool(threads.coerceAtMost(ips.size))
        pool = p
        return try {
            p.invokeAny(ips.map { ip ->
                Callable { if (probeDobot(ctx, ip)) ip else throw NoSuchElementException(ip) }
            }, timeoutS, TimeUnit.SECONDS)
        } catch (_: Exception) {
            null // none answered, timed out, or cancelled
        } finally {
            p.shutdownNow()
            pool = null
        }
    }
}
