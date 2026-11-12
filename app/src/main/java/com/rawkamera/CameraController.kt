package com.rawkamera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Range
import android.view.Surface
import android.view.TextureView
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Camera2 ile tamamen manuel çalışan arka kamera:
 * otomatik pozlama kapalı, ISO ve enstantane uygulamadan gelir, çekim sadece RAW (DNG).
 * Flaş (patlama / sürekli), dokunarak odak ve art arda çekim desteklenir.
 */
class CameraController(
    private val context: Context,
    private val texture: TextureView,
    private val listener: Listener
) {
    /** SINGLE: fenerle patlama (manuel pozlama) · REAL: gerçek flaş (otomatik pozlama) · TORCH: sürekli */
    enum class Flash { OFF, SINGLE, TORCH, REAL }

    interface Listener {
        fun onCameraError(message: String)
        fun onSaved(name: String)
        fun onFlashStrengthUnsupported()
        fun onRealFlashMissed()
    }

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val main = Handler(Looper.getMainLooper())

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var writer: ExecutorService? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null

    // Gerçek flaş için ek JPEG akışı: bazı telefonlar flaşı sadece JPEG istenen çekimde patlatır.
    // Görüntüsü kullanılmaz, gelir gelmez kapatılır.
    private var jpegReader: ImageReader? = null
    private var previewSurface: Surface? = null

    private var cameraId: String? = null
    private var chars: CameraCharacteristics? = null

    @Volatile private var iso = 400
    @Volatile private var exposureNs = 8_000_000L
    @Volatile private var flash = Flash.OFF
    @Volatile private var flashStrength = 0
    @Volatile private var afRegion: MeteringRectangle? = null
    @Volatile var running = false
        private set

    // Patlama flaşı, fenerin kısa süre yakılmasıyla yapılır: bazı telefonlar (Honor dahil)
    // manuel pozlamada tek seferlik flaş komutunu görmezden gelir ama feneri yakar.
    @Volatile private var preFlash = false

    /** Güç ayarı feneri susturuyorsa true olur; o andan sonra güç ayarı gönderilmez. */
    @Volatile var strengthBroken = false
        private set
    private var torchMisses = 0

    // Patlama sırasında fenerin gerçekten yandığını izlemek için (kamera iş parçacığında).
    private var preFlashFrames = 0

    // Gerçek flaş dizisi: otomatik pozlama + ön ölçüm (precapture) + tek patlama.
    @Volatile private var realSeq = false
    private var sawPrecapture = false
    private var aeReady = false
    private var aeStateKnown = false
    private var flashStateKnown = false

    /** Telefonun tutuluş açısı (0, 90, 180, 270), DNG yönü için. */
    @Volatile var deviceRotation = 0

    // Kayıt hattı: aynı anda en fazla 2 kare yazılırken bekleyebilir.
    private val inFlight = AtomicInteger(0)
    private val results = HashMap<Long, Pair<TotalCaptureResult, Int>>()
    private val images = HashMap<Long, Image>()

    val isoRange: Range<Int>?
        get() = chars?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)

    val exposureRange: Range<Long>?
        get() = chars?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)

    val hasFlash: Boolean
        get() = chars?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

    /** Patlama flaşının ayarlanabilir en yüksek gücü (1 = ayarlanamıyor). */
    val singleMaxLevel: Int
        get() = if (Build.VERSION.SDK_INT >= 35) {
            chars?.get(CameraCharacteristics.FLASH_SINGLE_STRENGTH_MAX_LEVEL) ?: 1
        } else 1

    /** Sürekli flaşın (fener) ayarlanabilir en yüksek gücü (1 = ayarlanamıyor). */
    val torchMaxLevel: Int
        get() = if (Build.VERSION.SDK_INT >= 35) {
            chars?.get(CameraCharacteristics.FLASH_TORCH_STRENGTH_MAX_LEVEL) ?: 1
        } else 1

    val canFocus: Boolean
        get() {
            val c = chars ?: return false
            val minDist = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            val modes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
            return minDist > 0f && modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)
        }

    /** Uygun arka kamerayı bulur. Sorun varsa kullanıcıya gösterilecek nedeni döner. */
    fun prepare(): String? {
        if (chars != null) return null
        var reason = "Arka kamera bulunamadı."
        try {
            for (id in manager.cameraIdList) {
                val c = manager.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
                val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
                val manual = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
                val raw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
                if (manual && raw) {
                    cameraId = id
                    chars = c
                    return null
                }
                reason = when {
                    !manual && !raw -> "Bu telefon, dış uygulamalara kameranın manuel ISO/enstantane kontrolünü ve RAW çekimi açmıyor."
                    !manual -> "Bu telefon, dış uygulamalara kameranın manuel ISO/enstantane kontrolünü açmıyor."
                    else -> "Bu telefon, dış uygulamalara RAW (DNG) çekimi açmıyor."
                }
            }
        } catch (e: Exception) {
            reason = "Kamera bilgileri okunamadı."
        }
        return reason
    }

    @SuppressLint("MissingPermission")
    fun start(iso: Int, exposureNs: Long) {
        if (running) return
        val c = chars ?: return
        val id = cameraId ?: return
        val st = texture.surfaceTexture ?: return
        this.iso = iso
        this.exposureNs = exposureNs

        val t = HandlerThread("kamera").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        writer = Executors.newSingleThreadExecutor()

        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map == null) {
            post { listener.onCameraError("Kamera yapılandırması okunamadı.") }
            return
        }
        val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR)
        val rawSize = rawSizes?.maxByOrNull { it.width.toLong() * it.height }
        if (rawSize == null) {
            post { listener.onCameraError("RAW çözünürlüğü bulunamadı.") }
            return
        }
        val previews = map.getOutputSizes(SurfaceTexture::class.java)
        val pv = previews.filter { it.width * 3 == it.height * 4 && it.width <= 1920 }
            .maxByOrNull { it.width * it.height } ?: previews[0]
        st.setDefaultBufferSize(pv.width, pv.height)
        val ps = Surface(st)
        previewSurface = ps

        val r = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 3)
        r.setOnImageAvailableListener({ rd ->
            val img = try {
                rd.acquireNextImage()
            } catch (e: IllegalStateException) {
                null
            } ?: return@setOnImageAvailableListener
            images[img.timestamp] = img
            match(img.timestamp)
        }, h)
        reader = r

        val jpegSize = map.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height }
        jpegReader = jpegSize?.let { size ->
            ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2).also { jr ->
                jr.setOnImageAvailableListener({ rd ->
                    try {
                        rd.acquireNextImage()?.close()
                    } catch (_: Exception) {
                    }
                }, h)
            }
        }

        running = true
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    device = d
                    createSession(d)
                }

                override fun onDisconnected(d: CameraDevice) {
                    d.close()
                    device = null
                }

                override fun onError(d: CameraDevice, error: Int) {
                    d.close()
                    device = null
                    post { listener.onCameraError("Kamera açılamadı (hata $error). Başka bir uygulama kamerayı kullanıyor olabilir.") }
                }
            }, h)
        } catch (e: Exception) {
            running = false
            post { listener.onCameraError("Kamera açılamadı.") }
        }
    }

    private fun createSession(d: CameraDevice) {
        val ps = previewSurface ?: return
        val r = reader ?: return
        val jr = jpegReader
        val surfaces = if (jr != null) listOf(ps, r.surface, jr.surface) else listOf(ps, r.surface)
        try {
            @Suppress("DEPRECATION")
            d.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    updatePreview()
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (jpegReader != null) {
                        // Ek akış kabul edilmedi: onsuz, eskisi gibi kur.
                        jpegReader?.close()
                        jpegReader = null
                        createSession(d)
                    } else {
                        post { listener.onCameraError("Kamera oturumu kurulamadı.") }
                    }
                }
            }, handler)
        } catch (e: Exception) {
            post { listener.onCameraError("Kamera oturumu kurulamadı.") }
        }
    }

    private fun applyManual(b: CaptureRequest.Builder, still: Boolean, singleFlash: Boolean) {
        val c = chars
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
        b.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(exposureNs, 33_333_333L))

        if (realSeq) {
            // Gerçek flaş: pozlamayı kamera seçer, flaş her karede gerektiği gibi patlar.
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
        }

        // Odak: dokunulan bölgeye tek seferlik otomatik odak, yoksa sürekli otomatik odak.
        val region = afRegion
        val afModes = c?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
        if (region != null && canFocus) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            val maxRegions = c?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
            if (maxRegions > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
        } else if (afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }

        if (realSeq) return

        // Flaş: sürekli modda her karede yanar; patlama modunda çekim sırasında kısa süre yanar.
        val torch = flash == Flash.TORCH || preFlash || (still && singleFlash && flash == Flash.SINGLE)
        val mode = if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
        b.set(CaptureRequest.FLASH_MODE, mode)
        if (torch && flashStrength > 0 && !strengthBroken && Build.VERSION.SDK_INT >= 35) {
            b.set(CaptureRequest.FLASH_STRENGTH_LEVEL, flashStrength)
        }
    }

    fun setExposure(iso: Int, exposureNs: Long) {
        this.iso = iso
        this.exposureNs = exposureNs
        handler?.post { updatePreview() }
    }

    /** strength: telefonun kendi güç ölçeğinde seviye; 0 = varsayılan güç. */
    fun setFlash(mode: Flash, strength: Int) {
        flash = mode
        flashStrength = strength
        handler?.post { updatePreview() }
    }

    private fun updatePreview() {
        val d = device ?: return
        val s = session ?: return
        val ps = previewSurface ?: return
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(ps)
            applyManual(b, still = false, singleFlash = false)
            s.setRepeatingRequest(b.build(), previewCallback, handler)
        } catch (_: Exception) {
        }
    }

    /** Fener istendiği hâlde güç ayarı yüzünden yanmıyorsa güç ayarını bırakır. */
    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            if (realSeq) {
                val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                if (ae != null) {
                    aeStateKnown = true
                    if (ae == CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        sawPrecapture = true
                    } else if (sawPrecapture) {
                        aeReady = true
                    }
                }
            }
            val wantTorch = request.get(CaptureRequest.FLASH_MODE) == CaptureRequest.FLASH_MODE_TORCH
            if (preFlash && wantTorch) {
                val fs = result.get(CaptureResult.FLASH_STATE)
                if (fs != null) flashStateKnown = true
                if (fs == CaptureResult.FLASH_STATE_FIRED || fs == CaptureResult.FLASH_STATE_PARTIAL) preFlashFrames++
            }
            if (!wantTorch || strengthBroken || flashStrength <= 0 || Build.VERSION.SDK_INT < 35) {
                torchMisses = 0
                return
            }
            if (request.get(CaptureRequest.FLASH_STRENGTH_LEVEL) == null) return
            val st = result.get(CaptureResult.FLASH_STATE) ?: return
            if (st == CaptureResult.FLASH_STATE_FIRED || st == CaptureResult.FLASH_STATE_PARTIAL) {
                torchMisses = 0
            } else if (++torchMisses >= 15) {
                strengthBroken = true
                torchMisses = 0
                updatePreview()
                post { listener.onFlashStrengthUnsupported() }
            }
        }
    }

    // ---------- Odak ----------

    /** Vizördeki noktaya (0..1 aralığında) odaklanır. */
    fun focusAt(nx: Float, ny: Float) {
        val c = chars ?: return
        val act = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val so = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        // Ekrandaki dikey görüntü, sensör görüntüsünün saat yönünde döndürülmüş hâlidir.
        val sx: Float
        val sy: Float
        when (so) {
            90 -> { sx = ny; sy = 1f - nx }
            180 -> { sx = 1f - nx; sy = 1f - ny }
            270 -> { sx = 1f - ny; sy = nx }
            else -> { sx = nx; sy = ny }
        }
        val size = (minOf(act.width(), act.height()) * 0.12f).roundToInt().coerceAtLeast(16)
        val left = ((sx * act.width()).roundToInt() - size / 2).coerceIn(0, act.width() - size)
        val top = ((sy * act.height()).roundToInt() - size / 2).coerceIn(0, act.height() - size)
        afRegion = MeteringRectangle(left, top, size, size, MeteringRectangle.METERING_WEIGHT_MAX - 1)
        handler?.post { triggerAf() }
    }

    /** Sürekli otomatik odağa geri döner. */
    fun resetFocus() {
        if (afRegion == null) return
        afRegion = null
        handler?.post {
            sendAfTrigger(CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            updatePreview()
        }
    }

    private fun triggerAf() {
        sendAfTrigger(CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
        updatePreview()
        sendAfTrigger(CaptureRequest.CONTROL_AF_TRIGGER_START)
    }

    private fun sendAfTrigger(trigger: Int) {
        val d = device ?: return
        val s = session ?: return
        val ps = previewSurface ?: return
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(ps)
            applyManual(b, still = false, singleFlash = false)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, trigger)
            s.capture(b.build(), null, handler)
        } catch (_: Exception) {
        }
    }

    // ---------- Çekim ----------

    /**
     * Bir kare çeker. Kamera hazır değilse ya da kayıt hattı doluysa false döner.
     * withFlash: patlama modundaki flaşın bu karede patlayıp patlamayacağı.
     */
    fun capture(withFlash: Boolean): Boolean {
        val h = handler ?: return false
        if (device == null || session == null || reader == null) return false
        if (inFlight.get() >= 2) return false
        inFlight.incrementAndGet()
        val orientation = exifOrientation()
        val fire = withFlash && flash == Flash.SINGLE
        val real = withFlash && flash == Flash.REAL
        h.post {
            if (real) {
                startRealFlash(h, orientation)
            } else if (fire) {
                // Önce feneri yak, kamera "ışık yandı" deyince kareyi çek.
                preFlash = true
                preFlashFrames = 0
                flashStateKnown = false
                updatePreview()
                waitForTorch(h, orientation, System.currentTimeMillis(), retried = false)
            } else {
                doCapture(orientation, fire)
            }
        }
        return true
    }

    private fun waitForTorch(h: Handler, orientation: Int, startMs: Long, retried: Boolean) {
        val elapsed = System.currentTimeMillis() - startMs
        val strengthInUse = flashStrength > 0 && !strengthBroken && Build.VERSION.SDK_INT >= 35
        when {
            // Işık yandı: hemen çek.
            preFlashFrames >= 1 -> doCapture(orientation, true)
            // Kamera durum bildirmiyorsa sabit süre bekle.
            !flashStateKnown && elapsed >= PRE_FLASH_MS -> doCapture(orientation, true)
            // Güç ayarı feneri susturuyor: ayarı bırak ve bir kez daha dene.
            flashStateKnown && strengthInUse && !retried && elapsed >= 600 -> {
                strengthBroken = true
                updatePreview()
                post { listener.onFlashStrengthUnsupported() }
                h.postDelayed({ waitForTorch(h, orientation, System.currentTimeMillis(), retried = true) }, 30)
            }
            // Yine de yanmadıysa beklemeyi bırakıp çek.
            elapsed >= MAX_PRE_FLASH_MS -> doCapture(orientation, true)
            else -> h.postDelayed({ waitForTorch(h, orientation, startMs, retried) }, 30)
        }
    }

    private fun doCapture(orientation: Int, fire: Boolean) {
        val d = device
        val s = session
        val r = reader
        val h = handler
        fun done() {
            if (fire && preFlash) {
                preFlash = false
                updatePreview()
            }
        }
        if (d == null || s == null || r == null || h == null) {
            inFlight.decrementAndGet()
            preFlash = false
            return
        }
        try {
            // Flaşlı karede, fenerin çalıştığı vizör akışıyla aynı ayarlarla çekilir.
            val b = if (fire) {
                d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).also {
                    previewSurface?.let { ps -> it.addTarget(ps) }
                    it.addTarget(r.surface)
                    applyManual(it, still = false, singleFlash = false)
                }
            } else {
                d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).also {
                    it.addTarget(r.surface)
                    applyManual(it, still = true, singleFlash = false)
                }
            }
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                // Pozlama başladı: bu karenin ayarları kilitlendi, feneri hemen söndür.
                override fun onCaptureStarted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    timestamp: Long,
                    frameNumber: Long
                ) {
                    done()
                }

                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    done()
                    val ts = result.get(CaptureResult.SENSOR_TIMESTAMP)
                    if (ts == null) {
                        inFlight.decrementAndGet()
                        return
                    }
                    results[ts] = result to orientation
                    match(ts)
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    done()
                    inFlight.decrementAndGet()
                    post { listener.onCameraError("Çekim başarısız oldu.") }
                }
            }, h)
        } catch (e: Exception) {
            done()
            inFlight.decrementAndGet()
            post { listener.onCameraError("Çekim başarısız oldu.") }
        }
    }

    // ---------- Gerçek flaş ----------

    private fun startRealFlash(h: Handler, orientation: Int) {
        val d = device
        val s = session
        val ps = previewSurface
        if (d == null || s == null || ps == null) {
            inFlight.decrementAndGet()
            return
        }
        realSeq = true
        sawPrecapture = false
        aeReady = false
        aeStateKnown = false
        try {
            updatePreview()
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(ps)
            applyManual(b, still = false, singleFlash = false)
            b.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            s.capture(b.build(), null, h)
        } catch (e: Exception) {
            endRealFlash()
            inFlight.decrementAndGet()
            post { listener.onCameraError("Çekim başarısız oldu.") }
            return
        }
        waitForPrecapture(h, orientation, System.currentTimeMillis())
    }

    private fun waitForPrecapture(h: Handler, orientation: Int, startMs: Long) {
        val elapsed = System.currentTimeMillis() - startMs
        when {
            aeReady -> doRealCapture(orientation)
            !aeStateKnown && elapsed >= 600 -> doRealCapture(orientation)
            elapsed >= 2000 -> doRealCapture(orientation)
            else -> h.postDelayed({ waitForPrecapture(h, orientation, startMs) }, 30)
        }
    }

    private fun endRealFlash() {
        if (!realSeq) return
        realSeq = false
        updatePreview()
    }

    private fun doRealCapture(orientation: Int) {
        val d = device
        val s = session
        val r = reader
        val h = handler
        if (d == null || s == null || r == null || h == null) {
            realSeq = false
            inFlight.decrementAndGet()
            return
        }
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(r.surface)
            jpegReader?.let { b.addTarget(it.surface) }
            applyManual(b, still = true, singleFlash = false)
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    endRealFlash()
                    val fs = result.get(CaptureResult.FLASH_STATE)
                    if (fs != null && fs != CaptureResult.FLASH_STATE_FIRED && fs != CaptureResult.FLASH_STATE_PARTIAL) {
                        post { listener.onRealFlashMissed() }
                    }
                    val ts = result.get(CaptureResult.SENSOR_TIMESTAMP)
                    if (ts == null) {
                        inFlight.decrementAndGet()
                        return
                    }
                    results[ts] = result to orientation
                    match(ts)
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    endRealFlash()
                    inFlight.decrementAndGet()
                    post { listener.onCameraError("Çekim başarısız oldu.") }
                }
            }, h)
        } catch (e: Exception) {
            endRealFlash()
            inFlight.decrementAndGet()
            post { listener.onCameraError("Çekim başarısız oldu.") }
        }
    }

    /** Aynı zaman damgalı görüntü ve sonuç geldiğinde DNG yazmaya gönderir. */
    private fun match(ts: Long) {
        val img = images[ts] ?: return
        val res = results[ts] ?: return
        images.remove(ts)
        results.remove(ts)
        val w = writer
        if (w == null) {
            img.close()
            inFlight.decrementAndGet()
            return
        }
        try {
            w.execute {
                writeDng(res.first, img, res.second)
                inFlight.decrementAndGet()
            }
        } catch (e: Exception) {
            img.close()
            inFlight.decrementAndGet()
        }
    }

    private fun exifOrientation(): Int {
        val sensor = chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        return when ((sensor + deviceRotation) % 360) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun writeDng(res: TotalCaptureResult, img: Image, orientation: Int) {
        val c = chars
        if (c == null) {
            img.close()
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val name = "RAW_$stamp.dng"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/RawKamera")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        var uri: Uri? = null
        var dng: DngCreator? = null
        try {
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Kayıt yeri oluşturulamadı")
            val creator = DngCreator(c, res)
            dng = creator
            creator.setOrientation(orientation)
            val out = resolver.openOutputStream(uri) ?: throw IOException("Dosya açılamadı")
            out.use { creator.writeImage(it, img) }
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            post { listener.onSaved(name) }
        } catch (e: Exception) {
            uri?.let {
                try {
                    resolver.delete(it, null, null)
                } catch (_: Exception) {
                }
            }
            post { listener.onCameraError("Fotoğraf kaydedilemedi.") }
        } finally {
            dng?.close()
            img.close()
        }
    }

    fun stop() {
        running = false
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
        try {
            device?.close()
        } catch (_: Exception) {
        }
        device = null
        // Yazılmakta olan kareler bitsin, sonra görüntü kaynağı kapansın.
        val w = writer
        writer = null
        w?.shutdown()
        try {
            w?.awaitTermination(4, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        thread?.quitSafely()
        thread = null
        handler = null
        for (img in images.values) img.close()
        images.clear()
        results.clear()
        reader?.close()
        reader = null
        jpegReader?.close()
        jpegReader = null
        previewSurface?.release()
        previewSurface = null
        inFlight.set(0)
        afRegion = null
        preFlash = false
        realSeq = false
    }

    private fun post(block: () -> Unit) {
        main.post(block)
    }

    companion object {
        private const val PRE_FLASH_MS = 300L
        private const val MAX_PRE_FLASH_MS = 1200L
    }
}
