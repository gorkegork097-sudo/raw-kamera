package com.rawkamera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.widget.FrameLayout

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

/** Üçte bir kuralı ızgarası. */
class GridOverlay(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val paint = Paint().apply {
        color = Color.argb(72, 255, 255, 255)
        strokeWidth = resources.displayMetrics.density
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 1..2) {
            val x = w * i / 3f
            val y = h * i / 3f
            canvas.drawLine(x, 0f, x, h, paint)
            canvas.drawLine(0f, y, w, y, paint)
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
        var max = 0f
        for (b in bins) if (b > max) max = b
        if (max <= 0f) return
        val h = height.toFloat()
        for (i in 0 until n) {
            val bh = h * (bins[i] / max)
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
