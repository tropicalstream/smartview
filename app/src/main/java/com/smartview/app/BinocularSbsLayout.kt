package com.smartview.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioManager
import android.os.SystemClock
import android.util.AttributeSet
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.WebView
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Side-by-side binocular compositor (lineage: TapGarden / TapGPT / TapLinkX3).
 *
 * The first child is a single logical viewport measured to half the physical
 * width and drawn twice (left + right eye). The right temple pad (cyttsp5)
 * drives a cross-hair cursor; the left pad (cyttsp6) is volume. The temple's
 * physical click arrives as KEYCODE_BUTTON_A/DPAD_CENTER — a KEY, never a touch.
 *
 * SmartView specifics:
 *  - Vertical EDGE SCROLLING instead of scroll bars: parking the cursor in the
 *    top/bottom band scrolls the page via [edgeScrollHandler].
 *  - DOUBLE-TAP detection (touch taps or temple-key presses): fires
 *    [doubleTapHandler] (voice/STT entry point). Single clicks are therefore
 *    deferred by the double-tap window before dispatching.
 *  - [tapInterceptor]: when it returns true the tap is consumed before any
 *    click/double-tap logic (used to stop an in-progress voice recording).
 */
class BinocularSbsLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val cursorView = CursorView(context)
    private var webView: WebView? = null

    /** Consume a tap before click logic (e.g. stop recording). Return true = consumed. */
    var tapInterceptor: (() -> Boolean)? = null
    /** Double-tap on the right pad / temple key. */
    var doubleTapHandler: (() -> Unit)? = null
    /** Triple-tap on the right pad / temple key (opens Settings). */
    var tripleTapHandler: (() -> Unit)? = null
    /** Click at logical cursor position; return true to consume (keyboard overlay). */
    var logicalClickHandler: ((Float, Float) -> Boolean)? = null
    /** Vertical edge scroll ticks (dy in logical px, +down). */
    var edgeScrollHandler: ((Int) -> Unit)? = null
    /** Pull against the left edge → back (or exit dim mode). Fires once per pull. */
    var leftEdgeBackHandler: (() -> Unit)? = null
    /** Pull against the right edge → enter dim mode. Fires once per pull. */
    var rightEdgePullHandler: (() -> Unit)? = null
    var contentInteractionBlocked: (() -> Boolean)? = null

    private var cursorX = 320f
    private var cursorY = 240f
    private var activeSide = Side.NONE
    private var lastInputX = 0f
    private var lastInputY = 0f
    private var downInputX = 0f
    private var downInputY = 0f
    private var lastMoveTimeMs = 0L
    private var preMoveCursorX = 0f
    private var preMoveCursorY = 0f
    private var lastMoveDist = 0f
    private var leftVolumeStartY = 0f
    private var leftVolumeStart = 0
    private var currentInputUsesMirroredCoordinates = false
    private var lastClickTime = 0L

    // Multi-tap machinery: each tap-up (re)schedules a resolve after the tap
    // window; taps arriving within the window increment the count. When the
    // sequence settles it dispatches single click / double / triple by count.
    private var lastTapUpTime = 0L
    private var tapCount = 0
    /** First tap of the current sequence was consumed (stopped speech/recording). */
    private var consumedTap = false
    private var multiTapRunnable: Runnable? = null

    private var edgeScrollDy = 0
    private var edgeScrollActive = false
    // Edge PULL gestures: with the cursor pinned at an edge, continued push in
    // that direction accumulates until it crosses the threshold (parking or
    // passing never triggers). The *Fired latches after one fire so a single
    // continuous pull acts exactly once.
    private var leftPullAccum = 0f
    private var leftPullFired = false
    private var rightPullAccum = 0f
    private var rightPullFired = false
    private val edgeScrollRunnable = object : Runnable {
        override fun run() {
            if (!edgeScrollActive || webView == null) return
            if (edgeScrollDy != 0) {
                edgeScrollHandler?.invoke(edgeScrollDy)
                postDelayed(this, EDGE_SCROLL_INTERVAL_MS)
            } else {
                stopEdgeScroll()
            }
        }
    }

    private enum class Side { NONE, LEFT_VOLUME, RIGHT_CURSOR }

    init {
        clipChildren = false
        clipToPadding = false
        addView(cursorView)
    }

    fun setWebViewTarget(view: WebView) {
        webView = view
        cursorView.bringToFront()
        post {
            val logicalWidth = logicalViewportWidth(width).coerceAtLeast(1)
            cursorX = logicalWidth * 0.5f
            cursorY = height * 0.5f
            updateCursor()
        }
    }

    val cursorPosX: Float get() = cursorX
    val cursorPosY: Float get() = cursorY

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val logicalWidth = logicalViewportWidth(measuredWidth)
        val logicalHeight = measuredHeight.coerceAtLeast(0)
        for (i in 0 until childCount) {
            getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(logicalWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(logicalHeight, MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.layout(0, 0, child.measuredWidth, child.measuredHeight)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val logicalWidth = logicalViewportWidth(width)
        if (logicalWidth <= 0) return
        val drawTime = drawingTime
        canvas.save()
        canvas.clipRect(0, 0, logicalWidth, height)
        drawLogicalChildren(canvas, drawTime)
        canvas.restore()
        canvas.save()
        canvas.translate(logicalWidth.toFloat(), 0f)
        canvas.clipRect(0, 0, logicalWidth, height)
        drawLogicalChildren(canvas, drawTime)
        canvas.restore()
    }

    private fun drawLogicalChildren(canvas: Canvas, drawTime: Long) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility != GONE) drawChild(canvas, child, drawTime)
        }
    }

    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        invalidate() // both halves must redraw when logical content changes
    }

    // ------------------------------------------------------------------
    //  Input
    // ------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val kc = event.keyCode
        // Temple physical click = KEY event (X3 guide gotcha #1).
        if (kc == KeyEvent.KEYCODE_BUTTON_A || kc == KeyEvent.KEYCODE_DPAD_CENTER) {
            if (event.action == KeyEvent.ACTION_UP) {
                cursorView.visibility = View.VISIBLE
                onTapUp()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val logicalWidth = logicalViewportWidth(width)
        if (logicalWidth <= 0) return super.dispatchTouchEvent(ev)
        return handleGlassesInput(ev, logicalWidth)
    }

    private fun handleGlassesInput(event: MotionEvent, logicalWidth: Int): Boolean {
        val rawX = event.getX(0)
        val rawY = event.getY(0)
        val localX =
            if (currentInputUsesMirroredCoordinates && rawX >= logicalWidth) rawX - logicalWidth else rawX
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_HOVER_ENTER -> {
                activeSide = classifyInputSide(event, logicalWidth)
                currentInputUsesMirroredCoordinates = isUnknownMirroredCoordinateEvent(event, logicalWidth)
                val startX =
                    if (currentInputUsesMirroredCoordinates && rawX >= logicalWidth) rawX - logicalWidth else rawX
                lastInputX = startX; lastInputY = rawY
                downInputX = startX; downInputY = rawY
                lastMoveTimeMs = 0L; lastMoveDist = 0f
                leftPullAccum = 0f; leftPullFired = false
                rightPullAccum = 0f; rightPullFired = false
                if (activeSide == Side.LEFT_VOLUME) {
                    leftVolumeStartY = rawY
                    leftVolumeStart = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    return true
                }
                cursorView.visibility = View.VISIBLE
                updateCursor()
                return true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                if (activeSide == Side.LEFT_VOLUME) { adjustVolume(rawY); return true }
                if (activeSide == Side.NONE) {
                    activeSide = classifyInputSide(event, logicalWidth)
                    if (activeSide == Side.LEFT_VOLUME) {
                        leftVolumeStartY = rawY
                        leftVolumeStart = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        adjustVolume(rawY)
                        return true
                    }
                }
                val dx = localX - lastInputX
                val dy = rawY - lastInputY
                if (abs(dx) < 0.35f && abs(dy) < 0.35f) return true
                preMoveCursorX = cursorX; preMoveCursorY = cursorY
                moveCursor(dx, dy, logicalWidth)
                lastMoveDist = hypot(cursorX - preMoveCursorX, cursorY - preMoveCursorY)
                lastMoveTimeMs = event.eventTime
                lastInputX = localX; lastInputY = rawY
                updateEdgePull(dx, logicalWidth)
                if (contentInteractionBlocked?.invoke() == true) stopEdgeScroll() else updateEdgeScroll()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_HOVER_EXIT -> {
                if (activeSide == Side.LEFT_VOLUME) { activeSide = Side.NONE; return true }
                // Capacitive liftoff jump: undo a final micro-move so the cursor
                // stays put on release.
                if (activeSide == Side.RIGHT_CURSOR && lastMoveTimeMs != 0L &&
                    event.eventTime - lastMoveTimeMs <= LIFT_JUMP_WINDOW_MS &&
                    lastMoveDist <= LIFT_JUMP_MAX_PX
                ) {
                    cursorX = preMoveCursorX; cursorY = preMoveCursorY
                    updateCursor()
                }
                val moved = abs(localX - downInputX) > touchSlop || abs(rawY - downInputY) > touchSlop
                val ended = event.actionMasked == MotionEvent.ACTION_UP
                if (!moved && ended) onTapUp()
                currentInputUsesMirroredCoordinates = false
                stopEdgeScroll()
                leftPullAccum = 0f; leftPullFired = false
                rightPullAccum = 0f; rightPullFired = false
                activeSide = Side.NONE
                return true
            }
        }
        return true
    }

    /** Unified tap entry for touch taps and temple-key presses. */
    private fun onTapUp() {
        // A consumed tap (one that stopped speech or a recording) used to reset
        // the whole sequence, zeroing tapCount AND lastTapUpTime — so the very
        // next tap started a fresh count of one and resolved to a page CLICK.
        // The effect: while the app is speaking, "double-tap to talk" is
        // unavailable precisely when it invites you to tap, and the second tap
        // lands on whatever is under the cursor instead. Keep the sequence
        // running and just remember the first tap was already spent.
        val consumed = tapInterceptor?.invoke() == true
        val now = SystemClock.uptimeMillis()
        val sincePrev = now - lastTapUpTime
        lastTapUpTime = now
        // Continue the sequence if this tap is inside the window, else start fresh.
        tapCount = if (multiTapRunnable != null && sincePrev in DOUBLE_TAP_MIN_GAP_MS..DOUBLE_TAP_WINDOW_MS)
            tapCount + 1 else 1
        if (consumed) consumedTap = true
        multiTapRunnable?.let { removeCallbacks(it) }
        val r = Runnable {
            multiTapRunnable = null
            val n = tapCount
            val spent = consumedTap
            tapCount = 0
            consumedTap = false
            when {
                n >= 3 -> tripleTapHandler?.invoke()
                n == 2 -> doubleTapHandler?.invoke()
                // A lone tap that already did its job must not also click the page.
                spent -> {}
                else -> performCursorClick()
            }
        }
        multiTapRunnable = r
        // Wait one window after the latest tap so a further tap can extend the count.
        postDelayed(r, DOUBLE_TAP_WINDOW_MS + 20L)
    }

    private fun cancelMultiTap() {
        multiTapRunnable?.let { removeCallbacks(it) }
        multiTapRunnable = null
        tapCount = 0
        lastTapUpTime = 0L
    }

    private fun performCursorClick() {
        val now = SystemClock.uptimeMillis()
        if (now - lastClickTime < CLICK_DEBOUNCE_MS) return
        lastClickTime = now
        if (logicalClickHandler?.invoke(cursorX, cursorY) == true) return
        clickAtCursor()
    }

    private fun clickAtCursor() {
        val target = webView ?: return
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, cursorX, cursorY, 0)
        target.dispatchTouchEvent(down); down.recycle()
        postDelayed({
            val up = MotionEvent.obtain(t, t + 48L, MotionEvent.ACTION_UP, cursorX, cursorY, 0)
            target.dispatchTouchEvent(up); up.recycle()
        }, 48L)
    }

    private fun classifyInputSide(event: MotionEvent, logicalWidth: Int): Side {
        val name = runCatching {
            event.device?.name ?: InputDevice.getDevice(event.deviceId)?.name
        }.getOrNull().orEmpty()
        return when {
            name.contains("cyttsp6", ignoreCase = true) -> Side.LEFT_VOLUME
            name.contains("cyttsp5", ignoreCase = true) -> Side.RIGHT_CURSOR
            event.isFromSource(InputDevice.SOURCE_MOUSE) -> Side.RIGHT_CURSOR
            event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE -> Side.RIGHT_CURSOR
            else -> if (event.getX(0) < logicalWidth) Side.LEFT_VOLUME else Side.RIGHT_CURSOR
        }
    }

    private fun isUnknownMirroredCoordinateEvent(event: MotionEvent, logicalWidth: Int): Boolean {
        val name = runCatching {
            event.device?.name ?: InputDevice.getDevice(event.deviceId)?.name
        }.getOrNull().orEmpty()
        if (name.contains("cyttsp5", true) || name.contains("cyttsp6", true)) return false
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
        ) return false
        return event.getX(0) >= logicalWidth
    }

    private fun adjustVolume(rawY: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val steps = ((leftVolumeStartY - rawY) / 34f).toInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (leftVolumeStart + steps).coerceIn(0, max), 0)
    }

    private fun moveCursor(dx: Float, dy: Float, logicalWidth: Int) {
        cursorX = (cursorX + dx * CURSOR_SENSITIVITY).coerceIn(1f, logicalWidth - 1f)
        cursorY = (cursorY + dy * CURSOR_SENSITIVITY).coerceIn(1f, height - 1f)
        updateCursor()
    }

    private fun updateCursor() {
        cursorView.cursorX = cursorX
        cursorView.cursorY = cursorY
        cursorView.invalidate()
        invalidate()
    }

    /**
     * Left-edge back as a PULL: only accumulates while the cursor is pinned at the
     * far-left edge AND the finger is still pushing left (dx<0). Crossing the
     * threshold fires back once; any rightward move resets the pull.
     */
    private fun updateEdgePull(dx: Float, logicalWidth: Int) {
        // Left edge → pull left (back / exit dim).
        if (cursorX <= EDGE_PULL_ZONE_PX && dx < 0f) {
            if (!leftPullFired) {
                leftPullAccum += -dx
                if (leftPullAccum >= EDGE_PULL_THRESHOLD_PX) {
                    leftPullAccum = 0f; leftPullFired = true
                    leftEdgeBackHandler?.invoke()
                }
            }
        } else if (dx > 0f) leftPullAccum = 0f
        // Right edge → pull right (enter dim).
        if (cursorX >= logicalWidth - EDGE_PULL_ZONE_PX && dx > 0f) {
            if (!rightPullFired) {
                rightPullAccum += dx
                if (rightPullAccum >= EDGE_PULL_THRESHOLD_PX) {
                    rightPullAccum = 0f; rightPullFired = true
                    rightEdgePullHandler?.invoke()
                }
            }
        } else if (dx < 0f) rightPullAccum = 0f
    }

    /** Vertical edge scrolling (the "no scroll bar" scroll). */
    private fun updateEdgeScroll() {
        val band = EDGE_SCROLL_BAND_PX
        val maxY = height.toFloat()
        val up = ((band - cursorY) / band).coerceIn(0f, 1f)
        val down = ((cursorY - (maxY - band)) / band).coerceIn(0f, 1f)
        edgeScrollDy = ((down - up) * EDGE_SCROLL_MAX_STEP_PX).toInt()
        if (edgeScrollDy == 0) { stopEdgeScroll(); return }
        if (!edgeScrollActive) {
            edgeScrollActive = true
            removeCallbacks(edgeScrollRunnable)
            post(edgeScrollRunnable)
        }
    }

    private fun stopEdgeScroll() {
        edgeScrollActive = false
        edgeScrollDy = 0
        removeCallbacks(edgeScrollRunnable)
    }

    private fun logicalViewportWidth(totalWidth: Int): Int = (totalWidth / 2).coerceAtLeast(0)

    private class CursorView(context: Context) : View(context) {
        var cursorX = 320f
        var cursorY = 240f
        private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2.5f; color = Color.BLACK
        }
        private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.5f; color = Color.WHITE
        }
        init { visibility = VISIBLE; isClickable = false; isFocusable = false }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawCircle(cursorX, cursorY, 7f, outer)
            canvas.drawCircle(cursorX, cursorY, 7f, inner)
            canvas.drawLine(cursorX - 12f, cursorY, cursorX + 12f, cursorY, outer)
            canvas.drawLine(cursorX, cursorY - 12f, cursorX, cursorY + 12f, outer)
            canvas.drawLine(cursorX - 12f, cursorY, cursorX + 12f, cursorY, inner)
            canvas.drawLine(cursorX, cursorY - 12f, cursorX, cursorY + 12f, inner)
        }
    }

    companion object {
        private const val CURSOR_SENSITIVITY = 0.86f
        private const val CLICK_DEBOUNCE_MS = 350L
        private const val LIFT_JUMP_WINDOW_MS = 140L
        private const val LIFT_JUMP_MAX_PX = 48f
        private const val EDGE_SCROLL_BAND_PX = 44f
        // Left-edge back PULL: cursor must be within ZONE of the edge and the user
        // must push a cumulative THRESHOLD of leftward travel against it.
        private const val EDGE_PULL_ZONE_PX = 8f
        private const val EDGE_PULL_THRESHOLD_PX = 140f
        private const val EDGE_SCROLL_MAX_STEP_PX = 26f
        private const val EDGE_SCROLL_INTERVAL_MS = 33L
        // Double-tap timing per the X3 guide: gap 40–320 ms (40 ms floor filters
        // keycode echo of the same physical tap).
        private const val DOUBLE_TAP_MIN_GAP_MS = 40L
        private const val DOUBLE_TAP_WINDOW_MS = 320L
    }
}
