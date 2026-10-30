package com.example.starclaim.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.starclaim.game.BOT
import com.example.starclaim.game.Fleet
import com.example.starclaim.game.GameEngine
import com.example.starclaim.game.NEUTRAL
import com.example.starclaim.game.PLAYER
import com.example.starclaim.game.Planet
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class MapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var engine: GameEngine? = null
        set(value) {
            field = value
            invalidate()
        }

    var selected: Int = -1
    var targeting: Boolean = false
    var onPlanetTap: ((Int) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val pad = 54f * d
    private val planetR = 25f * d
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private val arcRect = RectF()

    private val stars = List(90) {
        Triple(Random(it * 7919).nextFloat(), Random(it * 104729).nextFloat(),
            Random(it * 1543).nextFloat() * 1.6f + 0.5f)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val avail = min(w - 2 * pad, h - 2 * pad).coerceAtLeast(1f)
        scale = avail
        offsetX = (w - avail) / 2f
        offsetY = (h - avail) / 2f
    }

    private fun px(x: Float) = offsetX + x * scale
    private fun py(y: Float) = offsetY + y * scale

    private fun ownerColor(owner: Int): Int = when (owner) {
        PLAYER -> 0xFF22C55E.toInt()
        BOT -> 0xFFEF4444.toInt()
        else -> 0xFF64748B.toInt()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val e = engine ?: return

        canvas.drawColor(0xFF070B14.toInt())
        // starfield
        fill.style = Paint.Style.FILL
        for ((sx, sy, sr) in stars) {
            fill.color = 0x55FFFFFF
            canvas.drawCircle(sx * width, sy * height, sr * d, fill)
        }

        // hyperlanes
        line.color = 0x332F4A66
        line.strokeWidth = 2.2f * d
        for (p in e.planets) {
            for (l in p.links) {
                if (l <= p.id) continue
                val q = e.planets[l]
                canvas.drawLine(px(p.x), py(p.y), px(q.x), py(q.y), line)
            }
        }

        // arrows from your selected planet to its neighbours (where the fleet can go)
        val sel = e.planets.getOrNull(selected)
        if (!targeting && sel != null && sel.owner == PLAYER && e.orbit[sel.id][PLAYER] > 0.5f) {
            for (l in sel.links) {
                val q = e.planets[l]
                arrow(canvas, px(sel.x), py(sel.y), px(q.x), py(q.y), 0xAA22C55E.toInt())
            }
        }

        // highlighted neighbours while targeting
        if (targeting && selected in e.planets.indices) {
            line.color = 0xCC38BDF8.toInt()
            line.strokeWidth = 4f * d
            for (l in e.planets[selected].links) {
                val q = e.planets[l]
                canvas.drawLine(px(e.planets[selected].x), py(e.planets[selected].y), px(q.x), py(q.y), line)
            }
        }

        // fleets in transit
        for (f in e.fleets) {
            drawFleet(canvas, e, f)
        }

        // planets
        for (p in e.planets) drawPlanet(canvas, e, p)
    }

    private fun arrow(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int) {
        val dx = x2 - x1
        val dy = y2 - y1
        val len = hypot(dx, dy)
        if (len < 1f) return
        val ux = dx / len
        val uy = dy / len
        val sx = x1 + ux * (planetR + 6 * d)
        val sy = y1 + uy * (planetR + 6 * d)
        val ex = x2 - ux * (planetR + 9 * d)
        val ey = y2 - uy * (planetR + 9 * d)

        line.color = color
        line.strokeWidth = 3f * d
        canvas.drawLine(sx, sy, ex, ey, line)

        fill.style = Paint.Style.FILL
        fill.color = color
        val head = 11f * d
        path.reset()
        path.moveTo(ex, ey)
        path.lineTo(ex - ux * head - uy * head * 0.6f, ey - uy * head + ux * head * 0.6f)
        path.lineTo(ex - ux * head + uy * head * 0.6f, ey - uy * head - ux * head * 0.6f)
        path.close()
        canvas.drawPath(path, fill)
    }

    private fun drawPlanet(canvas: Canvas, e: GameEngine, p: Planet) {
        val cx = px(p.x)
        val cy = py(p.y)
        val color = ownerColor(p.owner)

        // glow
        fill.style = Paint.Style.FILL
        fill.color = (color and 0x00FFFFFF) or 0x33000000
        canvas.drawCircle(cx, cy, planetR * 1.55f, fill)

        // body
        fill.color = color
        canvas.drawCircle(cx, cy, planetR, fill)

        // inner shading
        fill.color = 0x33000000
        canvas.drawCircle(cx + planetR * 0.28f, cy + planetR * 0.3f, planetR * 0.72f, fill)

        // outline
        line.color = 0x99FFFFFF.toInt()
        line.strokeWidth = 1.6f * d
        canvas.drawCircle(cx, cy, planetR, line)

        // siege / capture ring
        val captureBy = p.captureBy
        if (p.capture > 0.5f && captureBy >= 0) {
            line.color = ownerColor(captureBy)
            line.strokeWidth = 4.5f * d
            arcRect.set(cx - planetR - 6 * d, cy - planetR - 6 * d, cx + planetR + 6 * d, cy + planetR + 6 * d)
            canvas.drawArc(arcRect, -90f, 360f * (p.capture / 100f), false, line)
        }

        // garrison
        label.color = Color.WHITE
        label.textSize = 15f * d
        val g = p.garrison.toInt().toString()
        canvas.drawText(g, cx, cy + 5 * d, label)

        // level dot
        if (p.level > 1) {
            fill.color = 0xFFFFD166.toInt()
            for (i in 0 until min(p.level, 4)) {
                canvas.drawCircle(cx - 9 * d + i * 6 * d, cy + planetR + 9 * d, 2.2f * d, fill)
            }
        }

        // orbit power badge
        val orbitP = e.orbit[p.id]
        val ownOrbit = if (p.owner >= 0) orbitP[p.owner] else 0f
        val enemyOrbit = (0..1).filter { it != p.owner }.sumOf { orbitP[it].toDouble() }.toFloat()
        if (ownOrbit > 0.5f) {
            badge(canvas, cx - planetR * 1.5f, cy - planetR * 1.5f, ownOrbit, ownerColor(p.owner))
        }
        if (enemyOrbit > 0.5f) {
            val enemy = (0..1).first { it != p.owner && orbitP[it] > 0.5f }
            badge(canvas, cx + planetR * 1.5f, cy - planetR * 1.5f, enemyOrbit, ownerColor(enemy))
        }

        // selection
        if (p.id == selected) {
            line.color = 0xFFFFFFFF.toInt()
            line.strokeWidth = 3f * d
            canvas.drawCircle(cx, cy, planetR + 10 * d, line)
        }
    }

    private fun badge(canvas: Canvas, x: Float, y: Float, value: Float, color: Int) {
        fill.color = color
        fill.style = Paint.Style.FILL
        canvas.drawCircle(x, y, 12f * d, fill)
        small.color = Color.BLACK
        small.textSize = 10.5f * d
        canvas.drawText(value.toInt().toString(), x, y + 3.6f * d, small)
    }

    private fun drawFleet(canvas: Canvas, e: GameEngine, f: Fleet) {
        val a = e.planets[f.from]
        val b = e.planets[f.to]
        val t = f.progress(e.time)
        val x = px(a.x + (b.x - a.x) * t)
        val y = py(a.y + (b.y - a.y) * t)
        val angle = kotlin.math.atan2((b.y - a.y) * scale, (b.x - a.x) * scale)

        fill.style = Paint.Style.FILL
        fill.color = ownerColor(f.owner)
        path.reset()
        val r = 9f * d
        path.moveTo(x + cos(angle) * r, y + sin(angle) * r)
        path.lineTo(x + cos(angle + 2.5f) * r, y + sin(angle + 2.5f) * r)
        path.lineTo(x + cos(angle - 2.5f) * r, y + sin(angle - 2.5f) * r)
        path.close()
        canvas.drawPath(path, fill)

        small.color = 0xCCFFFFFF.toInt()
        small.textSize = 9f * d
        canvas.drawText(f.power.toInt().toString(), x, y - 12 * d, small)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val e = engine ?: return true
        val x = event.x
        val y = event.y
        var best = -1
        var bestD = Float.MAX_VALUE
        for (p in e.planets) {
            val dist = hypot(px(p.x) - x, py(p.y) - y)
            if (dist < planetR * 1.9f && dist < bestD) {
                bestD = dist
                best = p.id
            }
        }
        if (best >= 0) {
            performClick()
            onPlanetTap?.invoke(best)
        }
        return true
    }
}
