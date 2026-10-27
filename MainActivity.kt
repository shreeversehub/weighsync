package com.example.scalereader

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Locale
import java.util.concurrent.TimeUnit

// ---------- palette ----------
private val BgTop = Color(0xFF0B1220)
private val BgBottom = Color(0xFF16324F)
private val Neon = Color(0xFF00E676)
private val Amber = Color(0xFFFFB300)
private val Danger = Color(0xFFFF5252)
private val Glass = Color(0x1AFFFFFF)

// ---------- ViewModel ----------
class MainVM(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("scale", 0)
    val reader = ScaleReader()

    var lang by mutableStateOf(if (prefs.getString("lang", "en") == "ta") Lang.TA else Lang.EN)
    var litreMode by mutableStateOf(prefs.getBoolean("litre", false))
    var demo by mutableStateOf(false)
    var tareKg by mutableStateOf(0.0)
    var beepOn by mutableStateOf(prefs.getBoolean("beep", true))
    var voiceOn by mutableStateOf(prefs.getBoolean("voice", true))
    var status by mutableStateOf<License.Status>(License.check(app))
    val t: Txt get() = if (lang == Lang.TA) TA else EN
    val deviceId: String = License.deviceId(app)

    /** Command line ending sent after T / U. Pick in the Diagnostics panel (debug build). */
    var endKey by mutableStateOf(prefs.getString("end2", "NONE") ?: "NONE")
    init { reader.cmdEnd = endOf(endKey) }
    private fun endOf(k: String) = when (k) { "CR" -> "\r"; "LF" -> "\n"; "CRLF" -> "\r\n"; else -> "" }
    fun setEnd(k: String) { endKey = k; prefs.edit().putString("end2", k).apply(); reader.cmdEnd = endOf(k) }

    /**
     * Command test: sends T and M with every line ending (NONE, CR, LF, CRLF), 2.5 s apart (about 20 s).
     * Each step is written to the Diagnostics log, so a reaction of the scale shows up right below it.
     */
    fun sweepCommands() {
        viewModelScope.launch {
            val keep = reader.cmdEnd
            val hits = mutableListOf<String>()
            for ((end, name) in listOf("" to "NONE", "\r" to "CR", "\n" to "LF", "\r\n" to "CRLF")) {
                for (c in listOf("T", "M")) {
                    reader.cmdEnd = end
                    val before = reader.raw.value ?: 0.0
                    val markBefore = reader.unitFromScale.value
                    reader.mark("#### $c + $name")
                    reader.send(c)
                    delay(2500)
                    val after = reader.raw.value ?: 0.0
                    val changed = kotlin.math.abs(after - before) > maxOf(0.04, before * 0.1) ||
                        reader.unitFromScale.value != markBefore
                    if (changed) {
                        val m = "REACTION: $c + $name   ($before -> $after)"
                        hits += m
                        reader.mark(m)
                    }
                }
            }
            reader.cmdEnd = keep
            reader.mark(
                if (hits.isEmpty()) "#### RESULT: NO REACTION to any command"
                else "#### RESULT: ${hits.size} reaction(s). Look at the REACTION lines."
            )
        }
    }

    /** Label follows the scale when the scale reports its unit in the data. Nothing is sent. */
    fun setLitreLabel(b: Boolean) {
        if (litreMode != b) { litreMode = b; prefs.edit().putBoolean("litre", b).apply() }
    }

    private var ttsReady = false
    private val tts: TextToSpeech = TextToSpeech(app) { ttsReady = (it == TextToSpeech.SUCCESS) }

    fun changeLang(l: Lang) { lang = l; prefs.edit().putString("lang", if (l == Lang.TA) "ta" else "en").apply() }
    fun toggleBeep() { beepOn = !beepOn; prefs.edit().putBoolean("beep", beepOn).apply() }
    fun toggleVoice() { voiceOn = !voiceOn; prefs.edit().putBoolean("voice", voiceOn).apply() }

    /** TARE and UNIT press the scale's own keys, so device and app stay in step. */
    fun doTare() {
        if (SEND_TARE_TO_SCALE) { if (demo || reader.connected.value) reader.send("T"); return }
        tareKg = if (tareKg != 0.0) 0.0 else (reader.raw.value ?: 0.0)      // tap again to clear
    }
    fun toggleUnit() {
        if (SEND_UNIT_TO_SCALE && !demo && !reader.connected.value) return   // scale not connected: do nothing
        litreMode = !litreMode
        prefs.edit().putBoolean("litre", litreMode).apply()
        if (SEND_UNIT_TO_SCALE) reader.send("M")                 // "M" = Mode key (from the Pecoss app)
    }

    /** Fixes the label only (nothing is sent), e.g. after someone pressed the scale's own UNIT key. */
    fun flipLabelOnly() {
        litreMode = !litreMode
        prefs.edit().putBoolean("litre", litreMode).apply()
    }

    fun speak(text: String) {
        if (!ttsReady) return
        val loc = if (lang == Lang.TA) Locale("ta", "IN") else Locale("en", "IN")
        tts.language = if (tts.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) loc else Locale.ENGLISH
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "w")
    }

    fun activate(token: String) { status = License.install(getApplication(), token) }
    fun refreshLicense() { status = License.check(getApplication()) }

    /** True once per day, so the expiry pop-up is not shown again and again. */
    fun shouldAlertToday(): Boolean {
        val today = java.time.LocalDate.now().toEpochDay()
        if (prefs.getLong("alertDay", -1) == today) return false
        prefs.edit().putLong("alertDay", today).apply()
        return true
    }

    fun tamilVoiceOk(): Boolean =
        ttsReady && tts.isLanguageAvailable(Locale("ta", "IN")) >= TextToSpeech.LANG_AVAILABLE

    @SuppressLint("MissingPermission")
    fun bonded(): List<BluetoothDevice> = try {
        getApplication<Application>().getSystemService(BluetoothManager::class.java)
            ?.adapter?.bondedDevices?.toList().orEmpty()
    } catch (e: SecurityException) { emptyList() }

    @SuppressLint("MissingPermission")
    fun selectDevice(d: BluetoothDevice) {
        prefs.edit().putString("addr", d.address).apply()
        demo = false
        reader.start(viewModelScope, d)
    }

    fun autoConnect(): Boolean {
        if (demo) { reader.startDemo(viewModelScope); return true }
        val addr = prefs.getString("addr", null) ?: return false
        return try {
            val bm = getApplication<Application>().getSystemService(BluetoothManager::class.java)
            val dev = bm?.adapter?.getRemoteDevice(addr) ?: return false
            reader.start(viewModelScope, dev); true
        } catch (e: Exception) { false }
    }

    fun toggleDemo() {
        demo = !demo
        if (demo) reader.startDemo(viewModelScope) else { reader.stop(); autoConnect() }
    }

    override fun onCleared() { reader.stop(); tts.shutdown() }
}

