package com.example.setasoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.View
import kotlin.math.min

/**
 * Botão redondo flutuante com o ícone desenhado em código
 * (não depende de emoji nem de arquivos de imagem).
 */
class FloatButton(
    context: Context,
    val kind: Kind,
    private val activeColor: Int
) : View(context) {

    enum class Kind { DRAW, BADGE, COLOR, UNDO, CLEAR, CALIBRATE }

    var active = false
        set(value) {
            field = value
            invalidate()
        }

    /** Usado só pelo botão de cor: mostra a cor atual. */
    var dotColor = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    private val d = resources.displayMetrics.density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        alpha = 235
        strokeWidth = 1.5f * d
    }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2f
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 11f
    }
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f - ring.strokeWidth

        // ativo: preenchido de limão com ícone escuro; inativo: preto com contorno limão
        val inkColor = if (active) 0xFF101010.toInt() else Color.WHITE
        ink.color = inkColor
        label.color = inkColor
        ring.color = if (active) Color.WHITE else 0xFFC8FF00.toInt()
        ring.alpha = 255
        fill.color = if (active) activeColor else 0xF2101010.toInt()
        c.drawCircle(cx, cy, r, fill)
        c.drawCircle(cx, cy, r, ring)

        // Ícones desenhados numa grade de 24x24
        val s = min(w, h) * 0.5f / 24f
        c.save()
        c.translate(cx - 12f * s, cy - 12f * s)
        c.scale(s, s)

        when (kind) {
            Kind.DRAW -> {
                path.reset()
                path.moveTo(4f, 20f)
                path.lineTo(5f, 15f)
                path.lineTo(15f, 5f)
                path.lineTo(19f, 9f)
                path.lineTo(9f, 19f)
                path.close()
                c.drawPath(path, ink)
                c.drawLine(13f, 7f, 17f, 11f, ink)
            }

            Kind.BADGE -> {
                c.drawCircle(12f, 12f, 9f, ink)
                val fm = label.fontMetrics
                c.drawText("!!", 12f, 12f - (fm.ascent + fm.descent) / 2f, label)
            }

            Kind.COLOR -> {
                fill.color = dotColor
                c.drawCircle(12f, 12f, 7.5f, fill)
                c.drawCircle(12f, 12f, 7.5f, ink)
            }

            Kind.UNDO -> {
                path.reset()
                path.moveTo(9f, 6f)
                path.lineTo(4f, 11f)
                path.lineTo(9f, 16f)
                path.moveTo(4f, 11f)
                path.lineTo(13f, 11f)
                path.quadTo(20f, 11f, 20f, 16f)
                path.quadTo(20f, 21f, 13f, 21f)
                path.lineTo(9f, 21f)
                c.drawPath(path, ink)
            }

            Kind.CLEAR -> {
                c.drawLine(5f, 7f, 19f, 7f, ink)
                path.reset()
                path.moveTo(9f, 7f)
                path.lineTo(9f, 4f)
                path.lineTo(15f, 4f)
                path.lineTo(15f, 7f)
                path.moveTo(7f, 7f)
                path.lineTo(8f, 20f)
                path.lineTo(16f, 20f)
                path.lineTo(17f, 7f)
                c.drawPath(path, ink)
                c.drawLine(10f, 11f, 10f, 16f, ink)
                c.drawLine(14f, 11f, 14f, 16f, ink)
            }

            Kind.CALIBRATE -> {
                path.reset()
                path.moveTo(4f, 9f); path.lineTo(4f, 4f); path.lineTo(9f, 4f)
                path.moveTo(15f, 4f); path.lineTo(20f, 4f); path.lineTo(20f, 9f)
                path.moveTo(20f, 15f); path.lineTo(20f, 20f); path.lineTo(15f, 20f)
                path.moveTo(9f, 20f); path.lineTo(4f, 20f); path.lineTo(4f, 15f)
                c.drawPath(path, ink)
                fill.color = inkColor
                c.drawCircle(12f, 12f, 1.6f, fill)
            }
        }
        c.restore()
    }
}
