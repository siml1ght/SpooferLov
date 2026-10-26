package com.kartonchik.lvs

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.os.*
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.util.*
import kotlin.math.sin

private val CFG: UUID = UUID.fromString("7a1c0001-5b0d-4e6a-9d1a-0c0ffee00001")
private val CHR: UUID = UUID.fromString("7a1c0002-5b0d-4e6a-9d1a-0c0ffee00001")
private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

class ToyView(c: Context) : View(c) {
    @Volatile var level = 0
    val hist = ArrayList<Pair<Long, Int>>()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val t = SystemClock.uptimeMillis()
        cv.drawColor(0xFF14171D.toInt())
        val ch = h * 0.62f
        val r = ch * 0.3f + level * ch * 0.006f * sin(t / (60.0 - level * 2)).toFloat()
        p.shader = RadialGradient(w / 2, ch / 2, r.coerceAtLeast(1f), 0xFFFF8FB8.toInt(),
            if (level > 0) 0xFFFF4D8D.toInt() else 0xFF4A3A44.toInt(), Shader.TileMode.CLAMP)
        cv.drawCircle(w / 2, ch / 2, r, p); p.shader = null
        // last 60 s step chart
        p.color = 0xFFFF4D8D.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = 4f
        val top = ch + 8f; val bot = h - 8f; val span = 60000f
        val path = Path(); var first = true; var py = 0f
        synchronized(hist) {
            hist.removeAll { t - it.first > span + 5000 }
            val pts = hist + Pair(t, level)
            for (pt in pts) {
                val x = w - (t - pt.first) / span * w
                val y = bot - pt.second / 20f * (bot - top)
                if (first) { path.moveTo(x, y); first = false } else { path.lineTo(x, py); path.lineTo(x, y) }
                py = y
            }
        }
        cv.drawPath(path, p); p.style = Paint.Style.FILL
        postInvalidateOnAnimation()
    }
}

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var chr: BluetoothGattCharacteristic? = null
    private var scanner: BluetoothLeScanner? = null
    private val ops = ArrayDeque<() -> Unit>()
    private var busy = false
    private var connected = false
    private var maxL = 0; private var workMs = 0L

    private lateinit var status: TextView; private lateinit var lvl: TextView; private lateinit var logTv: TextView
    private lateinit var toy: ToyView; private lateinit var batBar: ProgressBar; private lateinit var batTxt: TextView
    private lateinit var name: EditText; private lateinit var letter: EditText; private lateinit var fw: EditText; private lateinit var mac: EditText
    private lateinit var batSeek: SeekBar; private lateinit var rateSeek: SeekBar
    private lateinit var mirror: CheckBox; private lateinit var connBtn: Button
    private val logLines = ArrayDeque<String>()

    // ---------- UI ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun tv(t: String, size: Float = 14f, color: Int = 0xFFE8EAF0.toInt()) = TextView(this).apply { text = t; textSize = size; setTextColor(color) }
    private fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }
    private fun edit(hint: String) = EditText(this).apply { this.hint = hint; setTextColor(0xFFE8EAF0.toInt()); setHintTextColor(0xFF8B93A7.toInt()); setSingleLine() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(24)) }
        val sv = ScrollView(this).apply { setBackgroundColor(0xFF111318.toInt()); addView(root) }
        setContentView(sv)
        fun add(v: View) = root.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        add(tv("🎛 LVS Control", 22f))
        status = tv("не подключено", 14f, 0xFFFF6B6B.toInt()); add(status)
        connBtn = btn("Найти и подключиться") { if (connected) disconnect() else startScan() }; add(connBtn)

        toy = ToyView(this)
        root.addView(toy, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240)))
        lvl = tv("Вибрация: 0/20   макс 0   работа 0 с"); add(lvl)
        batTxt = tv("Батарея: —%"); add(batTxt)
        batBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }; add(batBar)
        mirror = CheckBox(this).apply { text = "Зеркалить вибрацию на этом телефоне"; setTextColor(0xFFE8EAF0.toInt()) }; add(mirror)

        add(tv("\nМодель и имя", 16f, 0xFFFF4D8D.toInt()))
        val presets = listOf("— пресет (догадки) —", "Lush (S, 11)", "Lush 2? (S, 20)", "Lush 3? (S, 30)", "Lush 4? (S, 40)", "Hush (Z, 11)")
        val vals = listOf("", "S|11|LVS-S001", "S|20|LVS-S002", "S|30|LVS-S003", "S|40|LVS-S004", "Z|11|LVS-Z001")
        val sp = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, presets)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val s = vals[pos].split("|"); if (s.size == 3) { letter.setText(s[0]); fw.setText(s[1]); name.setText(s[2]) }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        add(sp)
        name = edit("Имя (LVS-S003)"); letter = edit("Буква модели"); fw = edit("Версия"); mac = edit("MAC (12 hex)")
        listOf(name, letter, fw, mac).forEach { add(it) }
        add(btn("Применить (ESP32 перезагрузится)") {
            send("type=${letter.text}:${fw.text}:${mac.text.toString().uppercase()};")
            send("name=${name.text}"); log("Имя/тип отправлены")
        })

        add(tv("\nБатарея и приколы", 16f, 0xFFFF4D8D.toInt()))
        val bl = tv("Заряд: 80%"); add(bl)
        batSeek = SeekBar(this).apply { max = 99; progress = 79
            setOnSeekBarChangeListener(sb { bl.text = "Заряд: ${it + 1}%" }) }; add(batSeek)
        val rl = tv("Скорость разряда: ×1"); add(rl)
        rateSeek = SeekBar(this).apply { max = 99; progress = 0
            setOnSeekBarChangeListener(sb { rl.text = "Скорость разряда: ×${it + 1}" }) }; add(rateSeek)
        add(btn("Применить заряд и скорость") { send("bat=${batSeek.progress + 1}"); send("rate=${rateSeek.progress + 1}"); log("Заряд/скорость применены") })
        add(btn("Выключить «игрушку» (включить: BOOT 2 с)") { send("off") })
        add(btn("Сброс настроек ESP32") { send("reset") })
        add(btn("Поделиться логом") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, logLines.joinToString("\n")) }, "Лог"))
        })
        add(tv("\nЛог команд", 16f, 0xFFFF4D8D.toInt()))
        logTv = tv("", 11f, 0xFFB8BDCC.toInt()).apply { typeface = Typeface.MONOSPACE }; add(logTv)

        requestPerms()
        tick()
    }

    private fun sb(f: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) = f(p)
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    private fun log(m: String) = ui.post {
        logLines.add(String.format("%tT %s", Date(), m)); while (logLines.size > 200) logLines.poll()
        logTv.text = logLines.joinToString("\n")
    }

    private var lastTick = SystemClock.uptimeMillis()
    private fun tick() {
        val now = SystemClock.uptimeMillis(); val dt = now - lastTick; lastTick = now
        if (toy.level > 0) workMs += dt
        lvl.text = "Вибрация: ${toy.level}/20   макс $maxL   работа ${workMs / 1000} с"
        if (mirror.isChecked && toy.level > 0) {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot((toy.level / 20f * 180).toLong().coerceAtLeast(10), VibrationEffect.DEFAULT_AMPLITUDE))
        }
        ui.postDelayed({ tick() }, 200)
    }

    // ---------- permissions / scan ----------
    private fun requestPerms() {
        val need = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (need.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) requestPermissions(need, 1)
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            val n = r.scanRecord?.deviceName ?: return
            if (!n.startsWith("LVS-")) return
            scanner?.stopScan(this)
            log("Найдено $n ${r.device.address}")
            ui.post { status.text = "подключаюсь к $n…" }
            gatt = r.device.connectGatt(this@MainActivity, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        }
    }

    private fun startScan() {
        val bt = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (bt == null || !bt.isEnabled) { Toast.makeText(this, "Включите Bluetooth", Toast.LENGTH_SHORT).show(); return }
        scanner = bt.bluetoothLeScanner
        status.text = "поиск LVS-…"; status.setTextColor(0xFFE8C95A.toInt())
        scanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
        ui.postDelayed({ if (!connected && gatt == null) { scanner?.stopScan(scanCb); status.text = "не найдено"; status.setTextColor(0xFFFF6B6B.toInt()) } }, 12000)
    }

    private fun disconnect() { gatt?.disconnect() }

    // ---------- GATT op queue ----------
    @Synchronized private fun enqueue(op: () -> Unit) { ops.add(op); if (!busy) next() }
    @Synchronized private fun next() { val op = ops.poll(); if (op == null) { busy = false; return }; busy = true; op() }
    @Suppress("DEPRECATION")
    private fun send(t: String) {
        enqueue {
            val c = chr; val g = gatt
            if (c == null || g == null) { next() } else { c.value = t.toByteArray(); c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; g.writeCharacteristic(c) }
        }
    }

    @Suppress("DEPRECATION")
    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, ns: Int) {
            if (ns == BluetoothProfile.STATE_CONNECTED) { g.requestMtu(185) }
            else {
                connected = false; chr = null; g.close(); gatt = null
                synchronized(this@MainActivity) { ops.clear(); busy = false }
                ui.post { status.text = "не подключено"; status.setTextColor(0xFFFF6B6B.toInt()); connBtn.text = "Найти и подключиться"; toy.level = 0 }
                log("Отключено")
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, s: Int) { g.discoverServices() }
        override fun onServicesDiscovered(g: BluetoothGatt, s: Int) {
            val c = g.getService(CFG)?.getCharacteristic(CHR)
            if (c == null) { log("Нет сервиса настройки (прошит firmware_v3?)"); g.disconnect(); return }
            chr = c; connected = true
            ui.post { status.text = "подключено"; status.setTextColor(0xFF3DDC97.toInt()); connBtn.text = "Отключиться" }
            enqueue { g.setCharacteristicNotification(c, true)
                val d = c.getDescriptor(CCCD); d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
            send("get")
            enqueue { g.readCharacteristic(c) }
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, s: Int) { next() }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, s: Int) { next() }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, s: Int) {
            parseInfo(String(c.value ?: ByteArray(0))); next()
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val s = String(c.value ?: return)
            if (s.startsWith("S:")) {
                val m = s.substring(2).split(",").associate { it[0] to it.substring(1).toIntOrNull() }
                val v = m['v'] ?: 0
                if (v != toy.level) { synchronized(toy.hist) { toy.hist.add(Pair(SystemClock.uptimeMillis(), v)) }; toy.level = v; if (v > maxL) maxL = v }
                ui.post {
                    m['b']?.let { batBar.progress = it; batTxt.text = "Батарея: $it%" }
                    if (m['p'] == 0) { status.text = "игрушка выключена"; status.setTextColor(0xFFFF6B6B.toInt()) }
                }
            } else if (s.startsWith("E:")) log("← ${s.substring(2)};")
        }
    }

    private fun parseInfo(s: String) {
        val o = s.split("|").mapNotNull { val i = it.indexOf('='); if (i > 0) it.substring(0, i) to it.substring(i + 1) else null }.toMap()
        ui.post {
            name.setText(o["name"] ?: "")
            val t = (o["type"] ?: "").replace(";", "").split(":")
            letter.setText(t.getOrElse(0) { "" }); fw.setText(t.getOrElse(1) { "" }); mac.setText(t.getOrElse(2) { o["mac"] ?: "" })
            o["bat"]?.toIntOrNull()?.let { batSeek.progress = (it - 1).coerceIn(0, 99); batBar.progress = it; batTxt.text = "Батарея: $it%" }
            o["rate"]?.toFloatOrNull()?.let { rateSeek.progress = (it.toInt() - 1).coerceIn(0, 99) }
        }
        log("Инфо: $s")
    }

    override fun onDestroy() { gatt?.close(); super.onDestroy() }
}