// ---------- Activity ----------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // daily background check -> expiry notification (see ExpiryWorker.kt)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "expiry", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ExpiryWorker>(1, TimeUnit.DAYS).build()
        )
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val vm: MainVM = viewModel()
                Box(
                    Modifier.fillMaxSize()
                        .background(Brush.verticalGradient(listOf(BgTop, BgBottom)))
                        .systemBarsPadding()
                ) {
                    when (vm.status) {
                        is License.Status.Valid -> ScaleScreen(vm)
                        else -> ActivationScreen(vm)
                    }
                }
            }
        }
    }
}

// ---------- shared bits ----------
@Composable
fun LangToggle(vm: MainVM) {
    Row(Modifier.clip(RoundedCornerShape(20.dp)).background(Glass).padding(3.dp)) {
        listOf(Lang.EN to "EN", Lang.TA to "த").forEach { (l, label) ->
            val sel = vm.lang == l
            Text(
                label, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                color = if (sel) Color.Black else Color.White,
                modifier = Modifier.clip(RoundedCornerShape(16.dp))
                    .background(if (sel) Neon else Color.Transparent)
                    .clickable { vm.changeLang(l) }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
fun BigButton(text: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(64.dp).clip(RoundedCornerShape(18.dp))
            .background(Brush.horizontalGradient(listOf(color, color.copy(alpha = 0.7f))))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Black) }
}

@Composable
fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        color = if (on) Color.Black else Color.White.copy(alpha = 0.7f),
        modifier = Modifier.clip(RoundedCornerShape(20.dp))
            .background(if (on) Neon else Glass).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

fun qrBitmap(text: String, size: Int = 600): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (m[x, y]) AColor.BLACK else AColor.WHITE)
    return bmp
}

/**
 * true  = in LITRE mode the app converts the kg number itself, UNLESS the scale reports its own litre mode
 *         (an L in its data), in which case the scale's number is shown as it is.
 * false = always show exactly what the scale sends.
 */
const val APP_ROUNDS_LITRE = true

/**
 * false = LITRE is calculated only in the app; nothing is sent to the scale (recommended until the scale is proven
 *         to accept commands).
 * true  = the UNIT button also sends "U" to the scale.
 */
