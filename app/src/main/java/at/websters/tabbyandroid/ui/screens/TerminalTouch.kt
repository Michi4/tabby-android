package at.websters.tabbyandroid.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Touch routing for the terminal output (replaces `transformable`, which
 * swallowed every finger drag so history could never be finger-scrolled):
 *
 * - one finger drag → scrolls the output (with fling on release),
 * - two fingers → pinch-zooms the font (when [pinchZoomEnabled]),
 * - tap / long-press (no move past slop) → consumed NOTHING, so focus-tap
 *   and text-selection/long-press-extend keep working untouched.
 *
 * A drag that starts after long-press timeout is also left alone (selection
 * extend). Programmatic [ScrollState] users (follow-stick, find jump,
 * back-to-live button) are unaffected — they share the same state.
 */
fun Modifier.terminalTouch(
    scroll: ScrollState,
    flingScope: CoroutineScope,
    pinchZoomEnabled: () -> Boolean,
    onZoom: (zoomChange: Float) -> Unit,
    onUserDrag: () -> Unit = {},
): Modifier = composed {
    val latestZoom by rememberUpdatedState(onZoom)
    val latestPinch by rememberUpdatedState(pinchZoomEnabled)
    val latestDrag by rememberUpdatedState(onUserDrag)
    pointerInput(scroll) {
        val slop = viewConfiguration.touchSlop
        val longPressMs = viewConfiguration.longPressTimeoutMillis
        var flingJob: Job? = null
        awaitEachGesture {
            flingJob?.cancel()
            flingJob = null
            val down = awaitFirstDown(requireUnconsumed = false)
            var mode = TouchMode.Undecided
            var prevDist = 0f
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) {
                    // All fingers up.
                    if (mode == TouchMode.Scroll) {
                        val vy = tracker.calculateVelocity().y
                        if (abs(vy) > 200f) {
                            // Manual friction fling (platform feel, no extra
                            // APIs): decay velocity per frame until slow/edge.
                            flingJob = flingScope.launch {
                                var v = -vy
                                var lastT = withFrameNanos { it }
                                while (abs(v) > 50f) {
                                    val t = withFrameNanos { it }
                                    val dt = ((t - lastT) / 1e9).toFloat()
                                        .coerceIn(0.001f, 0.05f)
                                    lastT = t
                                    val moved = scroll.dispatchRawDelta(v * dt)
                                    if (moved == 0f) break
                                    v *= kotlin.math.exp(-3.5f * dt)
                                }
                            }
                        }
                        // Swallow the release so a drag never ends as a tap
                        // (which would pop the keyboard while reading history).
                        event.changes.forEach { it.consume() }
                    }
                    break
                }
                when {
                    pressed.size >= 2 -> {
                        if (mode != TouchMode.Pinch) {
                            mode = TouchMode.Pinch
                            flingJob?.cancel()
                            flingJob = null
                            prevDist = pinchDist(pressed[0].position, pressed[1].position)
                        } else {
                            val dist = pinchDist(pressed[0].position, pressed[1].position)
                            if (latestPinch() && prevDist > 0f && dist > 0f) {
                                val zoom = dist / prevDist
                                if (zoom.isFinite() && zoom != 1f) latestZoom(zoom)
                            }
                            prevDist = dist
                        }
                        pressed.forEach { it.consume() }
                    }
                    mode == TouchMode.Undecided -> {
                        val change = pressed[0]
                        val moved = (change.position - down.position).getDistance()
                        if (moved > slop) {
                            // Late drag = selection extend, not scroll.
                            if (change.uptimeMillis - down.uptimeMillis > longPressMs) break
                            mode = TouchMode.Scroll
                            latestDrag()
                        }
                    }
                    mode == TouchMode.Scroll -> {
                        val change = pressed[0]
                        tracker.addPosition(change.uptimeMillis, change.position)
                        val dy = (change.position - change.previousPosition).y
                        if (dy != 0f) scroll.dispatchRawDelta(-dy)
                        change.consume()
                    }
                    // Pinch with one finger lifted: hold position, no jump-scroll.
                }
            }
        }
    }
}

private enum class TouchMode { Undecided, Scroll, Pinch }

private fun pinchDist(a: Offset, b: Offset): Float =
    hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()
