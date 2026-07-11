package cooking.schizo.hyprspace.debug

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Debug-only own-process logcat tailer for the in-app Logs tab. */
object DebugLogStore {

    private const val MAX_LINES = 1_000

    private val relevantMarkers = listOf(
        "hyprspace",
        "Hyprspace",
        "HyprspaceVpn",
        "gomobile",
        "GoLog",
        "go-log",
        "libp2p",
        "p2p",
        "DHT",
        "TUN",
        "vpn-tun",
    )

    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private var logcatProcess: Process? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return

        scope.launch {
            tailOwnProcessLogcat()
        }
    }

    fun clear() {
        _lines.value = emptyList()
    }

    fun snapshot(): String = lines.value.joinToString(separator = "\n")

    private fun tailOwnProcessLogcat() {
        val pid = android.os.Process.myPid().toString()
        append("Starting own-process logcat capture for pid $pid")

        try {
            val process = ProcessBuilder(
                "logcat",
                "-v",
                "threadtime",
                "--pid",
                pid,
            ).redirectErrorStream(true).start()

            logcatProcess = process
            process.inputStream.bufferedReader().useLines { sequence ->
                sequence
                    .filter(::isRelevantLogLine)
                    .forEach { append(it) }
            }
        } catch (t: Throwable) {
            append("Unable to capture logcat: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            logcatProcess?.destroy()
            logcatProcess = null
            started.set(false)
            append("Own-process logcat capture stopped")
        }
    }

    private fun isRelevantLogLine(line: String): Boolean =
        relevantMarkers.any { marker -> line.contains(marker) }

    private fun append(line: String) {
        _lines.update { existing ->
            (existing + line).takeLast(MAX_LINES)
        }
    }
}
