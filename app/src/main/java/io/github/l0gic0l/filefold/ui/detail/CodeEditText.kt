package io.github.l0gic0l.filefold.ui.detail

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.EditText
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.max

/**
 * The editor's text view: Android's own EditText, which re-measures only the
 * paragraph being edited and draws only the lines on screen, so large files
 * stay responsive. On top of it this adds line numbers, fling scrolling and
 * the editor's keyboard shortcuts. Static settings live in res/layout/code_editor.xml.
 */
class CodeEditText(context: Context, attrs: AttributeSet?) : EditText(context, attrs) {

    interface Listener {
        fun onSelectionChanged(start: Int, end: Int)
        fun onUndo()
        fun onRedo()
        /** Ctrl+S, Ctrl+F, Ctrl+G and friends. Returns true when handled. */
        fun onShortcut(keyCode: Int, shift: Boolean): Boolean
    }

    var listener: Listener? = null

    var lineNumbers: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                updatePadding()
            }
        }

    /** Number of lines in the text, kept up to date by the session; sets the gutter width. */
    var totalLines: Int = 1
        set(value) {
            val oldDigits = digits(field)
            field = value
            if (digits(value) != oldDigits) updatePadding()
        }

    private val density = resources.displayMetrics.density
    private val numberPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val gutterPaint = Paint()
    private val clip = Rect()
    private val gutterLines = LineCounter()
    private var gutterWidth = 0

    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFling = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downY = 0f
    private var dragged = false

    init {
        updatePadding()
    }

    fun setColors(text: Int, gutter: Int, numbers: Int, highlight: Int, cursor: Int) {
        setTextColor(text)
        gutterPaint.color = gutter
        numberPaint.color = numbers
        highlightColor = highlight
        textCursorDrawable = GradientDrawable().apply {
            setColor(cursor)
            setSize((2 * density).toInt(), 0)
        }
        invalidate()
    }

    fun setEditorTextSize(sp: Float) {
        // applyDimension follows the user's (possibly non-linear) font scale.
        if (textSize != TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)) {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            updatePadding()
        }
    }

    /** The text is about to change at [offset]: line numbers above it are still right. */
    fun invalidateLinesFrom(offset: Int) = gutterLines.invalidateFrom(offset)

    private fun digits(n: Int) = n.coerceAtLeast(1).toString().length.coerceAtLeast(2)

    private fun updatePadding() {
        numberPaint.textSize = textSize * 0.9f
        numberPaint.typeface = typeface
        val pad = (8 * density).toInt()
        gutterWidth = if (lineNumbers) (numberPaint.measureText("9".repeat(digits(totalLines))) + 3 * pad).toInt() else 0
        setPadding(gutterWidth + pad, pad, 2 * pad, 8 * pad)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!lineNumbers) return
        val layout = layout ?: return
        canvas.getClipBounds(clip)
        // The canvas is scrolled with the text; drawing at scrollX keeps the gutter fixed on the left.
        val left = scrollX.toFloat()
        canvas.drawRect(left, scrollY.toFloat(), left + gutterWidth, (scrollY + height).toFloat(), gutterPaint)
        val text = text ?: return
        if (layout.lineCount == 0) return
        val top = totalPaddingTop
        val first = layout.getLineForVertical(max(0, clip.top - top))
        val last = layout.getLineForVertical(max(0, clip.bottom - top))
        val x = left + gutterWidth - 8 * density
        var number = -1
        for (line in first..last) {
            val start = layout.getLineStart(line)
            // Wrapped continuation rows get no number.
            if (start > 0 && start <= text.length && text[start - 1] != '\n') continue
            number = if (number < 0) gutterLines.lineAt(text, start) + 1 else number + 1
            canvas.drawText(number.toString(), x, (top + layout.getLineBaseline(line)).toFloat(), numberPaint)
        }
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        listener?.onSelectionChanged(selStart, selEnd)
    }

    // A plain EditText stops dead when you lift your finger; this adds momentum.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                velocity?.recycle()
                velocity = VelocityTracker.obtain()
                downY = event.y
                dragged = false
            }
            MotionEvent.ACTION_MOVE -> if (abs(event.y - downY) > touchSlop) dragged = true
        }
        velocity?.addMovement(event)
        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            val tracker = velocity
            if (tracker != null && event.actionMasked == MotionEvent.ACTION_UP && dragged && !hasSelection()) {
                tracker.computeCurrentVelocity(1000, maxFling.toFloat())
                val vy = tracker.yVelocity
                val layout = layout
                if (abs(vy) > minFling && layout != null) {
                    val maxY = max(0, layout.height + totalPaddingTop + totalPaddingBottom - height)
                    scroller.fling(scrollX, scrollY, 0, -vy.toInt(), scrollX, scrollX, 0, maxY)
                    postInvalidateOnAnimation()
                }
            }
            tracker?.recycle()
            velocity = null
        }
        return handled
    }

    override fun computeScroll() {
        super.computeScroll()
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, scroller.currY)
            postInvalidateOnAnimation()
        }
    }

    // The editor keeps its own undo history, so the platform's undo commands are routed there.
    override fun onTextContextMenuItem(id: Int): Boolean = when (id) {
        android.R.id.undo -> { listener?.onUndo(); true }
        android.R.id.redo -> { listener?.onRedo(); true }
        else -> super.onTextContextMenuItem(id)
    }

    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isCtrlPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_Z -> {
                    if (event.isShiftPressed) listener?.onRedo() else listener?.onUndo()
                    return true
                }
                KeyEvent.KEYCODE_Y -> {
                    listener?.onRedo()
                    return true
                }
                else -> if (listener?.onShortcut(keyCode, event.isShiftPressed) == true) return true
            }
        }
        return super.onKeyShortcut(keyCode, event)
    }
}