const val SEND_UNIT_TO_SCALE = true

/**
 * false = TARE is done inside the app (shown weight = scale weight - tare taken at the tap).
 * true  = the TARE button sends "T" to the scale (only if the scale really accepts commands).
 */
const val SEND_TARE_TO_SCALE = true

/**
 * Optional true-litre conversion before the rounding rule: litre = kg / DENSITY.
 * 1.0 = no density (the rounding rule only). Milk is about 1.03. Use a value other than 1.0 only if the scale
 * always sends KG numbers to the phone (otherwise the number would be converted twice).
 */
const val DENSITY = "1.0"

/**
 * Used in LITRE mode when APP_ROUNDS_LITRE = true: the app converts KG to LITRE itself.
 * Step is 0.1 litre below 1 kg and 1 litre from 1 kg up. Inside each step:
 * 0.20 of the step or more goes UP (ceil), less than 0.20 goes DOWN (floor).
 * 0.320 -> 0.400   0.560 -> 0.600   1.12 -> 1.000   1.25 -> 2.000   1.9 -> 2.000
 */
fun litreText(txt: String): String {
    val bd = txt.toBigDecimalOrNull() ?: return txt
    if (bd.abs() < java.math.BigDecimal("0.01") || bd.signum() < 0) return txt     // zero / noise: leave as is
    val density = java.math.BigDecimal(DENSITY)
    val x = if (density.compareTo(java.math.BigDecimal.ONE) == 0) bd
            else bd.divide(density, java.math.MathContext(10))                       // kg -> litre
    val small = x < java.math.BigDecimal.ONE
    val q = if (small) x.multiply(java.math.BigDecimal.TEN) else x                  // measured in steps
    val whole = q.setScale(0, java.math.RoundingMode.FLOOR)
    val frac = q.subtract(whole)
    val n = if (frac >= java.math.BigDecimal("0.20")) whole.add(java.math.BigDecimal.ONE) else whole
    val litres = if (small) n.divide(java.math.BigDecimal.TEN) else n
    return litres.setScale(3).toPlainString()
}

/** "1.250" -> "1.25", "0.000" -> "0": easier to hear. */
fun spoken(txt: String): String =
    txt.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: txt

