package com.rawkamera

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

class MainActivity : Activity(), CameraController.Listener {

    private enum class Param { ISO, SHUTTER }

    private data class Shutter(val label: String, val ns: Long)

    private lateinit var root: View
    private lateinit var texture: TextureView
    private lateinit var histogram: HistogramView
    private lateinit var isoLabel: TextView
    private lateinit var isoValue: TextView
    private lateinit var shLabel: TextView
    private lateinit var shValue: TextView
    private lateinit var evValue: TextView
    private lateinit var message: TextView
    private lateinit var menuPanel: View
    private lateinit var menuLabel: TextView
    private lateinit var menuValue: TextView
    private lateinit var ruler: RulerView
    private lateinit var status: TextView
    private lateinit var shutterBar: View

    private lateinit var cam: CameraController
    private lateinit var click: ClickSound
    private val ui = Handler(Looper.getMainLooper())

    private var isoValues: List<Int> = emptyList()
    private var shutterValues: List<Shutter> = emptyList()
    private var isoIdx = 0
    private var shIdx = 0
    private var menu: Param? = null

    private var started = false
    private var askedPermission = false
    private var orientationListener: OrientationEventListener? = null

    // Ses tuşları
    private val pressed = HashSet<Int>()
    private val longDone = HashSet<Int>()
    private var combo = false

