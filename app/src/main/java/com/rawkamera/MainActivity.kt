package com.rawkamera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

class MainActivity : Activity(), CameraController.Listener {

    private enum class Panel { ISO, SHUTTER, FLASH, SETTINGS }

    private data class Shutter(val label: String, val ns: Long)

    private lateinit var root: View
    private lateinit var previewBox: View
    private lateinit var texture: TextureView
    private lateinit var guides: GuideOverlay
    private lateinit var focusSquare: View
    private lateinit var afLabel: TextView
    private lateinit var countdown: TextView
    private lateinit var histogram: HistogramView
    private lateinit var isoLabel: TextView
    private lateinit var isoValue: TextView
    private lateinit var shLabel: TextView
    private lateinit var shValue: TextView
    private lateinit var evLabel: TextView
    private lateinit var evValue: TextView
    private lateinit var flLabel: TextView
    private lateinit var flValue: TextView
    private lateinit var settingsBtn: ImageButton
    private lateinit var message: TextView
    private lateinit var menuPanel: View
    private lateinit var menuLabel: TextView
    private lateinit var menuValue: TextView
    private lateinit var ruler: RulerView
    private lateinit var settingsPanel: View
    private lateinit var guideRow: FlowLayout
    private lateinit var flashRow: FlowLayout
    private lateinit var timerRow: FlowLayout
    private lateinit var info: TextView
    private lateinit var shutter: View
    private lateinit var shutterBar: View

    private lateinit var cam: CameraController
    private lateinit var sfx: SoundFx
    private val ui = Handler(Looper.getMainLooper())

    // Pozlama
    private var isoValues: List<Int> = emptyList()
    private var shutterValues: List<Shutter> = emptyList()
    private var isoIdx = 0
    private var shIdx = 0

    // Flaş
    private var flashOn = false
    private var flashTorch = false
    private var flashIdx = 4

    // Kılavuz ve zamanlayıcı
    private var guideMode = GuideOverlay.THIRDS
    private var spiralFlip = 0
    private var timerSec = 0

    private var menu: Panel? = null
    private var started = false
    private var askedPermission = false
    private var orientationListener: OrientationEventListener? = null

    // Ses tuşları
    private val keysDown = HashSet<Int>()
    private var keyCombo = false
    private var keyLongFired = false

    // Çekim durumu
    private var bursting = false
    private var burstCount = 0
    private var counting = false
    private var countdownLeft = 0
    private var pressConsumed = false

