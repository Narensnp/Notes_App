package com.layers.app.drawing

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.layers.app.data.model.InkStroke
import com.layers.app.data.model.InkTool

/**
 * Draws vector ink on any android.graphics.Canvas (screen, PDF page, exported image).
 * Stroke coordinates and widths are stored relative to the canvas width, so [widthPx] scales them.
 */
object InkRenderer {

    private val penPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val highlighterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    const val HIGHLIGHTER_ALPHA = 0x5A

    @Synchronized
    fun drawStrokes(canvas: Canvas, strokes: List<InkStroke>, widthPx: Float, dimColor: Int? = null) {
        // Highlighter strokes go underneath the pen so writing stays crisp.
        for (s in strokes) if (s.tool == InkTool.HIGHLIGHTER) drawStroke(canvas, s, widthPx, dimColor)
        for (s in strokes) if (s.tool == InkTool.PEN) drawStroke(canvas, s, widthPx, dimColor)
    }

    @Synchronized
    fun drawStroke(canvas: Canvas, stroke: InkStroke, widthPx: Float, overrideColor: Int? = null) {
        val pts = stroke.points
        if (pts.size < 3) return
        val color = overrideColor ?: stroke.color.toInt()
        val baseWidth = (stroke.width * widthPx).coerceAtLeast(0.6f)
        when (stroke.tool) {
            InkTool.HIGHLIGHTER -> {
                highlighterPaint.color = color
                highlighterPaint.alpha = HIGHLIGHTER_ALPHA
                highlighterPaint.strokeWidth = baseWidth
                path.reset()
                path.moveTo(pts[0] * widthPx, pts[1] * widthPx)
                if (pts.size == 3) {
                    path.lineTo(pts[0] * widthPx + 0.1f, pts[1] * widthPx)
                } else {
                    var i = 3
                    while (i + 2 < pts.size) {
                        val x0 = pts[i - 3] * widthPx
                        val y0 = pts[i - 2] * widthPx
                        val x1 = pts[i] * widthPx
                        val y1 = pts[i + 1] * widthPx
                        path.quadTo(x0, y0, (x0 + x1) / 2f, (y0 + y1) / 2f)
                        i += 3
                    }
                    path.lineTo(pts[pts.size - 3] * widthPx, pts[pts.size - 2] * widthPx)
                }
                canvas.drawPath(path, highlighterPaint)
            }
            InkTool.PEN -> {
                penPaint.color = color
                if (pts.size == 3) {
                    penPaint.style = Paint.Style.FILL
                    canvas.drawCircle(pts[0] * widthPx, pts[1] * widthPx, baseWidth * pressureFactor(pts[2]) / 2f, penPaint)
                    penPaint.style = Paint.Style.STROKE
                    return
                }
                var i = 3
                while (i + 2 < pts.size) {
                    val p = (pts[i - 1] + pts[i + 2]) / 2f
                    penPaint.strokeWidth = baseWidth * pressureFactor(p)
                    canvas.drawLine(
                        pts[i - 3] * widthPx, pts[i - 2] * widthPx,
                        pts[i] * widthPx, pts[i + 1] * widthPx,
                        penPaint
                    )
                    i += 3
                }
            }
        }
    }

    /** Light touches draw thinner lines, firm pressure thicker ones. */
    fun pressureFactor(pressure: Float): Float = 0.45f + 0.95f * pressure.coerceIn(0f, 1f)

    /** True if a point (in normalized units) is within [radius] of any segment of the stroke. */
    fun hits(stroke: InkStroke, x: Float, y: Float, radius: Float): Boolean {
        val pts = stroke.points
        val r = radius + stroke.width / 2f
        if (pts.size == 3) return dist2(pts[0], pts[1], x, y) <= r * r
        var i = 3
        while (i + 2 < pts.size) {
            if (segmentDist2(x, y, pts[i - 3], pts[i - 2], pts[i], pts[i + 1]) <= r * r) return true
            i += 3
        }
        return false
    }

    private fun dist2(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return dx * dx + dy * dy
    }

    private fun segmentDist2(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val vx = bx - ax
        val vy = by - ay
        val len2 = vx * vx + vy * vy
        if (len2 == 0f) return dist2(px, py, ax, ay)
        val t = (((px - ax) * vx + (py - ay) * vy) / len2).coerceIn(0f, 1f)
        return dist2(px, py, ax + t * vx, ay + t * vy)
    }
}
