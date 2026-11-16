package io.github.zby1888.codexzh

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View

/**
 * Draws the terminal grid in a software layer. The app shell remains hardware
 * accelerated, but terminal glyphs and cell backgrounds are rasterized by
 * Android's software Canvas instead of being submitted individually to HWUI/Mali.
 * This isolates the terminal surface from device-specific GPU driver failures.
 */
internal class TerminalSoftwareView(context: Context) : View(context) {
    private var currentFrame: TerminalFrame = TerminalFrame.blank(24, 80)

    private val regularTypeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    private val italicTypeface = Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundPaint = Paint().apply { style = Paint.Style.FILL }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(190, 230, 237, 243)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        setBackgroundColor(0xFF101318.toInt())
        textPaint.typeface = regularTypeface
    }

    fun updateFrame(frame: TerminalFrame) {
        currentFrame = frame
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = currentFrame
        if (frame.rows <= 0 || frame.columns <= 0 || width <= 0 || height <= 0) return

        canvas.drawColor(0xFF101318.toInt())
        val cellWidth = width.toFloat() / frame.columns
        val cellHeight = height.toFloat() / frame.rows
        textPaint.textSize = cellHeight * 0.78f
        val metrics = textPaint.fontMetrics
        val baselineOffset = (cellHeight - metrics.descent - metrics.ascent) / 2f

        frame.cells.forEachIndexed { row, cells ->
            cells.forEachIndexed { column, cell ->
                val reverse = (cell.flags and (1 shl 3)) != 0
                val foreground = if (reverse) cell.background else cell.foreground
                val background = if (reverse) cell.foreground else cell.background
                val left = column * cellWidth
                val top = row * cellHeight

                if (background != null) {
                    backgroundPaint.color = android.graphics.Color.rgb(
                        (background shr 16) and 0xff,
                        (background shr 8) and 0xff,
                        background and 0xff
                    )
                    canvas.drawRect(
                        left, top,
                        left + cellWidth * maxOf(1, cell.width),
                        top + cellHeight,
                        backgroundPaint
                    )
                }

                textPaint.color = if (foreground == null) {
                    android.graphics.Color.rgb(226, 232, 240)
                } else {
                    android.graphics.Color.rgb(
                        (foreground shr 16) and 0xff,
                        (foreground shr 8) and 0xff,
                        foreground and 0xff
                    )
                }
                textPaint.isFakeBoldText = (cell.flags and 1) != 0
                textPaint.isUnderlineText = (cell.flags and (1 shl 2)) != 0
                textPaint.isStrikeThruText = (cell.flags and (1 shl 4)) != 0
                textPaint.typeface = if ((cell.flags and (1 shl 1)) != 0) {
                    italicTypeface
                } else {
                    regularTypeface
                }

                if (cell.text.isNotEmpty() && cell.text != " " && cell.width > 0) {
                    canvas.drawText(cell.text, left, top + baselineOffset, textPaint)
                }
            }
        }

        if (frame.cursorVisible) {
            val left = frame.cursorColumn * cellWidth
            val top = frame.cursorRow * cellHeight
            canvas.drawRect(left, top, left + cellWidth, top + cellHeight, cursorPaint)
        }
    }
}