// ---------- main scale screen ----------
@Composable
fun ScaleScreen(vm: MainVM) {
    val t = vm.t
    val ctx = LocalContext.current
    val view = LocalView.current
    val raw by vm.reader.raw.collectAsState()
    val txt by vm.reader.text.collectAsState()
    val stable by vm.reader.stable.collectAsState()
    val connected by vm.reader.connected.collectAsState()
    var showPicker by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showExpiry by remember { mutableStateOf(false) }
    var showDiag by remember { mutableStateOf(false) }
    val unitFromScale by vm.reader.unitFromScale.collectAsState()
    LaunchedEffect(unitFromScale) { unitFromScale?.let { vm.setLitreLabel(it) } }
    val valid = vm.status as? License.Status.Valid

    // expiry alert once a day when 30 days or fewer are left (pop-up + spoken)
    LaunchedEffect(valid?.daysLeft) {
        val d = valid?.daysLeft
        if (d != null && d <= 30 && vm.shouldAlertToday()) {
            showExpiry = true
            if (vm.voiceOn) { delay(1500); vm.speak(expiryMessage(vm.lang, d)) }
        }
    }
    val unitLabel = if (vm.litreMode) t.litre else t.kg
    val netText = if (vm.tareKg == 0.0) txt else String.format(Locale.US, "%.3f", (raw ?: 0.0) - vm.tareKg)
    val shownText = when {
        unitFromScale == true -> txt                                        // the scale itself is in litre mode: show its number
        vm.litreMode && APP_ROUNDS_LITRE -> litreText(netText)              // app converts kg -> litre
        else -> netText
    }

    // keep the screen on while weighing
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }

    // beep + vibrate + speak when the weight settles
    LaunchedEffect(stable) {
        if (stable && (raw ?: 0.0) != 0.0) {
            if (vm.beepOn) {
                try {
                    val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 90)
                    tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
                    delay(250); tg.release()
                } catch (_: Exception) {}
                try {
                    ctx.getSystemService(android.os.Vibrator::class.java)
                        ?.vibrate(android.os.VibrationEffect.createOneShot(80, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } catch (_: Exception) {}
            }
            if (vm.voiceOn) {
                val word = if (vm.lang == Lang.TA) (if (vm.litreMode) "லிட்டர்" else "கிலோ")
                           else (if (vm.litreMode) "litre" else "kilo")
                vm.speak("${spoken(shownText)} $word")
            }
        }
    }

    fun connect() { if (!vm.autoConnect() && !vm.demo) showPicker = true }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun askNotif() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) connect()
        askNotif()
    }
    LaunchedEffect(Unit) {
        vm.refreshLicense()
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) else { connect(); askNotif() }
    }

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // header
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    t.appName, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.clickable { showAbout = true }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(if (connected) Neon else Danger))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (connected) t.connected else t.disconnected, fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.clickable { showPicker = true }
                    )
                }
            }
            if (BuildConfig.DEBUG) {
                Text("DIAG", color = Color.Gray, fontSize = 12.sp,
                    modifier = Modifier.padding(end = 10.dp).clickable { showDiag = true })
                Text("DEMO", color = if (vm.demo) Neon else Color.Gray, fontSize = 12.sp,
                    modifier = Modifier.padding(end = 10.dp).clickable { vm.toggleDemo() })
            }
            LangToggle(vm)
        }

        // expiry banner (30 days or fewer)
        valid?.let {
            val d = it.daysLeft
            if (d <= 30) {
                Text(
                    "⚠  ${expiryMessage(vm.lang, d)}", color = Color.Black, fontWeight = FontWeight.Bold,
                    fontSize = 15.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(if (d <= 7) Danger else Amber)
                        .clickable { showExpiry = true }.padding(12.dp)
                )
            }
        }

        // beep / voice chips
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip("${t.beep}: ${if (vm.beepOn) t.on else t.off}", vm.beepOn) { vm.toggleBeep() }
            Chip("${t.voice}: ${if (vm.voiceOn) t.on else t.off}", vm.voiceOn) { vm.toggleVoice() }
        }

        // weight card
        Column(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(28.dp))
                .background(Glass).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(28.dp))
                .clickable {
                    val w = if (vm.lang == Lang.TA) (if (vm.litreMode) "லிட்டர்" else "கிலோ") else (if (vm.litreMode) "litre" else "kilo")
                    vm.speak("${spoken(shownText)} $w")
                }.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
        ) {
            Text(t.weight, color = Color.White.copy(alpha = 0.6f), letterSpacing = 4.sp, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                shownText, fontSize = 84.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace, maxLines = 1,
                style = TextStyle(color = Neon, shadow = Shadow(Neon.copy(alpha = 0.6f), blurRadius = 28f))
            )
            Text(
                unitLabel, fontSize = 28.sp, color = Color.White, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { vm.flipLabelOnly() }.padding(8.dp)   // tap = correct the label only
            )
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(if (stable) Neon.copy(alpha = 0.2f) else Color.Transparent)
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) { Text(if (stable) "●  ${t.stable}" else " ", color = Neon, fontWeight = FontWeight.Bold) }
            if (vm.tareKg != 0.0) Text("${t.tare}: ${String.format(Locale.US, "%.3f", vm.tareKg)}", color = Amber, fontSize = 14.sp)
        }

        // buttons (also press the scale's own keys)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BigButton(if (vm.tareKg != 0.0) "${t.tare} ✕" else t.tare, Amber, Modifier.weight(1f)) { vm.doTare() }
            BigButton("${t.unit}: $unitLabel", Color(0xFF40C4FF), Modifier.weight(1f)) { vm.toggleUnit() }
        }

        // re-send the Mode key if the scale's display does not match the phone
        Text(
            "↻ sync scale", color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().clickable { vm.reader.send("M") }.padding(4.dp),
            textAlign = TextAlign.Center
        )

        // license footer
        (vm.status as? License.Status.Valid)?.let {
            val warn = it.daysLeft <= 30
            Text(
                "${it.info.client}  •  ${t.validTill} ${java.time.LocalDate.ofEpochDay(it.info.expiryDay)}  (${it.daysLeft} ${t.days})",
                color = if (warn) Amber else Color.White.copy(alpha = 0.5f), fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
            )
        }
    }

    if (showPicker) {
        val devices = remember { vm.bonded() }
        AlertDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {},
            title = { Text(t.selectScale) },
            text = {
                if (devices.isEmpty()) Text(t.noScale) else Column {
                    devices.forEach { d ->
                        @SuppressLint("MissingPermission")
                        val name = try { d.name } catch (e: SecurityException) { null } ?: d.address
                        Text(
                            name,
                            modifier = Modifier.fillMaxWidth().clickable { vm.selectDevice(d); showPicker = false }
                                .padding(vertical = 12.dp),
                            fontSize = 18.sp
                        )
                    }
                }
            }
        )
    }

    if (showDiag) {
        val log by vm.reader.log.collectAsState()
        AlertDialog(
            onDismissRequest = { showDiag = false },
            confirmButton = { TextButton(onClick = { showDiag = false }) { Text(t.close) } },
            title = { Text("Diagnostics") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Command ending (pick the one that makes the scale react):", fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("NONE", "CR", "LF", "CRLF").forEach { k -> Chip(k, vm.endKey == k) { vm.setEnd(k) } }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("SEND T (tare)", false) { vm.reader.send("T") }
                        Chip("SEND M (mode)", false) { vm.reader.send("M") }
                    }
                    Chip("RUN COMMAND TEST (20 s)", false) { vm.sweepCommands() }
                    Text("Put about 0.5 kg on the scale and keep it still. Tap the test, watch the scale for 20 s. The RESULT line appears below.", fontSize = 11.sp)
                    Text("Scale unit seen in data: ${when (unitFromScale) { true -> "LITRE"; false -> "KG"; null -> "no marker" }}", fontSize = 12.sp)
                    log.lastOrNull { it.startsWith("#### RESULT") }?.let {
                        Text(it, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Text("Raw data (< from scale, > sent), newest last:", fontSize = 12.sp)
                    log.takeLast(70).forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                }
            }
        )
    }

    if (showExpiry && valid != null) {
        AlertDialog(
            onDismissRequest = { showExpiry = false },
            title = { Text(t.expiryTitle) },
            text = { Text(expiryMessage(vm.lang, valid.daysLeft), fontSize = 18.sp) },
            confirmButton = {
                TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$SUPPORT_PHONE"))) }) {
                    Text(t.callSupplier)
                }
            },
            dismissButton = { TextButton(onClick = { showExpiry = false }) { Text(t.close) } }
        )
    }

    if (showAbout) {
        val st = vm.status as? License.Status.Valid
        val ver = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (e: Exception) { "1.0" }
        AlertDialog(
            onDismissRequest = { showAbout = false },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text(t.close) } },
            title = { Text(t.about) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${t.deviceId}: ${vm.deviceId}", fontFamily = FontFamily.Monospace)
                    st?.let {
                        Text(it.info.client)
                        Text("${t.validTill} ${java.time.LocalDate.ofEpochDay(it.info.expiryDay)}")
                    }
                    Text("Key ID: ${License.keyId()}   v$ver", fontSize = 12.sp)
                    Text(
                        if (vm.tamilVoiceOk()) "Tamil voice: ready" else "Tamil voice not installed (Settings > Text-to-speech)",
                        fontSize = 12.sp
                    )
                    TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$SUPPORT_PHONE"))) }) {
                        Text(t.callSupplier)
                    }
                }
            }
        )
    }
}

