package com.meow.padlink

import android.content.Context
import android.graphics.*
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/** Full-screen multi-touch controller: left stick, right stick, ABXY, bumpers, Select/Start/Home. */
class PadView(ctx: Context, private val pad: HidGamepad) : View(ctx) {

    private data class Btn(val label: String, val bit: Int, val rect: RectF, val round: Boolean, val color: Int)
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

    private val buttons = mutableListOf<Btn>()
    private val sticks = mutableListOf<Stick>()
    private val pointerBtn = HashMap<Int, Btn>()
    private val pointerStick = HashMap<Int, Stick>()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        buttons.clear(); sticks.clear()
        val u = min(w, h) / 10f
        textPaint.textSize = u * 0.45f

        // Sticks
        sticks += Stick(PointF(w * 0.18f, h * 0.62f), u * 2.2f, true)
        sticks += Stick(PointF(w * 0.62f, h * 0.78f), u * 1.4f, false)

        // ABXY diamond (Android layout: A bottom, B right, X left, Y top)
        val cx = w * 0.84f; val cy = h * 0.52f; val r = u * 0.85f; val gap = u * 1.5f
        fun round(label: String, bit: Int, x: Float, y: Float, c: Int) =
            Btn(label, bit, RectF(x - r, y - r, x + r, y + r), true, c)
        buttons += round("A", HidGamepad.A, cx, cy + gap, Color.parseColor("#6FBF73"))
        buttons += round("B", HidGamepad.B, cx + gap, cy, Color.parseColor("#E06A5A"))
        buttons += round("X", HidGamepad.X, cx - gap, cy, Color.parseColor("#5A9BE0"))
        buttons += round("Y", HidGamepad.Y, cx, cy - gap, amber)

        // Shoulders
        val sh = u * 1.1f; val sw = w * 0.16f
        buttons += Btn("L2", HidGamepad.L2, RectF(u * 0.4f, u * 0.4f, u * 0.4f + sw, u * 0.4f + sh), false, surface)
        buttons += Btn("L1", HidGamepad.L1, RectF(u * 0.4f, u * 1.8f, u * 0.4f + sw, u * 1.8f + sh), false, surface)
        buttons += Btn("R2", HidGamepad.R2, RectF(w - u * 0.4f - sw, u * 0.4f, w - u * 0.4f, u * 0.4f + sh), false, surface)
        buttons += Btn("R1", HidGamepad.R1, RectF(w - u * 0.4f - sw, u * 1.8f, w - u * 0.4f, u * 1.8f + sh), false, surface)

        // Center
        val bw = u * 1.6f; val bh = u * 0.8f; val my = h * 0.42f
        buttons += Btn("SELECT", HidGamepad.SELECT, RectF(w / 2f - bw * 1.7f, my, w / 2f - bw * 0.7f, my + bh), false, surface)
        buttons += Btn("HOME", HidGamepad.HOME, RectF(w / 2f - bw / 2, my, w / 2f + bw / 2, my + bh), false, surface)
        buttons += Btn("START", HidGamepad.START, RectF(w / 2f + bw * 0.7f, my, w / 2f + bw * 1.7f, my + bh), false, surface)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(bg)
        for (s in sticks) {
            paint.color = surface; c.drawCircle(s.center.x, s.center.y, s.radius, paint)
            paint.color = amber; c.drawCircle(s.knob.x, s.knob.y, s.radius * 0.42f, paint)
        }
        val pressed = pointerBtn.values.toSet()
        for (b in buttons) {
            paint.color = if (b in pressed) text else b.color
            if (b.round) c.drawOval(b.rect, paint) else c.drawRoundRect(b.rect, 20f, 20f, paint)
            textPaint.color = if (b in pressed) bg else text
            c.drawText(b.label, b.rect.centerX(), b.rect.centerY() + textPaint.textSize / 3, textPaint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex; val id = e.getPointerId(i)
                down(id, e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                val id = e.getPointerId(i)
                pointerStick[id]?.let { moveStick(it, e.getX(i), e.getY(i)) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> { pointerBtn.keys.toList().forEach { up(it) }; pointerStick.keys.toList().forEach { up(it) } }
        }
        invalidate()
        return true
    }

    private fun down(id: Int, x: Float, y: Float) {
        buttons.firstOrNull { it.rect.contains(x, y) }?.let {
            pointerBtn[id] = it; pad.setButton(it.bit, true)
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); return
        }
        sticks.firstOrNull { hypot(x - it.center.x, y - it.center.y) < it.radius * 1.4f }?.let {
            pointerStick[id] = it; moveStick(it, x, y)
        }
    }

    private fun up(id: Int) {
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