    private val closeMenuRunnable = Runnable { closeMenu() }
    private val clearStatusRunnable = Runnable { status.text = "" }
    private val meterRunnable = object : Runnable {
        override fun run() {
            meter()
            ui.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = findViewById(R.id.root)
        texture = findViewById(R.id.preview)
        histogram = findViewById(R.id.histogram)
        isoLabel = findViewById(R.id.isoLabel)
        isoValue = findViewById(R.id.isoValue)
        shLabel = findViewById(R.id.shLabel)
        shValue = findViewById(R.id.shValue)
        evValue = findViewById(R.id.evValue)
        message = findViewById(R.id.message)
        menuPanel = findViewById(R.id.menuPanel)
        menuLabel = findViewById(R.id.menuLabel)
        menuValue = findViewById(R.id.menuValue)
        ruler = findViewById(R.id.ruler)
        status = findViewById(R.id.status)
        shutterBar = findViewById(R.id.shutterBar)

        cam = CameraController(this, texture, this)
        click = ClickSound()

        findViewById<View>(R.id.shutter).setOnClickListener { takePhoto() }
        findViewById<View>(R.id.isoBox).setOnClickListener { toggleMenu(Param.ISO) }
        findViewById<View>(R.id.shBox).setOnClickListener { toggleMenu(Param.SHUTTER) }
        ruler.onStep = { d -> step(d) }

        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                if (started && !cam.running) startPreview()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                cam.deviceRotation = ((orientation + 45) / 90 * 90) % 360
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        orientationListener?.enable()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!askedPermission) {
                askedPermission = true
                requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            }
            return
        }
        startCamera()
    }

    override fun onPause() {
        super.onPause()
        orientationListener?.disable()
        ui.removeCallbacks(meterRunnable)
        closeMenu()
        if (started) {
            savePrefs()
            cam.stop()
            started = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        click.release()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAMERA) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            showMessage("Kamera izni verilmedi.\n\nAyarlar › Uygulamalar › RAW Kamera › İzinler bölümünden kamerayı açıp uygulamayı yeniden başlat.")
        }
    }

    // ---------- Kamera ----------

    private fun startCamera() {
        if (started) return
        val reason = cam.prepare()
        if (reason != null) {
            showMessage(reason)
            return
        }
        message.visibility = View.GONE
        if (isoValues.isEmpty()) setupValues()
        started = true
        refresh()
        if (texture.isAvailable) startPreview()
        ui.removeCallbacks(meterRunnable)
        ui.post(meterRunnable)
    }

    private fun startPreview() {
        cam.start(isoValues[isoIdx], shutterValues[shIdx].ns)
    }

    private fun setupValues() {
        val isoR = cam.isoRange
        isoValues = ISO_STEPS.filter { isoR == null || (it >= isoR.lower && it <= isoR.upper) }
        if (isoValues.isEmpty()) isoValues = listOf(isoR?.lower ?: 100)

        val expR = cam.exposureRange
        shutterValues = SHUTTER_STEPS
            .map { (label, sec) -> Shutter(label, (sec * 1_000_000_000L).toLong()) }
            .filter { expR == null || (it.ns >= expR.lower && it.ns <= expR.upper) }
        if (shutterValues.isEmpty()) shutterValues = listOf(Shutter("1/125", 8_000_000L))

        val prefs = getSharedPreferences("ayarlar", MODE_PRIVATE)
        val savedIso = prefs.getInt("iso", 400)
        val savedNs = prefs.getLong("ns", 8_000_000L)
        isoIdx = isoValues.indices.minByOrNull { abs(isoValues[it] - savedIso) } ?: 0
        shIdx = shutterValues.indices.minByOrNull { abs(shutterValues[it].ns - savedNs) } ?: 0
    }

    private fun savePrefs() {
        if (isoValues.isEmpty()) return
        getSharedPreferences("ayarlar", MODE_PRIVATE).edit()
            .putInt("iso", isoValues[isoIdx])
            .putLong("ns", shutterValues[shIdx].ns)
            .apply()
    }

    private fun takePhoto() {
        if (!started) return
        if (cam.capture()) {
            click.play()
            shutterBar.alpha = 0.35f
            shutterBar.animate().alpha(1f).setDuration(220).start()
        }
    }

    override fun onCameraError(message: String) {
        showStatus(message)
    }

    override fun onSaved(name: String) {
        showStatus("KAYDEDİLDİ  ·  DCIM/RawKamera")
    }

    // ---------- Histogram ve EV ölçümü ----------

    private fun meter() {
        if (!started || !texture.isAvailable) return
        val w = 64
        val h = 48
        val bmp = texture.getBitmap(w, h) ?: return
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        val bins = FloatArray(32)
        var sum = 0.0
        for (p in px) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val l = 0.299 * r + 0.587 * g + 0.114 * b
            sum += l
            bins[(l.toInt() * 32 / 256).coerceIn(0, 31)] += 1f
        }
        histogram.setBins(bins)
        // Orta gri (~118) hedefine göre sapma; görüntü gama kodlu olduğu için 2.2 ile ölçeklenir.
        val mean = (sum / px.size).coerceAtLeast(1.0)
        val ev = (2.2 * ln(mean / 118.0) / ln(2.0)).coerceIn(-3.0, 3.0)
        val q = (ev * 3).roundToInt() / 3.0
        evValue.text = formatEv(q)
    }

    private fun formatEv(v: Double): String {
        if (abs(v) < 0.05) return "0.0"
        val s = String.format(Locale.US, "%.1f", abs(v))
        return if (v > 0) "+$s" else "−$s"
    }

    // ---------- Menü ----------

    private fun toggleMenu(p: Param) {
        if (!started) return
        if (menu == p) closeMenu() else openMenu(p)
    }

    private fun openMenu(p: Param) {
        menu = p
        menuPanel.visibility = View.VISIBLE
        refresh()
        scheduleClose()
    }

    private fun closeMenu() {
        ui.removeCallbacks(closeMenuRunnable)
        if (menu == null) return
        menu = null
        menuPanel.visibility = View.GONE
        refresh()
        savePrefs()
    }

    private fun scheduleClose() {
        ui.removeCallbacks(closeMenuRunnable)
        ui.postDelayed(closeMenuRunnable, 4000)
    }

    private fun step(delta: Int) {
        val m = menu ?: return
        val changed = when (m) {
            Param.ISO -> {
                val n = (isoIdx + delta).coerceIn(0, isoValues.lastIndex)
                (n != isoIdx).also { isoIdx = n }
            }
            Param.SHUTTER -> {
                val n = (shIdx + delta).coerceIn(0, shutterValues.lastIndex)
                (n != shIdx).also { shIdx = n }
            }
        }
        if (changed) {
            cam.setExposure(isoValues[isoIdx], shutterValues[shIdx].ns)
            root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            refresh()
        }
        scheduleClose()
    }

    private fun refresh() {
        if (isoValues.isEmpty()) return
        val iso = isoValues[isoIdx].toString()
        val sh = shutterValues[shIdx].label
        isoValue.text = iso
        shValue.text = sh

        styleItem(isoLabel, isoValue, menu, Param.ISO)
        styleItem(shLabel, shValue, menu, Param.SHUTTER)

        when (menu) {
            Param.ISO -> {
                menuLabel.text = "ISO"
                menuValue.text = iso
                ruler.labels = isoValues.map { it.toString() }
                ruler.index = isoIdx
            }
            Param.SHUTTER -> {
                menuLabel.text = "ENSTANTANE"
                menuValue.text = sh
                ruler.labels = shutterValues.map { it.label }
                ruler.index = shIdx
            }
            null -> {}
        }
        ruler.invalidate()
    }

    private fun styleItem(label: TextView, value: TextView, open: Param?, mine: Param) {
        val active = open == mine
        val dimmed = open != null && !active
        label.setTextColor(if (active) Color.WHITE else GREY)
        value.setTextColor(if (dimmed) GREY else Color.WHITE)
        value.paintFlags = if (active) value.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        else value.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
    }

    // ---------- Ses tuşları ----------
    // Ses açma uzun bas: ISO menüsü · Ses kısma uzun bas: enstantane menüsü
    // Aynı tuşa tekrar uzun bas: menüyü kapat · Menü açıkken kısa basış: değeri değiştir
    // İki tuşa birlikte uzun basma ileride bir özellik için boş bırakıldı.

    private fun isVolume(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isVolume(keyCode)) return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) {
            event.startTracking()
            pressed.add(keyCode)
            if (pressed.size >= 2) combo = true
        }
        return true
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (!isVolume(keyCode)) return super.onKeyLongPress(keyCode, event)
        longDone.add(keyCode)
        if (combo || pressed.size >= 2) return true // ileride kullanılacak kombinasyon
        val target = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) Param.ISO else Param.SHUTTER
        toggleMenu(target)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!isVolume(keyCode)) return super.onKeyUp(keyCode, event)
        pressed.remove(keyCode)
        val wasLong = longDone.remove(keyCode)
        if (!wasLong && !combo && !event.isCanceled && menu != null) {
            step(if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1)
        }
        if (pressed.isEmpty()) combo = false
        return true
    }

    // ---------- Yardımcılar ----------

    private fun showMessage(text: String) {
        message.text = text
        message.visibility = View.VISIBLE
    }

    private fun showStatus(text: String) {
        status.text = text
        ui.removeCallbacks(clearStatusRunnable)
        ui.postDelayed(clearStatusRunnable, 1800)
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    companion object {
        private const val REQ_CAMERA = 1
        private val GREY = Color.rgb(154, 154, 154)

        private val ISO_STEPS = listOf(
            50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250,
            1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 25600
        )

        // Yavaştan hızlıya, 1/3 durak aralıklarla.
        private val SHUTTER_STEPS = listOf(
            "1\"" to 1.0, "0.8\"" to 0.8, "0.6\"" to 0.6, "0.5\"" to 0.5, "0.4\"" to 0.4, "0.3\"" to 0.3,
            "1/4" to 1 / 4.0, "1/5" to 1 / 5.0, "1/6" to 1 / 6.0, "1/8" to 1 / 8.0, "1/10" to 1 / 10.0,
            "1/13" to 1 / 13.0, "1/15" to 1 / 15.0, "1/20" to 1 / 20.0, "1/25" to 1 / 25.0, "1/30" to 1 / 30.0,
            "1/40" to 1 / 40.0, "1/50" to 1 / 50.0, "1/60" to 1 / 60.0, "1/80" to 1 / 80.0, "1/100" to 1 / 100.0,
            "1/125" to 1 / 125.0, "1/160" to 1 / 160.0, "1/200" to 1 / 200.0, "1/250" to 1 / 250.0,
            "1/320" to 1 / 320.0, "1/400" to 1 / 400.0, "1/500" to 1 / 500.0, "1/640" to 1 / 640.0,
            "1/800" to 1 / 800.0, "1/1000" to 1 / 1000.0, "1/1250" to 1 / 1250.0, "1/1600" to 1 / 1600.0,
            "1/2000" to 1 / 2000.0, "1/2500" to 1 / 2500.0, "1/3200" to 1 / 3200.0, "1/4000" to 1 / 4000.0,
            "1/5000" to 1 / 5000.0, "1/6400" to 1 / 6400.0, "1/8000" to 1 / 8000.0
        )
    }
}
