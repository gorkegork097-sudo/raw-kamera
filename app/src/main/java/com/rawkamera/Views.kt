package com.rawkamera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.max

/** Genişliğe göre 3:4 (dikey) oranında yer kaplayan kutu. */
class AspectFrameLayout(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec)
        val maxH = MeasureSpec.getSize(heightMeasureSpec)
        var w = maxW
        var h = maxW * 4 / 3
        if (maxH in 1 until h) {
            h = maxH
            w = h * 3 / 4
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
    }
}

/** Kompozisyon kılavuzları: üçte bir, altın oran, spiral, çapraz, perspektif. */
class GuideOverlay(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    companion object {
        const val OFF = 0
        const val THIRDS = 1
        const val PHI = 2
        const val SPIRAL = 3
        const val DIAGONAL = 4
        const val PERSPECTIVE = 5
    }

    var mode = THIRDS
        set(v) {
            field = v
            invalidate()
        }

    /** Spiralin hangi köşeye sarıldığı (0-3). */
    var flip = 0
        set(v) {
            field = v
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(77, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val strong = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(115, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 255, 255, 255)
        style = Paint.Style.FILL
    }

    // Altın spiral, 1000 x 1618 birimlik dikey bir dikdörtgende çizilir.
    private val spiral = Path().apply {
        val c = floatArrayOf(
            0f, 1000f, 1000f,
            382f, 1000f, 618f,
            382f, 1236f, 382f,
            236f, 1236f, 236f,
            236f, 1146f, 146f,
            292f, 1146f, 90f,
            292f, 1180f, 56f
        )
        for (i in 0 until 7) {
            val cx = c[i * 3]
            val cy = c[i * 3 + 1]
            val r = c[i * 3 + 2]
            arcTo(RectF(cx - r, cy - r, cx + r, cy + r), -90f + 90f * i, 90f, i == 0)
        }
    }
    private val drawn = Path()
    private val xform = Matrix()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        when (mode) {
            THIRDS -> grid(canvas, w, h, 1f / 3f, 2f / 3f)
            PHI -> grid(canvas, w, h, 0.382f, 0.618f)
            SPIRAL -> {
                xform.reset()
                xform.setScale(w / 1000f, h / 1618f)
                if (flip == 1 || flip == 2) xform.postScale(-1f, 1f, w / 2f, h / 2f)
                if (flip == 2 || flip == 3) xform.postScale(1f, -1f, w / 2f, h / 2f)
                spiral.transform(xform, drawn)
                canvas.drawPath(drawn, strong)
            }
            DIAGONAL -> {
                canvas.drawLine(0f, 0f, w, h, line)
                canvas.drawLine(w, 0f, 0f, h, line)
                val s = 12f * density
                canvas.drawLine(w / 2f - s, h / 2f, w / 2f + s, h / 2f, strong)
                canvas.drawLine(w / 2f, h / 2f - s, w / 2f, h / 2f + s, strong)
            }
            PERSPECTIVE -> {
                val cx = w / 2f
                val cy = h / 2f
                val pts = floatArrayOf(
                    0f, 0f, w / 4f, 0f, w * 3f / 4f, 0f, w, 0f,
                    w, cy, w, h, w * 3f / 4f, h, w / 4f, h, 0f, h, 0f, cy
                )
                for (i in pts.indices step 2) canvas.drawLine(cx, cy, pts[i], pts[i + 1], line)
                canvas.drawCircle(cx, cy, 3f * density, dot)
            }
        }
    }

    private fun grid(canvas: Canvas, w: Float, h: Float, a: Float, b: Float) {
        canvas.drawLine(w * a, 0f, w * a, h, line)
        canvas.drawLine(w * b, 0f, w * b, h, line)
        canvas.drawLine(0f, h * a, w, h * a, line)
        canvas.drawLine(0f, h * b, w, h * b, line)
    }
}

/** Çocuklarını yan yana dizip sığmayınca alt satıra geçiren basit düzen. */
class FlowLayout(context: Context, attrs: AttributeSet?) : ViewGroup(context, attrs) {
    private val gap = (16 * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val childW = MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST)
        val childH = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            measureChild(c, childW, childH)
            if (x > 0 && x + c.measuredWidth > maxW) {
                x = 0
                y += rowH
                rowH = 0
            }
            x += c.measuredWidth + gap
            rowH = max(rowH, c.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), y + rowH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) {
                x = 0
                y += rowH
                rowH = 0
            }
            val left = paddingLeft + x
            val top = paddingTop + y
            c.layout(left, top, left + c.measuredWidth, top + c.measuredHeight)
            x += c.measuredWidth + gap
            rowH = max(rowH, c.measuredHeight)
        }
    }
}

/** Arka plansız, sadece çubuklardan oluşan parlaklık histogramı. */
class HistogramView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val paint = Paint().apply { color = Color.argb(217, 255, 255, 255) }
    private var bins = FloatArray(32)

    fun setBins(values: FloatArray) {
        bins = values
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val n = bins.size
        if (n == 0) return
        val gap = resources.displayMetrics.density
        val bw = (width - gap * (n - 1)) / n
        var maxV = 0f
        for (b in bins) if (b > maxV) maxV = b
        if (maxV <= 0f) return
        val h = height.toFloat()
        for (i in 0 until n) {
            val bh = h * (bins[i] / maxV)
            val x = i * (bw + gap)
            canvas.drawRect(x, h - bh, x + bw, h, paint)
        }
    }
}

/**
 * Seçili değer ortada duran yatay cetvel.
 * Parmakla sağa-sola kaydırınca [onStep] çağrılır (+1 sağdaki değere geçer).
 */
class RulerView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var labels: List<String> = emptyList()
    var index = 0
    var onStep: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val slots = 7
    private val tickPaint = Paint().apply { strokeWidth = density }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        @Suppress("DEPRECATION")
        textSize = 10f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private var lastX = 0f

    override fun onDraw(canvas: Canvas) {
        if (labels.isEmpty()) return
        val slotW = width / slots.toFloat()
        val center = slots / 2
        val tickBase = 16f * density
        for (s in 0 until slots) {
            val i = index - center + s
            if (i < 0 || i >= labels.size) continue
            val selected = i == index
            val color = if (selected) Color.WHITE else Color.rgb(143, 143, 143)
            tickPaint.color = color
            textPaint.color = color
            textPaint.isFakeBoldText = selected
            val cx = slotW * (s + 0.5f)
            val th = (if (selected) 14f else 8f) * density
            canvas.drawLine(cx, tickBase - th, cx, tickBase, tickPaint)
            canvas.drawText(labels[i], cx, tickBase + 4f * density + textPaint.textSize, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val slotW = width / slots.toFloat()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val steps = (dx / slotW).toInt()
                if (steps != 0 && slotW > 0f) {
                    // Cetveli sola sürüklemek sağdaki (daha büyük) değeri getirir.
                    onStep?.invoke(-steps)
                    lastX += steps * slotW
                }
            }
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
