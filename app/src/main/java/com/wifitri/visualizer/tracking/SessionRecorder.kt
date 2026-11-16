package com.wifitri.visualizer.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecInfo(val active: Boolean = false, val startMs: Long = 0L, val events: Int = 0, val markers: Int = 0, val lastFile: File? = null)

/**
 * Writes a JSON-lines log of everything the tracker sees so a session can be replayed and analysed offline:
 * raw readings with the dead-reckoned position they were taken at, steps, headings, scan outcomes, the estimator's output,
 * and user-dropped markers (including "the AP is exactly here", which gives ground truth).
 * Pure Kotlin (no Android types) so it is unit-testable.
 */
class SessionRecorder(private val dir: File) {
    private var writer: BufferedWriter? = null
    private var lastHeadingMs = 0L
    private var markerCount = 0
    private var events = 0
    private var startMs = 0L

    private val _info = MutableStateFlow(RecInfo())
    val info: StateFlow<RecInfo> = _info

    val active: Boolean get() = writer != null

    @Synchronized
    fun start(header: Map<String, Any?>, nowMs: Long = System.currentTimeMillis()): File {
        stop()
        dir.mkdirs()
        val f = File(dir, "wifi-compass-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs)) + ".jsonl")
        writer = f.bufferedWriter()
        startMs = nowMs; events = 0; markerCount = 0; lastHeadingMs = 0L
        _info.value = RecInfo(true, nowMs, 0, 0, f)
        write("header", nowMs, header)
        return f
    }

    @Synchronized
    fun stop(): File? {
        val w = writer ?: return _info.value.lastFile
        w.flush(); w.close(); writer = null
        _info.value = _info.value.copy(active = false, events = events)
        return _info.value.lastFile
    }

    // ---- events ----

    fun step(t: Long, x: Double, y: Double, steps: Int, headingRad: Double) =
        write("step", t, mapOf("x" to x, "y" to y, "n" to steps, "h" to headingRad))

    /** Heading changes continuously; keep ~5 Hz. */
    @Synchronized
    fun heading(t: Long, headingRad: Double, source: String) {
        if (writer == null || t - lastHeadingMs < 200) return
        lastHeadingMs = t
        write("heading", t, mapOf("h" to headingRad, "src" to source))
    }

    fun reading(t: Long, radio: String, id: String, name: String, rssi: Double, freq: Int, scanTsUs: Long, x: Double, y: Double) =
        write("reading", t, mapOf("radio" to radio, "id" to id, "name" to name, "rssi" to rssi, "freq" to freq, "scanTsUs" to scanTsUs, "x" to x, "y" to y))

    fun scanStats(t: Long, accepted: Int, refused: Int, intervalMs: Long) =
        write("scan", t, mapOf("accepted" to accepted, "refused" to refused, "intervalMs" to intervalMs))

    fun estimate(t: Long, fields: Map<String, Any?>) = write("estimate", t, fields)

    fun source(t: Long, source: String) = write("heading_source", t, mapOf("src" to source))

    /** kind: "mark" (a spot you tell me about) or "ap" (you are standing next to the access point being tracked). */
    @Synchronized
    fun marker(t: Long, kind: String, x: Double, y: Double, target: String): String {
        if (writer == null) return ""
        markerCount++
        val label = (if (kind == "ap") "AP" else "M") + markerCount
        write("marker", t, mapOf("kind" to kind, "label" to label, "x" to x, "y" to y, "target" to target))
        _info.value = _info.value.copy(markers = markerCount)
        return label
    }

    // ---- JSON lines ----

    @Synchronized
    private fun write(kind: String, t: Long, fields: Map<String, Any?>) {
        val w = writer ?: return
        w.write(line(kind, t, fields)); w.write("\n")
        events++
        if (events % 50 == 0) w.flush()
        if (events % 20 == 0) _info.value = _info.value.copy(events = events)
    }

    companion object {
        fun line(kind: String, t: Long, fields: Map<String, Any?>): String {
            val sb = StringBuilder("{\"e\":\"").append(kind).append("\",\"t\":").append(t)
            for ((k, v) in fields) {
                sb.append(",\"").append(k).append("\":")
                when (v) {
                    null -> sb.append("null")
                    is Double -> sb.append(if (v.isNaN() || v.isInfinite()) "null" else String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.'))
                    is Float -> sb.append(String.format(Locale.US, "%.4f", v))
                    is Number, is Boolean -> sb.append(v)
                    else -> sb.append('"').append(escape(v.toString())).append('"')
                }
            }
            return sb.append('}').toString()
        }

        private fun escape(s: String): String {
            val sb = StringBuilder()
            for (c in s) when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
            return sb.toString()
        }
    }
}
