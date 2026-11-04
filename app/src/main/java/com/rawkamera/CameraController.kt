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
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.net.Uri
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

/**
 * Camera2 ile tamamen manuel çalışan arka kamera:
 * otomatik pozlama kapalı, ISO ve enstantane uygulamadan gelir, çekim sadece RAW (DNG).
 */
class CameraController(
    private val context: Context,
    private val texture: TextureView,
    private val listener: Listener
) {
    interface Listener {
        fun onCameraError(message: String)
        fun onSaved(name: String)
    }

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val main = Handler(Looper.getMainLooper())

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null

    private var cameraId: String? = null
    private var chars: CameraCharacteristics? = null

    @Volatile private var iso = 400
    @Volatile private var exposureNs = 8_000_000L
    @Volatile private var busy = false
    @Volatile var running = false
        private set

    /** Telefonun tutuluş açısı (0, 90, 180, 270), DNG yönü için. */
    @Volatile var deviceRotation = 0

    private var pendingResult: TotalCaptureResult? = null
    private var pendingImage: Image? = null
    private var pendingOrientation = ExifInterface.ORIENTATION_NORMAL

    val isoRange: Range<Int>?
        get() = chars?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)

    val exposureRange: Range<Long>?
        get() = chars?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)

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

        val r = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2)
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireNextImage() ?: return@setOnImageAvailableListener
            if (pendingImage != null) {
                img.close()
            } else {
                pendingImage = img
                tryWrite()
            }
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

    private fun applyManual(b: CaptureRequest.Builder) {
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
        b.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(exposureNs, 33_333_333L))
        val af = chars?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
        if (af.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
    }

    fun setExposure(iso: Int, exposureNs: Long) {
        this.iso = iso
        this.exposureNs = exposureNs
        handler?.post { updatePreview() }
    }

    private fun updatePreview() {
        val d = device ?: return
        val s = session ?: return
        val ps = previewSurface ?: return
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(ps)
            applyManual(b)
            s.setRepeatingRequest(b.build(), null, handler)
        } catch (_: Exception) {
        }
    }

    /** Çekimi başlatır. Kamera hazır değilse veya önceki kayıt sürüyorsa false döner. */
    fun capture(): Boolean {
        val d = device ?: return false
        val s = session ?: return false
        val r = reader ?: return false
        val h = handler ?: return false
        if (busy) return false
        busy = true
        pendingOrientation = exifOrientation()
        h.post {
            try {
                val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                b.addTarget(r.surface)
                applyManual(b)
                s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        pendingResult = result
                        tryWrite()
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        busy = false
                        pendingImage?.close()
                        pendingImage = null
                        post { listener.onCameraError("Çekim başarısız oldu.") }
                    }
                }, h)
            } catch (e: Exception) {
                busy = false
                post { listener.onCameraError("Çekim başarısız oldu.") }
            }
        }
        return true
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

    private fun tryWrite() {
        val res = pendingResult ?: return
        val img = pendingImage ?: return
        pendingResult = null
        pendingImage = null
        writeDng(res, img, pendingOrientation)
        busy = false
    }

    private fun writeDng(res: TotalCaptureResult, img: Image, orientation: Int) {
        val c = chars
        if (c == null) {
            img.close()
            return
        }
        val name = "RAW_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".dng"
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
        reader?.close()
        reader = null
        previewSurface?.release()
        previewSurface = null
        thread?.quitSafely()
        thread = null
        handler = null
        pendingImage?.close()
        pendingImage = null
        pendingResult = null
        busy = false
    }

    private fun post(block: () -> Unit) {
        main.post(block)
    }
}
