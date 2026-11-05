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
    enum class Flash { OFF, SINGLE, TORCH }

    interface Listener {
        fun onCameraError(message: String)
        fun onSaved(name: String)
    }

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val main = Handler(Looper.getMainLooper())

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var writer: ExecutorService? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
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

    /** Flaş testi: 0 = kapalı, 1 = manuel pozlama + fener, 2 = otomatik pozlama + fener. */
    @Volatile private var flashTest = 0

    fun setFlashTest(method: Int) {
        flashTest = method
        handler?.post { updatePreview() }
    }

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
        try {
            @Suppress("DEPRECATION")
            d.createCaptureSession(listOf(ps, r.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    updatePreview()
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    post { listener.onCameraError("Kamera oturumu kurulamadı.") }
                }
            }, handler)
        } catch (e: Exception) {
            post { listener.onCameraError("Kamera oturumu kurulamadı.") }
        }
    }

    private fun applyManual(b: CaptureRequest.Builder, still: Boolean, singleFlash: Boolean) {
        val c = chars
        if (!still && flashTest == 2) {
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            return
        }
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
        b.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(exposureNs, 33_333_333L))

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

        // Flaş: sürekli modda her karede yanar, patlama modunda sadece çekim karesinde.
        val mode = when {
            !still && flashTest == 1 -> CaptureRequest.FLASH_MODE_TORCH
            flash == Flash.TORCH -> CaptureRequest.FLASH_MODE_TORCH
            still && singleFlash && flash == Flash.SINGLE -> CaptureRequest.FLASH_MODE_SINGLE
            else -> CaptureRequest.FLASH_MODE_OFF
        }
        b.set(CaptureRequest.FLASH_MODE, mode)
        if (mode != CaptureRequest.FLASH_MODE_OFF && flashStrength > 0 && Build.VERSION.SDK_INT >= 35) {
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
            s.setRepeatingRequest(b.build(), null, handler)
        } catch (_: Exception) {
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
        val d = device ?: return false
        val s = session ?: return false
        val r = reader ?: return false
        val h = handler ?: return false
        if (inFlight.get() >= 2) return false
        inFlight.incrementAndGet()
        val orientation = exifOrientation()
        h.post {
            try {
                val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                b.addTarget(r.surface)
                applyManual(b, still = true, singleFlash = withFlash)
                s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
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
                        inFlight.decrementAndGet()
                        post { listener.onCameraError("Çekim başarısız oldu.") }
                    }
                }, h)
            } catch (e: Exception) {
                inFlight.decrementAndGet()
                post { listener.onCameraError("Çekim başarısız oldu.") }
            }
        }
        return true
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
        previewSurface?.release()
        previewSurface = null
        inFlight.set(0)
        afRegion = null
    }

    private fun post(block: () -> Unit) {
        main.post(block)
    }
}