// ---------- activation / expired / blocked screen (scan only) ----------
@Composable
fun ActivationScreen(vm: MainVM) {
    val t = vm.t
    val ctx = LocalContext.current
    val scan = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { vm.activate(it) } }
    val qr = remember { qrBitmap(vm.deviceId).asImageBitmap() }
    val msg: String? = when (vm.status) {
        is License.Status.Expired -> "${t.expired}\n${t.contact}"
        is License.Status.WrongDevice -> t.wrongDevice
        is License.Status.Tampered -> t.tampered
        is License.Status.Invalid -> t.invalid
        is License.Status.Blocked -> t.blocked
        else -> null
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { LangToggle(vm) }
        Text(t.appName, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
        msg?.let { Text(it, color = Danger, textAlign = TextAlign.Center, fontSize = 18.sp) }
        if (vm.status is License.Status.Invalid)
            Text(License.reason, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, textAlign = TextAlign.Center)

        Text(t.step1, color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center)
        Image(qr, null, Modifier.size(220.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(8.dp))
        Text(vm.deviceId, color = Color.White.copy(alpha = 0.6f), fontFamily = FontFamily.Monospace, fontSize = 14.sp)

        Spacer(Modifier.height(6.dp))
        Text(t.step2, color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center)
        BigButton(t.scan, Neon, Modifier.fillMaxWidth().height(80.dp)) {
            scan.launch(ScanOptions().setBeepEnabled(false).setOrientationLocked(false))
        }
        if (msg != null) BigButton(t.callSupplier, Amber, Modifier.fillMaxWidth()) {
            ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$SUPPORT_PHONE")))
        }
        Text("Key ID: ${License.keyId()}", color = Color.White.copy(alpha = 0.35f), fontSize = 12.sp)
    }
}
