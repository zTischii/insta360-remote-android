package dev.hansel.insta360remote.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-Memory-Ringpuffer fuer Diagnoseausgaben.
 *
 * WICHTIG FUERS REVERSE-ENGINEERING: Saemtliche GATT-Kommunikation (Bytes der
 * Kamera und unserer Notifies) wird hier als Hex-Dump mitgeloggt. So laesst sich
 * das GPS-Payload-Format direkt aus der App heraus gegenpruefen, ohne zwingend
 * einen externen Sniffer zu brauchen (HCI snoop log bleibt trotzdem empfohlen).
 */
object Diagnostics {

    private const val MAX_LINES = 500
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun log(tag: String, message: String) {
        Log.i(tag, message)
        append("${timeFormat.format(Date())} $tag: $message")
    }

    fun append(line: String) {
        val next = ArrayList<String>(_lines.value.size + 1)
        next.addAll(_lines.value)
        next.add(line)
        if (next.size > MAX_LINES) {
            next.subList(0, next.size - MAX_LINES).clear()
        }
        _lines.value = next
    }

    fun hex(bytes: ByteArray?): String {
        if (bytes == null || bytes.isEmpty()) return "<empty>"
        val sb = StringBuilder(bytes.size * 3)
        for (b in bytes) {
            sb.append(String.format(Locale.US, "%02X ", b))
        }
        return sb.toString().trim()
    }

    fun clear() {
        _lines.value = emptyList()
    }
}
