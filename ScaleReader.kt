package com.example.scalereader

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID
import kotlin.random.Random

/**
 * true = the app guesses the scale's unit from the look of numbers below 1 (KG data looks like 0.054,
 * LITRE data looks like .200). Numbers of 1 and above give no clue. false = ignore the format and only use unit letters (kg / L) if the scale sends them.
 */
const val AUTO_UNIT_FROM_FORMAT = false

/**
 * Scale streams ~10 lines/sec non-stop. We keep reading silently and publish to the
 * UI only when the text actually changes.
 */
class ScaleReader {
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    // number with optional leading dot (".200"), optional sign
    private val NUM = Regex("[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)")
    private val LITRE_PREFIX = Regex("^\\s*[lL]")           // "L 0.400" / "L0.400": the scale shows an L in litre mode
    private var prefixSeen = false
    private val LEADING_DOT = Regex("^[+-]?\\.\\d+$")        // ".200"  looks like LITRE data
    private val LEADING_ZERO = Regex("^[+-]?0\\.\\d+$")      // "0.054" looks like KG data
    private var fmtGuess: Boolean? = null
    private var fmtVotes = 0
    private val LITRE_TAG = Regex("\\bl(itre|iter|tr|t)?\\b")

    /** Line ending added after every command. The Pecoss app sends bare letters, so the default is none (""). */
    var cmdEnd: String = ""

    val raw = MutableStateFlow<Double?>(null)   // numeric value (changes only)
    val text = MutableStateFlow("0.000")        // number text as the scale sends it
    val stable = MutableStateFlow(false)
    val connected = MutableStateFlow(false)

    /** Unit the scale reports in its own data: true = litre, false = kg, null = no marker seen. */
    val unitFromScale = MutableStateFlow<Boolean?>(null)

    /** Last raw lines (only when the text changes) and sent commands, for the Diagnostics panel. */
    val log = MutableStateFlow<List<String>>(emptyList())

    private var lastText: String? = null
    private var lastNote: String? = null
    private var same = 0
    private var job: Job? = null
    private var socket: BluetoothSocket? = null
    private var out: java.io.OutputStream? = null       // output stream of the CURRENT connection
    private var gen = 0                                  // connection generation (stops old jobs from clobbering new state)
    private var currentDevice: BluetoothDevice? = null

    private fun note(s: String) {
        if (s == lastNote) return
        lastNote = s
        log.value = (log.value + s).takeLast(150)
    }

    /** Adds a marker line to the Diagnostics log. */
    fun mark(s: String) = note(s)

    /** Pure logic, unit-testable. */
    fun feed(line: String) {
        val rawLine = line.trim()
        if (rawLine.isEmpty()) return
        note("< $rawLine")
        val t = NUM.find(rawLine)?.value ?: return
        val low = rawLine.lowercase()
        val v = t.toDoubleOrNull() ?: return
        if (low.contains("kg")) unitFromScale.value = false
        else if (LITRE_TAG.containsMatchIn(low) || LITRE_PREFIX.containsMatchIn(rawLine)) {
            prefixSeen = true; unitFromScale.value = true            // L marker -> LITRE
        }
        else if (prefixSeen) unitFromScale.value = false             // scale marks LITRE with L, so a plain number = KG
        else if (AUTO_UNIT_FROM_FORMAT && v != 0.0) {
            val guess: Boolean? = when {
                LEADING_DOT.matches(t) -> true       // LITRE
                LEADING_ZERO.matches(t) -> false     // KG
                else -> null                         // 1.250 etc: cannot tell, keep the current label
            }
            if (guess != null) {
                if (guess == fmtGuess) { if (++fmtVotes >= 4) unitFromScale.value = guess }
                else { fmtGuess = guess; fmtVotes = 1 }
            }
        }
        if (t == lastText) {
            if (++same == 8) stable.value = true      // ~0.8 s unchanged
            return
        }
        lastText = t; same = 0
        stable.value = false
        raw.value = v
        text.value = t
    }

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope, device: BluetoothDevice) {
        if (job?.isActive == true && currentDevice?.address == device.address) return   // already running: do not reconnect
        stop()
        currentDevice = device
        val my = ++gen
        job = scope.launch(Dispatchers.IO) {
            while (isActive && my == gen) {
                var s: BluetoothSocket? = null
                try {
                    s = device.createRfcommSocketToServiceRecord(SPP)
                    socket = s
                    s.connect()
                    out = s.outputStream
                    connected.value = true
                    note("* connected")
                    s.inputStream.bufferedReader().useLines { it.forEach(::feed) }
                } catch (e: Exception) {
                    note("* connection: ${e.javaClass.simpleName}")
                } finally {
                    if (my == gen) { connected.value = false; out = null }
                    runCatching { s?.close() }                 // close only THIS job's socket
                }
                delay(2000)   // retry
            }
        }
    }

    /** Fake scale for testing without hardware. */
    fun startDemo(scope: CoroutineScope) {
        stop()
        job = scope.launch(Dispatchers.Default) {
            connected.value = true
            val steps = listOf(0.0, 0.0, 0.350, 1.2, 1.250, 1.250, 1.250, 0.0)
            while (isActive) {
                for (w in steps) {
                    repeat(Random.nextInt(12, 25)) { feed("%.3f".format(w)); delay(100) }
                }
            }
        }
    }

    /** Sends a command to the scale exactly like the Pecoss app: raw letter bytes. "T" = tare, "M" = mode / unit. */
    fun send(cmd: String) {
        val bytes = (cmd + cmdEnd).toByteArray()
        note("> $cmd" + cmdEnd.replace("\r", "<CR>").replace("\n", "<LF>"))
        Thread {
            try {
                val o = out ?: throw java.io.IOException("not connected")
                o.write(bytes, 0, bytes.size)
                o.flush()
                note("  sent ${bytes.size} byte(s) OK")
            } catch (e: Exception) {
                note("  WRITE FAILED: ${e.javaClass.simpleName} ${e.message}")
            }
        }.start()
    }

    fun stop() {
        gen++
        currentDevice = null
        job?.cancel(); job = null
        runCatching { socket?.close() }
        connected.value = false
    }
}
