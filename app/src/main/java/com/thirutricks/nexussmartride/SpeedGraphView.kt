package com.thirutricks.nexussmartride

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * A lightweight live speed graph drawn directly on Canvas.
 * No charting library needed — just raw Android 2D graphics.
 *
 * Keeps the last MAX_POINTS speed samples and draws a smooth
 * cubic-bezier line chart with a gradient fill.
 */
class SpeedGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        private const val MAX_POINTS = 60       // ~30 seconds at 2Hz
        private const val MAX_SPEED  = 60f      // Y-axis ceiling (km/h)
        private const val GRID_LINES = 3
    }

    // Ring buffer of speed samples
    private val samples = ArrayDeque<Float>(MAX_POINTS + 1)

    // Paint objects (allocated once, not per draw)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFF3FB950.toInt()  // accent_green
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0x1AFFFFFF.toInt()  // 10% white
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8B949E.toInt()  // text_secondary
        textSize = 24f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }

    private val path      = Path()
    private val fillPath  = Path()

    // Gradient is rebuilt when size changes
    private var gradient: LinearGradient? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        gradient = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0x663FB950.toInt(), 0x003FB950.toInt()),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        fillPaint.shader = gradient
    }

    /** Call this from MainActivity whenever a new speed value arrives */
    fun addSample(speedKmh: Int) {
        if (samples.size >= MAX_POINTS) samples.removeFirst()
        samples.addLast(speedKmh.coerceAtLeast(0).toFloat())
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f || samples.size < 2) return

        val padLeft  = 48f
        val padRight = 12f
        val padTop   = 16f
        val padBot   = 24f
        val graphW   = w - padLeft - padRight
        val graphH   = h - padTop - padBot

        // ── Grid lines ──────────────────────────────────────────────────────
        val maxVal = max(MAX_SPEED, (samples.maxOrNull() ?: MAX_SPEED) * 1.1f)
        for (i in 0..GRID_LINES) {
            val y = padTop + graphH * (1f - i.toFloat() / GRID_LINES)
            canvas.drawLine(padLeft, y, w - padRight, y, gridPaint)
            val label = "%.0f".format(maxVal * i / GRID_LINES)
            canvas.drawText(label, 4f, y + labelPaint.textSize / 3, labelPaint)
        }

        // ── Build bezier path ─────────────────────────────────────────────
        val pts = samples.toList()
        val step = graphW / (MAX_POINTS - 1).toFloat()

        path.reset()
        fillPath.reset()

        for (idx in pts.indices) {
            val x = padLeft + (MAX_POINTS - pts.size + idx) * step
            val y = padTop + graphH * (1f - (pts[idx] / maxVal).coerceIn(0f, 1f))

            if (idx == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, padTop + graphH)
                fillPath.lineTo(x, y)
            } else {
                // Cubic bezier control points for smooth curve
                val prevX = padLeft + (MAX_POINTS - pts.size + idx - 1) * step
                val prevY = padTop + graphH * (1f - (pts[idx - 1] / maxVal).coerceIn(0f, 1f))
                val cpX = (prevX + x) / 2f
                path.cubicTo(cpX, prevY, cpX, y, x, y)
                fillPath.cubicTo(cpX, prevY, cpX, y, x, y)
            }
        }

        // Close fill path at bottom
        val lastX = padLeft + (pts.size - 1) * step
        fillPath.lineTo(lastX, padTop + graphH)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)
    }
}
