package io.github.zby1888.codexzh

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/**
 * Draws the terminal grid in a software layer. The app shell remains hardware
 * accelerated, but terminal glyphs and cell backgrounds are rasterized by
 * Android's software Canvas instead of being submitted individually to HWUI/Mali.
 * This isolates the terminal surface from device-specific GPU driver failures.
 */
internal class TerminalSoftwareView(context: Context) : View(context) {
    private var currentFrame: TerminalFrame = TerminalFrame.blank(24, 80)
    private var scrollbackLoader: (() -> Unit)? = null
    private var scrollOffsetRows = 0
    private var pendingScrollRows = 0
    private var lastTouchY = 0f
    private var dragRemainder = 0f

    private val regularTypeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    private val italicTypeface = Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundPaint = Paint().apply { style = Paint.Style.FILL }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(190, 230, 237, 243)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val scrollHintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(147, 197, 253)
        textSize = 12f * resources.displayMetrics.density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        setBackgroundColor(0xFF101318.toInt())
        textPaint.typeface = regularTypeface
    }

    fun setScrollbackLoader(loader: (() -> Unit)?) {
        scrollbackLoader = loader
    }

    fun updateFrame(frame: TerminalFrame) {
        val hadNoHistory = currentFrame.scrollback.isEmpty()
        currentFrame = frame
        if (hadNoHistory && frame.scrollback.isNotEmpty() && pendingScrollRows > 0) {
            scrollOffsetRows = pendingScrollRows.coerceIn(1, frame.scrollback.size)
            pendingScrollRows = 0
        }
        scrollOffsetRows = scrollOffsetRows.coerceIn(0, frame.scrollback.size)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchY = event.y
                dragRemainder = 0f
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val delta = event.y - lastTouchY
                lastTouchY = event.y
                val rowHeight = height.toFloat() / currentFrame.rows.coerceAtLeast(1)
                if (rowHeight > 0f) {
                    dragRemainder += delta
                    val lines = (dragRemainder / rowHeight).toInt()
                    if (lines != 0) {
                        dragRemainder -= lines * rowHeight
                        if (currentFrame.scrollback.isEmpty() && lines > 0) {
                            pendingScrollRows = (pendingScrollRows + lines).coerceAtLeast(1)
                            scrollbackLoader?.invoke()
                        } else {
                            scrollOffsetRows = (scrollOffsetRows + lines)
                                .coerceIn(0, currentFrame.scrollback.size)
                            invalidate()
                        }
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (scrollOffsetRows > 0) scrollbackLoader?.invoke()
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = currentFrame
        if (frame.rows <= 0 || frame.columns <= 0 || width <= 0 || height <= 0) return

        canvas.drawColor(0xFF101318.toInt())
        val cellWidth = width.toFloat() / frame.columns
        val cellHeight = height.toFloat() / frame.rows
        textPaint.textSize = cellHeight * 0.82f
        val metrics = textPaint.fontMetrics
        val baselineOffset = (cellHeight - metrics.descent - metrics.ascent) / 2f

        val visibleCells = if (scrollOffsetRows > 0 && frame.scrollback.isNotEmpty()) {
            val combined = frame.scrollback + frame.cells
            val end = (combined.size - scrollOffsetRows).coerceIn(frame.rows, combined.size)
            combined.subList(end - frame.rows, end)
        } else {
            frame.cells
        }

        visibleCells.forEachIndexed { row, cells ->
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

        if (frame.cursorVisible && scrollOffsetRows == 0) {
            val left = frame.cursorColumn * cellWidth
            val top = frame.cursorRow * cellHeight
            canvas.drawRect(left, top, left + cellWidth, top + cellHeight, cursorPaint)
        }
        if (scrollOffsetRows > 0) {
            canvas.drawText(
                "历史记录 · 向下拖动查看更多 · 向上拖动回到最新",
                8f * resources.displayMetrics.density,
                16f * resources.displayMetrics.density,
                scrollHintPaint
            )
        }
    }
}