    private val closeMenuRunnable = Runnable { closeMenu() }
    private val clearInfoRunnable = Runnable { if (!bursting && !counting) info.text = "" }
    private val meterRunnable = object : Runnable {
        override fun run() {
            meter()
            ui.postDelayed(this, 250)
        }
    }
    private val keyLongRunnable = Runnable { onVolumeHeld() }
    private val burstStartRunnable = Runnable { startBurst() }
    private val burstLoop = object : Runnable {
        override fun run() {
            if (!bursting) return
            // Seri çekimde patlama flaşı kullanılmaz; sürekli flaş açıksa yanmaya devam eder.
            if (cam.capture(withFlash = false)) {
                burstCount++
                sfx.shutter()
                showCounter()
            }
            ui.postDelayed(this, 50)
        }
    }
    private val countdownTick = object : Runnable {
        override fun run() {
            if (!counting) return
            if (countdownLeft <= 0) {
                endCountdown()
                shoot()
                return
            }
            countdown.text = countdownLeft.toString()
            if (countdownLeft <= 3) sfx.beep()
            countdownLeft--
            ui.postDelayed(this, 1000)
        }
    }
    private val focusReleaseRunnable = Runnable {
        cam.resetFocus()
        focusSquare.animate().alpha(0f).setDuration(300).withEndAction {
            focusSquare.visibility = View.INVISIBLE
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = findViewById(R.id.root)
        previewBox = findViewById(R.id.previewBox)
        texture = findViewById(R.id.preview)
        guides = findViewById(R.id.guides)
        focusSquare = findViewById(R.id.focusSquare)
        afLabel = findViewById(R.id.afLabel)
        countdown = findViewById(R.id.countdown)
        histogram = findViewById(R.id.histogram)
        isoLabel = findViewById(R.id.isoLabel)
        isoValue = findViewById(R.id.isoValue)
        shLabel = findViewById(R.id.shLabel)
        shValue = findViewById(R.id.shValue)
        evLabel = findViewById(R.id.evLabel)
        evValue = findViewById(R.id.evValue)
        flLabel = findViewById(R.id.flLabel)
        flValue = findViewById(R.id.flValue)
        settingsBtn = findViewById(R.id.settingsBtn)
        message = findViewById(R.id.message)
        menuPanel = findViewById(R.id.menuPanel)
        menuLabel = findViewById(R.id.menuLabel)
        menuValue = findViewById(R.id.menuValue)
        ruler = findViewById(R.id.ruler)
        settingsPanel = findViewById(R.id.settingsPanel)
        guideRow = findViewById(R.id.guideRow)
        flashRow = findViewById(R.id.flashRow)
        timerRow = findViewById(R.id.timerRow)
        info = findViewById(R.id.info)
        shutter = findViewById(R.id.shutter)
        shutterBar = findViewById(R.id.shutterBar)

        cam = CameraController(this, texture, this)
        sfx = SoundFx(this)

        loadSettings()
        buildSettings()
        guides.mode = guideMode
        guides.flip = spiralFlip

        findViewById<View>(R.id.isoBox).setOnClickListener { togglePanel(Panel.ISO) }
        findViewById<View>(R.id.shBox).setOnClickListener { togglePanel(Panel.SHUTTER) }
        findViewById<View>(R.id.flBox).setOnClickListener { flashCombo() }
        settingsBtn.setOnClickListener { togglePanel(Panel.SETTINGS) }
        ruler.onStep = { d -> step(d) }
        setupShutter()
        setupFocusGestures()

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
        stopBurst()
        cancelCountdown()
        ui.removeCallbacks(focusReleaseRunnable)
        focusSquare.visibility = View.INVISIBLE
        afLabel.visibility = View.GONE
        closeMenu(silent = true)
        if (started) {
            savePrefs()
            cam.stop()
            started = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sfx.release()
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
        clampFlashIdx()
        started = true
        refresh()
        if (texture.isAvailable) startPreview()
        ui.removeCallbacks(meterRunnable)
        ui.post(meterRunnable)
    }

    private fun startPreview() {
        applyFlash()
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

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val savedIso = prefs.getInt("iso", 400)
        val savedNs = prefs.getLong("ns", 8_000_000L)
        isoIdx = isoValues.indices.minByOrNull { abs(isoValues[it] - savedIso) } ?: 0
        shIdx = shutterValues.indices.minByOrNull { abs(shutterValues[it].ns - savedNs) } ?: 0
    }

    private fun loadSettings() {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        guideMode = p.getInt("guide", GuideOverlay.THIRDS).coerceIn(0, GUIDE_NAMES.lastIndex)
        spiralFlip = p.getInt("spiral", 0).coerceIn(0, 3)
        timerSec = p.getInt("timer", 0).takeIf { it in TIMER_OPTS } ?: 0
        flashTorch = p.getBoolean("torch", false)
        flashIdx = p.getInt("flashIdx", 4)
    }

    private fun savePrefs() {
        val e = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt("guide", guideMode)
            .putInt("spiral", spiralFlip)
            .putInt("timer", timerSec)
            .putBoolean("torch", flashTorch)
            .putInt("flashIdx", flashIdx)
        if (isoValues.isNotEmpty()) {
            e.putInt("iso", isoValues[isoIdx]).putLong("ns", shutterValues[shIdx].ns)
        }
        e.apply()
    }

    // ---------- Deklanşör: dokun = tek kare (zamanlayıcıyla), basılı tut = seri ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupShutter() {
        shutter.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (counting) {
                        cancelCountdown()
                        pressConsumed = true
                    } else {
                        pressConsumed = false
                        shutterBar.alpha = 0.7f
                        ui.postDelayed(burstStartRunnable, BURST_HOLD_MS)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    ui.removeCallbacks(burstStartRunnable)
                    if (bursting) {
                        stopBurst()
                    } else if (!pressConsumed) {
                        shutterBar.alpha = 1f
                        singlePress()
                    }
                    v.performClick()
                }
                MotionEvent.ACTION_CANCEL -> {
                    ui.removeCallbacks(burstStartRunnable)
                    stopBurst()
                    if (!counting) shutterBar.alpha = 1f
                }
            }
            true
        }
    }

    private fun singlePress() {
        if (!started) return
        if (timerSec > 0) startCountdown() else shoot()
    }

    private fun shoot() {
        if (!started) return
        if (cam.capture(withFlash = true)) {
            sfx.shutter()
            shutterBar.alpha = 0.35f
            shutterBar.animate().alpha(1f).setDuration(220).start()
        }
    }

    private fun startBurst() {
        if (!started || counting) return
        bursting = true
        burstCount = 0
        showCounter()
        ui.post(burstLoop)
    }

    private fun stopBurst() {
        if (!bursting) return
        bursting = false
        ui.removeCallbacks(burstLoop)
        shutterBar.alpha = 1f
        ui.removeCallbacks(clearInfoRunnable)
        ui.postDelayed(clearInfoRunnable, 1500)
    }

    private fun showCounter() {
        info.setTextColor(Color.WHITE)
        info.textSize = 12f
        info.text = burstCount.toString()
    }

    private fun startCountdown() {
        counting = true
        countdownLeft = timerSec
        shutterBar.alpha = 0.4f
        info.setTextColor(GREY)
        info.textSize = 9f
        info.text = "ZAMANLAYICI · $timerSec SN"
        countdown.visibility = View.VISIBLE
        ui.post(countdownTick)
    }

    private fun endCountdown() {
        counting = false
        ui.removeCallbacks(countdownTick)
        countdown.visibility = View.GONE
        shutterBar.alpha = 1f
        info.text = ""
    }

    private fun cancelCountdown() {
        if (!counting) return
        endCountdown()
    }

    override fun onCameraError(message: String) {
        showStatus(message)
    }

    override fun onFlashStrengthUnsupported() {
        if (menu == Panel.FLASH) closeMenu()
        refresh()
        showStatus("FLAŞ GÜCÜ BU TELEFONDA AYARLANAMIYOR")
    }

    override fun onSaved(name: String) {
        if (!bursting) showStatus("KAYDEDİLDİ  ·  DCIM/RawKamera")
    }

    // ---------- Dokunarak odak: dokun = odakla, basılı tut = kilitle ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFocusGestures() {
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                focusAt(e.x, e.y, lock = false)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                focusAt(e.x, e.y, lock = true)
            }
        })
        previewBox.setOnTouchListener { v, e ->
            gd.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            true
        }
    }

    private fun focusAt(x: Float, y: Float, lock: Boolean) {
        if (!started) return
        if (!cam.canFocus) {
            showStatus("BU KAMERADA ODAK AYARLANAMIYOR")
            return
        }
        val w = previewBox.width.toFloat()
        val h = previewBox.height.toFloat()
        if (w <= 0f || h <= 0f) return
        cam.focusAt((x / w).coerceIn(0f, 1f), (y / h).coerceIn(0f, 1f))

        val s = dp(64).toFloat()
        val left = (x - s / 2f).coerceIn(0f, w - s)
        val top = (y - s / 2f).coerceIn(0f, h - s)
        focusSquare.animate().cancel()
        focusSquare.translationX = left
        focusSquare.translationY = top
        focusSquare.setBackgroundResource(if (lock) R.drawable.focus_box_locked else R.drawable.focus_box)
        focusSquare.alpha = 1f
        focusSquare.visibility = View.VISIBLE

        if (lock) {
            afLabel.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            afLabel.translationX = left + (s - afLabel.measuredWidth) / 2f
            afLabel.translationY = (top - afLabel.measuredHeight - dp(4)).coerceAtLeast(0f)
            afLabel.visibility = View.VISIBLE
            root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        } else {
            afLabel.visibility = View.GONE
        }

        ui.removeCallbacks(focusReleaseRunnable)
        if (!lock) ui.postDelayed(focusReleaseRunnable, FOCUS_RELEASE_MS)
    }

    // ---------- Flaş ----------

    /** Flaşın o anki modunda kaç güç seviyesi gösterilecek (0 = ayarlanamıyor). */
    private fun flashLevels(): Int {
        if (cam.strengthBroken) return 0
        val max = cam.torchMaxLevel
        return if (max > 1) minOf(max, 10) else 0
    }

    private fun clampFlashIdx() {
        val n = flashLevels()
        if (n > 0) flashIdx = flashIdx.coerceIn(0, n - 1)
    }

    /** Seçili seviyeyi telefonun kendi güç ölçeğine çevirir. */
    private fun halStrength(): Int {
        val n = flashLevels()
        if (n == 0) return 0
        val max = cam.torchMaxLevel
        return ((flashIdx + 1) * max / n.toFloat()).roundToInt().coerceIn(1, max)
    }

    private fun applyFlash() {
        val mode = when {
            !flashOn -> CameraController.Flash.OFF
            flashTorch -> CameraController.Flash.TORCH
            else -> CameraController.Flash.SINGLE
        }
        cam.setFlash(mode, halStrength())
    }

    /**
     * İki ses tuşuna birlikte uzun basış:
     * flaş kapalıyken açar ve güç menüsünü gösterir; güç menüsü açıkken menüyü kapatır
     * (flaş açık kalır); flaş açık ve menü kapalıyken flaşı kapatır.
     */
    private fun flashCombo() {
        if (!started) return
        if (!cam.hasFlash) {
            showStatus("BU KAMERADA FLAŞ YOK")
            return
        }
        when {
            menu == Panel.FLASH -> closeMenu()
            flashOn && menu == null -> {
                flashOn = false
                applyFlash()
                refresh()
            }
            else -> {
                flashOn = true
                applyFlash()
                if (flashLevels() > 0) {
                    openPanel(Panel.FLASH)
                } else {
                    closeMenu()
                    refresh()
                    showStatus("FLAŞ GÜCÜ BU TELEFONDA AYARLANAMIYOR")
                }
            }
        }
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

    // ---------- Menüler ----------

    private fun togglePanel(p: Panel) {
        if (!started) return
        if (menu == p) closeMenu() else openPanel(p)
    }

    private fun openPanel(p: Panel) {
        menu = p
        menuPanel.visibility = if (p == Panel.SETTINGS) View.GONE else View.VISIBLE
        settingsPanel.visibility = if (p == Panel.SETTINGS) View.VISIBLE else View.GONE
        sfx.menuOpen()
        refresh()
        refreshSettings()
        scheduleClose()
    }

    private fun closeMenu(silent: Boolean = false) {
        ui.removeCallbacks(closeMenuRunnable)
        if (menu == null) return
        menu = null
        menuPanel.visibility = View.GONE
        settingsPanel.visibility = View.GONE
        if (!silent) sfx.menuClose()
        refresh()
        savePrefs()
    }

    private fun scheduleClose() {
        ui.removeCallbacks(closeMenuRunnable)
        ui.postDelayed(closeMenuRunnable, MENU_CLOSE_MS)
    }

    private fun step(delta: Int) {
        val m = menu ?: return
        val changed = when (m) {
            Panel.ISO -> {
                val n = (isoIdx + delta).coerceIn(0, isoValues.lastIndex)
                (n != isoIdx).also { isoIdx = n }
            }
            Panel.SHUTTER -> {
                val n = (shIdx + delta).coerceIn(0, shutterValues.lastIndex)
                (n != shIdx).also { shIdx = n }
            }
            Panel.FLASH -> {
                val levels = flashLevels()
                if (levels == 0) {
                    false
                } else {
                    val n = (flashIdx + delta).coerceIn(0, levels - 1)
                    (n != flashIdx).also { flashIdx = n }
                }
            }
            Panel.SETTINGS -> false
        }
        if (changed) {
            if (m == Panel.FLASH) applyFlash() else cam.setExposure(isoValues[isoIdx], shutterValues[shIdx].ns)
            root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            if (delta > 0) sfx.tickUp() else sfx.tickDown()
            refresh()
        }
        scheduleClose()
    }

    private fun refresh() {
        if (isoValues.isEmpty()) return
        val iso = isoValues[isoIdx].toString()
        val sh = shutterValues[shIdx].label
        val levels = flashLevels()
        val fl = when {
            !flashOn -> "—"
            levels > 0 -> (flashIdx + 1).toString()
            else -> "AÇIK"
        }
        isoValue.text = iso
        shValue.text = sh
        flLabel.text = if (flashTorch) "FENER" else "FLŞ"
        flValue.text = fl

        val m = menu
        styleItem(isoLabel, isoValue, m, Panel.ISO)
        styleItem(shLabel, shValue, m, Panel.SHUTTER)
        styleItem(flLabel, flValue, m, Panel.FLASH)
        evValue.setTextColor(if (m != null) GREY else Color.WHITE)
        if (!flashOn && m == null) flValue.setTextColor(GREY)
        settingsBtn.setBackgroundResource(if (m == Panel.SETTINGS) R.drawable.underline else android.R.color.transparent)

        when (m) {
            Panel.ISO -> {
                menuLabel.text = "ISO"
                menuValue.text = iso
                ruler.labels = isoValues.map { it.toString() }
                ruler.index = isoIdx
            }
            Panel.SHUTTER -> {
                menuLabel.text = "ENSTANTANE"
                menuValue.text = sh
                ruler.labels = shutterValues.map { it.label }
                ruler.index = shIdx
            }
            Panel.FLASH -> {
                menuLabel.text = if (flashTorch) "FENER GÜCÜ" else "FLAŞ GÜCÜ"
                menuValue.text = fl
                ruler.labels = (1..levels).map { it.toString() }
                ruler.index = flashIdx
            }
            else -> {}
        }
        ruler.invalidate()
    }

    private fun styleItem(label: TextView, value: TextView, open: Panel?, mine: Panel) {
        val active = open == mine
        val dimmed = open != null && !active
        label.setTextColor(if (active) Color.WHITE else GREY)
        value.setTextColor(if (dimmed) GREY else Color.WHITE)
        value.paintFlags = if (active) value.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        else value.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
    }

    // ---------- Ayar menüsü ----------

    private fun buildSettings() {
        GUIDE_NAMES.forEachIndexed { i, name ->
            guideRow.addView(optionView(name) {
                // Seçili spirale tekrar dokunmak spirali bir sonraki köşeye çevirir.
                if (i == GuideOverlay.SPIRAL && guideMode == GuideOverlay.SPIRAL) {
                    spiralFlip = (spiralFlip + 1) % 4
                    guides.flip = spiralFlip
                } else {
                    guideMode = i
                    guides.mode = i
                }
            })
        }
        FLASH_MODES.forEachIndexed { i, name ->
            flashRow.addView(optionView(name) {
                flashTorch = i == 1
                clampFlashIdx()
                applyFlash()
                refresh()
            })
        }
        TIMER_OPTS.forEach { sec ->
            timerRow.addView(optionView(if (sec == 0) "KAPALI" else "$sec SN") { timerSec = sec })
        }
        refreshSettings()
    }

    private fun optionView(text: String, onPick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 11f
        letterSpacing = 0.06f
        typeface = Typeface.SANS_SERIF
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34))
        setOnClickListener {
            onPick()
            refreshSettings()
            savePrefs()
            scheduleClose()
        }
    }

    private fun refreshSettings() {
        for (i in 0 until guideRow.childCount) styleOption(guideRow.getChildAt(i) as TextView, i == guideMode)
        for (i in 0 until flashRow.childCount) styleOption(flashRow.getChildAt(i) as TextView, (i == 1) == flashTorch)
        for (i in 0 until timerRow.childCount) styleOption(timerRow.getChildAt(i) as TextView, TIMER_OPTS[i] == timerSec)
    }

    private fun styleOption(v: TextView, selected: Boolean) {
        v.setTextColor(if (selected) Color.WHITE else OPTION_GREY)
        v.paintFlags = if (selected) v.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        else v.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
    }

    // ---------- Ses tuşları ----------
    // Ses açma uzun bas: ISO menüsü · Ses kısma uzun bas: enstantane menüsü
    // İkisine birlikte uzun bas: flaş · Aynı uzun basış menüyü kapatır
    // Menü açıkken kısa basış: değeri bir adım değiştirir

    private fun isVolume(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isVolume(keyCode)) return super.onKeyDown(keyCode, event)
        if (event.repeatCount > 0) return true
        keysDown.add(keyCode)
        if (keysDown.size >= 2) keyCombo = true
        if (!keyLongFired) {
            ui.removeCallbacks(keyLongRunnable)
            ui.postDelayed(keyLongRunnable, LONG_PRESS_MS)
        }
        return true
    }

    private fun onVolumeHeld() {
        keyLongFired = true
        when {
            keysDown.size >= 2 -> flashCombo()
            keysDown.size == 1 && !keyCombo -> {
                val target = if (keysDown.first() == KeyEvent.KEYCODE_VOLUME_UP) Panel.ISO else Panel.SHUTTER
                togglePanel(target)
            }
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!isVolume(keyCode)) return super.onKeyUp(keyCode, event)
        keysDown.remove(keyCode)
        if (!keyLongFired && !keyCombo) {
            ui.removeCallbacks(keyLongRunnable)
            if (menu != null && menu != Panel.SETTINGS) {
                step(if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1)
            }
        }
        if (keysDown.isEmpty()) {
            ui.removeCallbacks(keyLongRunnable)
            keyLongFired = false
            keyCombo = false
        }
        return true
    }

    // ---------- Yardımcılar ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun showMessage(text: String) {
        message.text = text
        message.visibility = View.VISIBLE
    }

    private fun showStatus(text: String) {
        if (bursting || counting) return
        info.setTextColor(GREY)
        info.textSize = 10f
        info.text = text
        ui.removeCallbacks(clearInfoRunnable)
        ui.postDelayed(clearInfoRunnable, 1800)
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
        private const val PREFS = "ayarlar"
        private const val MENU_CLOSE_MS = 4000L
        private const val LONG_PRESS_MS = 500L
        private const val BURST_HOLD_MS = 400L
        private const val FOCUS_RELEASE_MS = 3000L
        private val GREY = Color.rgb(154, 154, 154)
        private val OPTION_GREY = Color.rgb(143, 143, 143)

        private val GUIDE_NAMES = listOf("KAPALI", "ÜÇTE BİR", "ALTIN ORAN", "SPİRAL", "ÇAPRAZ", "PERSPEKTİF")
        private val FLASH_MODES = listOf("PATLAMA", "SÜREKLİ")
        private val TIMER_OPTS = listOf(0, 2, 5, 10)

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
