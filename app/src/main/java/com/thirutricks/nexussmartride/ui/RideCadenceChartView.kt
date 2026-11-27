package com.thirutricks.nexussmartride.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.res.ResourcesCompat
import com.thirutricks.nexussmartride.R
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

data class CadenceChartPoint(
    val label: String,
    val fullDate: String = "",
    val distanceKm: Double,
    val socConsumed: Int,
    val isPeak: Boolean = false,
    val isToday: Boolean = false
)

/**
 * High-performance dual-axis cyber-automotive chart component.
 * Displays daily Distance (km) as glowing cyan bars and SoC Used (%)
 * as an overlaid kinetic emerald smooth curve with connected node points.
 */
class RideCadenceChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var points: List<CadenceChartPoint> = emptyList()
    private var selectedIndex: Int = -1
    private var animProgress: Float = 1f
    private var animator: ValueAnimator? = null

    var onPointSelected: ((CadenceChartPoint) -> Unit)? = null

    // Colors
    private val colorGrid = Color.parseColor("#152033")
    private val colorAxisText = Color.parseColor("#64748B")
    private val colorXAxisText = Color.parseColor("#94A3B8")
    private val colorBarFill = Color.parseColor("#260284C7")
    private val colorBarBorder = Color.parseColor("#38BDF8")
    private val colorBarSelectedFill = Color.parseColor("#5900E5FF")
    private val colorBarSelectedBorder = Color.parseColor("#00E5FF")
    private val colorSocLine = Color.parseColor("#01F5A0")
    private val colorSocDotOuter = Color.parseColor("#01F5A0")
    private val colorSocDotInner = Color.parseColor("#5EEAD4")
    private val colorTooltipBg = Color.parseColor("#E60D131F")
    private val colorTooltipBorder = Color.parseColor("#4D00E5FF")

    // Paints
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorGrid
        strokeWidth = 1f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorAxisText
        textSize = 9f * resources.displayMetrics.scaledDensity
        typeface = ResourcesCompat.getFont(context, R.font.jetbrains_mono)
    }

    private val xLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorXAxisText
        textSize = 9.5f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, R.font.space_grotesk)
    }

    private val barFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val barStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * resources.displayMetrics.density
    }

    private val socLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorSocLine
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val socDotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val highlightLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4000E5FF")
        strokeWidth = 1f * resources.displayMetrics.density
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }

    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorTooltipBg
        style = Paint.Style.FILL
    }

    private val tooltipBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorTooltipBorder
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
    }

    private val tooltipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 10f * resources.displayMetrics.scaledDensity
        typeface = ResourcesCompat.getFont(context, R.font.space_grotesk)
    }

    fun setPoints(newPoints: List<CadenceChartPoint>, animate: Boolean = true) {
        this.points = newPoints
        this.selectedIndex = if (newPoints.isNotEmpty()) newPoints.size - 1 else -1

        animator?.cancel()
        if (animate && newPoints.isNotEmpty()) {
            animProgress = 0f
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 650
                interpolator = DecelerateInterpolator(1.8f)
                addUpdateListener {
                    animProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            animProgress = 1f
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) return

        val density = resources.displayMetrics.density
        val padLeft = 32f * density
        val padRight = 30f * density
        val padTop = 16f * density
        val padBottom = 26f * density

        val plotLeft = padLeft
        val plotTop = padTop
        val plotRight = width.toFloat() - padRight
        val plotBottom = height.toFloat() - padBottom

        val plotW = plotRight - plotLeft
        val plotH = plotBottom - plotTop
        if (plotW <= 0f || plotH <= 0f) return

        // 1. Calculate dynamic scales
        val rawMaxDist = points.maxOfOrNull { it.distanceKm } ?: 10.0
        val (maxDist, leftStep) = when {
            rawMaxDist <= 20.0 -> Pair(20.0, 5.0)
            rawMaxDist <= 40.0 -> Pair(40.0, 10.0)
            rawMaxDist <= 60.0 -> Pair(60.0, 10.0)
            rawMaxDist <= 80.0 -> Pair(80.0, 10.0)
            rawMaxDist <= 100.0 -> Pair(100.0, 20.0)
            else -> {
                val rounded = ceil(rawMaxDist / 20.0) * 20.0
                Pair(rounded, 20.0)
            }
        }

        // Fixed SoC scale 0..120 with steps of 20 to match admin dashboard
        val maxSoc = 120
        val socSteps = intArrayOf(0, 20, 40, 60, 80, 100, 120)

        // 2. Draw Horizontal Gridlines & Left Y-Axis labels (Distance in km)
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.color = colorAxisText
        val numGridSteps = (maxDist / leftStep).toInt()
        for (s in 0..numGridSteps) {
            val distVal = (s * leftStep).toInt()
            val ratio = distVal.toFloat() / maxDist.toFloat()
            val y = plotBottom - ratio * plotH

            // Gridline
            canvas.drawLine(plotLeft, y, plotRight, y, gridPaint)

            // Left Y-Axis Label
            canvas.drawText(distVal.toString(), plotLeft - 6f * density, y + 3.5f * density, textPaint)
        }

        // 3. Draw Right Y-Axis labels (SoC Used in %) in kinetic emerald green
        val socTextPaint = Paint(textPaint).apply {
            textAlign = Paint.Align.LEFT
            color = colorSocLine
            typeface = textPaint.typeface
        }
        for (socVal in socSteps) {
            val ratio = socVal.toFloat() / maxSoc.toFloat()
            val y = plotBottom - ratio * plotH
            canvas.drawText(socVal.toString(), plotRight + 6f * density, y + 3.5f * density, socTextPaint)
        }

        // 3. Compute Column Centers & Bar Width
        val n = points.size
        val colWidth = plotW / n
        val maxBarWidth = when {
            n <= 3 -> 26f * density
            n <= 7 -> 20f * density
            n <= 14 -> 12f * density
            else -> 6f * density
        }
        val barWidth = minOf(colWidth * 0.58f, maxBarWidth)
        val cornerRadius = minOf(barWidth / 2f, 5f * density)

        // 4. Draw Distance Bars
        val dotCoords = mutableListOf<PointF>()
        for (i in 0 until n) {
            val pt = points[i]
            val cx = plotLeft + (i + 0.5f) * colWidth

            // Calculate Bar Height
            val barRatio = ((pt.distanceKm / maxDist).toFloat() * animProgress).coerceIn(0f, 1f)
            val barHeight = max(barRatio * plotH, if (pt.distanceKm > 0) 6f * density else 2f * density)
            val barTop = plotBottom - barHeight
            val barLeft = cx - barWidth / 2f
            val barRight = cx + barWidth / 2f

            val isSelected = (i == selectedIndex)
            barFillPaint.color = if (isSelected) colorBarSelectedFill else colorBarFill
            barStrokePaint.color = if (isSelected) colorBarSelectedBorder else colorBarBorder

            val barRect = RectF(barLeft, barTop, barRight, plotBottom)
            // Draw filled bar
            canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, barFillPaint)
            // Draw crisp neon stroke
            canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, barStrokePaint)

            // Calculate SoC Point
            val socRatio = ((pt.socConsumed.toFloat() / maxSoc) * animProgress).coerceIn(0f, 1f)
            val socY = plotBottom - socRatio * plotH
            dotCoords.add(PointF(cx, socY))

            // Draw X-Axis Label
            xLabelPaint.color = if (isSelected) Color.WHITE else colorXAxisText
            xLabelPaint.isFakeBoldText = isSelected
            canvas.drawText(pt.label, cx, plotBottom + 16f * density, xLabelPaint)
        }

        // 5. Draw SoC Used Smooth Curve (Cubic Spline)
        if (dotCoords.size > 1) {
            val splinePath = Path()
            splinePath.moveTo(dotCoords[0].x, dotCoords[0].y)

            for (i in 0 until dotCoords.size - 1) {
                val p0 = dotCoords[i]
                val p1 = dotCoords[i + 1]
                val midX = (p0.x + p1.x) / 2f
                splinePath.cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
            }
            canvas.drawPath(splinePath, socLinePaint)
        }

        // 6. Draw SoC Circular Node Markers (Outer ring + Inner core)
        val outerRadius = 4.2f * density
        val innerRadius = 2.2f * density
        for (i in dotCoords.indices) {
            val p = dotCoords[i]
            val isSelected = (i == selectedIndex)

            // Outer ring
            socDotPaint.style = Paint.Style.FILL
            socDotPaint.color = if (isSelected) Color.WHITE else colorSocDotOuter
            canvas.drawCircle(p.x, p.y, if (isSelected) outerRadius + 1.5f * density else outerRadius, socDotPaint)

            // Inner core
            socDotPaint.color = if (isSelected) colorSocLine else colorSocDotInner
            canvas.drawCircle(p.x, p.y, innerRadius, socDotPaint)
        }

        // 7. Draw Selected Inspection Guide & Tooltip
        if (selectedIndex in points.indices) {
            val selPt = points[selectedIndex]
            val selX = plotLeft + (selectedIndex + 0.5f) * colWidth

            // Vertical guideline
            canvas.drawLine(selX, plotTop, selX, plotBottom, highlightLinePaint)

            // Floating Tooltip Badge
            val tooltipText = String.format(
                Locale.US,
                "%.1f km  •  ⚡ %d%% SoC",
                selPt.distanceKm,
                selPt.socConsumed
            )
            val tooltipPadH = 8f * density
            val tooltipPadV = 4f * density
            val textW = tooltipTextPaint.measureText(tooltipText)
            val fontMetrics = tooltipTextPaint.fontMetrics
            val textH = fontMetrics.bottom - fontMetrics.top

            val ttW = textW + tooltipPadH * 2
            val ttH = textH + tooltipPadV * 2

            val ttLeft = (selX - ttW / 2f).coerceIn(plotLeft, plotRight - ttW)
            val ttTop = (plotTop - 4f * density).coerceAtLeast(0f)
            val ttRect = RectF(ttLeft, ttTop, ttLeft + ttW, ttTop + ttH)

            canvas.drawRoundRect(ttRect, 6f * density, 6f * density, tooltipBgPaint)
            canvas.drawRoundRect(ttRect, 6f * density, 6f * density, tooltipBorderPaint)

            val textBaseY = ttTop + tooltipPadV - fontMetrics.top
            canvas.drawText(tooltipText, ttLeft + tooltipPadH, textBaseY, tooltipTextPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (points.isEmpty()) return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val density = resources.displayMetrics.density
                val padLeft = 32f * density
                val padRight = 30f * density
                val plotW = width.toFloat() - padLeft - padRight
                if (plotW <= 0f) return true

                val colWidth = plotW / points.size
                val touchX = event.x - padLeft
                val index = (touchX / colWidth).toInt().coerceIn(0, points.size - 1)

                if (index != selectedIndex) {
                    selectedIndex = index
                    invalidate()
                    onPointSelected?.invoke(points[index])
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
