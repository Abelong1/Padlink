package com.meow.padlink

import android.content.Context
import android.graphics.*
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Layout (landscape):
 *  L2/L1 ............ SELECT HOME START ............ R1/R2
 *  D-PAD (menus)                                   Y
 *                                               X     B (Back)
 *        LEFT STICK            RIGHT STICK         A (OK)
 */
class PadView(ctx: Context, private val pad: HidGamepad) : View(ctx) {

    private data class Btn(val label: String, val sub: String, val bit: Int, val rect: RectF,
                           val round: Boolean, val color: Int)
    private data class Stick(val center: PointF, val radius: Float, val left: Boolean,
                             var knob: PointF = PointF(center.x, center.y))

    private val bg = Color.parseColor("#1E1612")
    private val amber = Color.parseColor("#F2A93B")
    private val surface = Color.parseColor("#3A2A20")
    private val text = Color.parseColor("#F5E6D3")

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = text; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = text; textAlign = Paint.Align.CENTER
    }

    private val buttons = mutableListOf<Btn>()
    private val sticks = mutableListOf<Stick>()
    private val pointerBtn = HashMap<Int, Btn>()
    private val pointerStick = HashMap<Int, Stick>()

    // D-pad
    private val dpadCenter = PointF()
    private var dpadSize = 0f          // half-width of the whole cross
    private var dpadPointer = -1
    private var dpadDir = 8

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        buttons.clear(); sticks.clear()
        val u = min(w, h) / 10f
        textPaint.textSize = u * 0.45f
        subPaint.textSize = u * 0.28f

        // D-pad (upper-left) — main control for TV menus
        dpadCenter.set(w * 0.16f, h * 0.47f)
        dpadSize = u * 2.0f

        // Sticks (bottom)
        sticks += Stick(PointF(w * 0.40f, h * 0.76f), u * 1.6f, true)
        sticks += Stick(PointF(w * 0.60f, h * 0.76f), u * 1.6f, false)

        // ABXY diamond — A = OK/Select, B = Back on Android TV
        val cx = w * 0.84f; val cy = h * 0.55f; val r = u * 0.95f; val gap = u * 1.6f
        fun round(label: String, sub: String, bit: Int, x: Float, y: Float, c: Int) =
            Btn(label, sub, bit, RectF(x - r, y - r, x + r, y + r), true, c)
        buttons += round("A", "OK", HidGamepad.A, cx, cy + gap, Color.parseColor("#4E9A52"))
        buttons += round("B", "BACK", HidGamepad.B, cx + gap, cy, Color.parseColor("#C0503F"))
        buttons += round("X", "", HidGamepad.X, cx - gap, cy, Color.parseColor("#3F78B8"))
        buttons += round("Y", "", HidGamepad.Y, cx, cy - gap, Color.parseColor("#C98A22"))

        // Shoulders
        val sh = u * 1.1f; val sw = w * 0.14f; val top = u * 1.6f
        buttons += Btn("L2", "", HidGamepad.L2, RectF(u * 0.4f, top, u * 0.4f + sw, top + sh), false, surface)
        buttons += Btn("L1", "", HidGamepad.L1, RectF(u * 0.6f + sw, top, u * 0.6f + sw * 2, top + sh), false, surface)
        buttons += Btn("R1", "", HidGamepad.R1, RectF(w - u * 0.6f - sw * 2, top, w - u * 0.6f - sw, top + sh), false, surface)
        buttons += Btn("R2", "", HidGamepad.R2, RectF(w - u * 0.4f - sw, top, w - u * 0.4f, top + sh), false, surface)

        // Center row
        val bw = u * 1.7f; val bh = u * 0.85f; val my = h * 0.42f
        buttons += Btn("SELECT", "", HidGamepad.SELECT, RectF(w / 2f - bw * 1.6f, my, w / 2f - bw * 0.6f, my + bh), false, surface)
        buttons += Btn("HOME", "", HidGamepad.HOME, RectF(w / 2f - bw / 2, my, w / 2f + bw / 2, my + bh), false, surface)
        buttons += Btn("START", "", HidGamepad.START, RectF(w / 2f + bw * 0.6f, my, w / 2f + bw * 1.6f, my + bh), false, surface)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(bg)
        drawDpad(c)
        for (s in sticks) {
            paint.color = surface; c.drawCircle(s.center.x, s.center.y, s.radius, paint)
            paint.color = amber; c.drawCircle(s.knob.x, s.knob.y, s.radius * 0.42f, paint)
        }
        val pressed = pointerBtn.values.toSet()
        for (b in buttons) {
            val on = b in pressed
            paint.color = if (on) text else b.color
            if (b.round) c.drawOval(b.rect, paint) else c.drawRoundRect(b.rect, 20f, 20f, paint)
            textPaint.color = if (on) bg else text
            subPaint.color = textPaint.color
            val sub = if (pad.tvMode) HidGamepad.TV_KEYS[b.bit]?.second ?: "" else ""
            if (sub.isEmpty()) {
                c.drawText(b.label, b.rect.centerX(), b.rect.centerY() + textPaint.textSize / 3, textPaint)
            } else {
                c.drawText(b.label, b.rect.centerX(), b.rect.centerY(), textPaint)
                c.drawText(sub, b.rect.centerX(), b.rect.centerY() + subPaint.textSize * 1.2f, subPaint)
            }
        }
    }

    private fun drawDpad(c: Canvas) {
        val s = dpadSize; val t = s / 3f; val x = dpadCenter.x; val y = dpadCenter.y
        // arms: up, right, down, left with hat codes 0,2,4,6
        val arms = listOf(
            0 to RectF(x - t, y - s, x + t, y - t),
            2 to RectF(x + t, y - t, x + s, y + t),
            4 to RectF(x - t, y + t, x + t, y + s),
            6 to RectF(x - s, y - t, x - t, y + t)
        )
        paint.color = surface
        c.drawRect(x - t, y - t, x + t, y + t, paint)
        for ((code, r) in arms) {
            paint.color = if (isDirActive(code)) amber else surface
            c.drawRoundRect(r, 16f, 16f, paint)
        }
        // arrows
        paint.color = text
        val a = t * 0.45f
        fun tri(cx: Float, cy: Float, dx: Float, dy: Float) {
            val p = Path()
            p.moveTo(cx + dx * a, cy + dy * a)
            p.lineTo(cx - dy * a - dx * a * 0.5f, cy + dx * a - dy * a * 0.5f)
            p.lineTo(cx + dy * a - dx * a * 0.5f, cy - dx * a - dy * a * 0.5f)
            p.close(); c.drawPath(p, paint)
        }
        tri(x, y - s * 0.66f, 0f, -1f); tri(x + s * 0.66f, y, 1f, 0f)
        tri(x, y + s * 0.66f, 0f, 1f); tri(x - s * 0.66f, y, -1f, 0f)
    }

    private fun isDirActive(code: Int) = when (code) {
        0 -> dpadDir in setOf(7, 0, 1)
        2 -> dpadDir in setOf(1, 2, 3)
        4 -> dpadDir in setOf(3, 4, 5)
        6 -> dpadDir in setOf(5, 6, 7)
        else -> false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                down(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                val id = e.getPointerId(i)
                pointerStick[id]?.let { moveStick(it, e.getX(i), e.getY(i)) }
                if (id == dpadPointer) updateDpad(e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> {
                (pointerBtn.keys + pointerStick.keys + listOf(dpadPointer)).toList().forEach { up(it) }
            }
        }
        invalidate()
        return true
    }

    private fun down(id: Int, x: Float, y: Float) {
        // D-pad first
        if (abs(x - dpadCenter.x) < dpadSize * 1.05f && abs(y - dpadCenter.y) < dpadSize * 1.05f) {
            dpadPointer = id; updateDpad(x, y); return
        }
        buttons.firstOrNull { it.rect.contains(x, y) }?.let {
            pointerBtn[id] = it; pad.setButton(it.bit, true)
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); return
        }
        sticks.firstOrNull { hypot(x - it.center.x, y - it.center.y) < it.radius * 1.4f }?.let {
            pointerStick[id] = it; moveStick(it, x, y)
        }
    }

    /** Direction from finger position; dead zone in the middle; slide between arms. */
    private fun updateDpad(x: Float, y: Float) {
        val dx = x - dpadCenter.x; val dy = y - dpadCenter.y
        val dead = dpadSize * 0.18f
        val dir = if (hypot(dx, dy) < dead) 8 else {
            val ax = abs(dx); val ay = abs(dy)
            when {
                ax > ay * 2.2f -> if (dx > 0) 2 else 6          // pure left/right
                ay > ax * 2.2f -> if (dy > 0) 4 else 0          // pure up/down
                dx > 0 && dy < 0 -> 1
                dx > 0 && dy > 0 -> 3
                dx < 0 && dy > 0 -> 5
                else -> 7
            }
        }
        if (dir != dpadDir) {
            dpadDir = dir
            pad.setDpad(dir)
            if (dir != 8) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    private fun up(id: Int) {
        if (id == dpadPointer) {
            dpadPointer = -1; dpadDir = 8; pad.setDpad(8)
        }
        pointerBtn.remove(id)?.let { pad.setButton(it.bit, false) }
        pointerStick.remove(id)?.let { s ->
            s.knob.set(s.center.x, s.center.y)
            if (s.left) pad.setLeftStick(0f, 0f) else pad.setRightStick(0f, 0f)
        }
    }

    private fun moveStick(s: Stick, x: Float, y: Float) {
        var dx = x - s.center.x; var dy = y - s.center.y
        val d = hypot(dx, dy)
        if (d > s.radius) { dx = dx / d * s.radius; dy = dy / d * s.radius }
        s.knob.set(s.center.x + dx, s.center.y + dy)
        val nx = dx / s.radius; val ny = dy / s.radius
        if (s.left) pad.setLeftStick(nx, ny) else pad.setRightStick(nx, ny)
    }
}
