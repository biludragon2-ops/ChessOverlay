package com.example.setasoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Camada que cobre a tela toda. Desenha setas, círculos e o selo "!!" nas casas do
 * tabuleiro e, no modo calibrar, deixa você posicionar o quadrado sobre o tabuleiro.
 */
class BoardView(context: Context) : View(context) {

    enum class Mode { OFF, DRAW, CALIBRATE }
    enum class Tool { ARROW, BADGE }

    private data class Shape(
        val f1: Int, val r1: Int, val f2: Int, val r2: Int,
        val color: Int, val badge: Boolean = false
    )

    val colors = intArrayOf(
        0xFF15781B.toInt(), // verde
        0xFF882020.toInt(), // vermelho
        0xFF003088.toInt(), // azul
        0xFFE68F00.toInt()  // laranja
    )

    /** Sobe a cada mudança visual. O BoardWatcher usa isso para refazer a comparação. */
    var version = 0
        private set

    var mode: Mode = Mode.OFF
        set(value) {
            field = value
            start = null
            cur = null
            dragMode = 0
            bump()
        }

    var tool: Tool = Tool.ARROW
        set(value) {
            field = value
            start = null
            cur = null
            bump()
        }

    var colorIndex = 0
        set(value) {
            field = value.coerceIn(0, colors.size - 1)
            bump()
        }

    /** Chamado quando o usuário termina de ajustar o tabuleiro (left, top, size em px). */
    var onBoardChanged: ((Float, Float, Float) -> Unit)? = null

    private var boardLeft = 0f
    private var boardTop = 0f
    private var boardSize = 0f

    private val shapes = mutableListOf<Shape>()
    private var start: IntArray? = null
    private var cur: IntArray? = null

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 13f * density
        textAlign = Paint.Align.CENTER
    }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val path = Path()
    private val rect = RectF()

    // calibração: 0 nada, 1 mover, 2 redimensionar
    private var dragMode = 0
    private var lastX = 0f
    private var lastY = 0f

    // ---------------- API usada pelo serviço ----------------

    val hasShapes: Boolean get() = shapes.isNotEmpty()

    /** Verdadeiro enquanto o dedo está desenhando ou ajustando a calibração. */
    val busy: Boolean get() = start != null || dragMode != 0

    fun boardLeft() = boardLeft
    fun boardTop() = boardTop
    fun boardSize() = boardSize

    fun bump() {
        version++
        invalidate()
    }

    fun setBoard(left: Float, top: Float, size: Float) {
        boardLeft = left
        boardTop = top
        boardSize = size
        bump()
    }

    fun clearShapes() {
        shapes.clear()
        start = null
        cur = null
        bump()
    }

    fun undo() {
        if (shapes.isNotEmpty()) shapes.removeAt(shapes.size - 1)
        bump()
    }

    /** Casas (índice r*8+f) tocadas por setas, círculos ou selos: não servem para detectar lances. */
    fun coveredCells(): BooleanArray {
        val covered = BooleanArray(64)
        fun mark(x: Float, y: Float) {
            val f = x.toInt()
            val r = y.toInt()
            if (f in 0..7 && r in 0..7) covered[r * 8 + f] = true
        }
        for (s in shapes) {
            val x1 = s.f1 + 0.5f
            val y1 = s.r1 + 0.5f
            val x2 = s.f2 + 0.5f
            val y2 = s.r2 + 0.5f
            if (s.badge || (s.f1 == s.f2 && s.r1 == s.r2)) {
                mark(x1, y1)
                continue
            }
            val len = hypot(x2 - x1, y2 - y1)
            val px = -(y2 - y1) / len
            val py = (x2 - x1) / len
            val steps = (len * 6).toInt().coerceAtLeast(1)
            for (i in 0..steps) {
                val t = i / steps.toFloat()
                val x = x1 + (x2 - x1) * t
                val y = y1 + (y2 - y1) * t
                mark(x, y)
                mark(x + px * 0.3f, y + py * 0.3f)
                mark(x - px * 0.3f, y - py * 0.3f)
            }
        }
        return covered
    }

    // ---------------- Desenho ----------------

    override fun onDraw(canvas: Canvas) {
        if (boardSize <= 0f) return

        when (mode) {
            Mode.CALIBRATE -> drawCalibration(canvas)
            Mode.DRAW -> drawBorder(canvas)
            Mode.OFF -> Unit
        }

        canvas.save()
        canvas.translate(boardLeft, boardTop)
        val sc = boardSize / 8f
        canvas.scale(sc, sc)
        shapes.forEach { if (!it.badge) drawShape(canvas, it, 0.8f) }

        val s = start
        val e = cur
        if (s != null && e != null && tool == Tool.ARROW) {
            val color = colors[colorIndex]
            // realça a casa de origem
            paint.style = Paint.Style.FILL
            paint.color = color
            paint.alpha = 70
            canvas.drawRect(s[0].toFloat(), s[1].toFloat(), s[0] + 1f, s[1] + 1f, paint)
            drawShape(canvas, Shape(s[0], s[1], e[0], e[1], color), 0.55f)
        }
        canvas.restore()

        // selos por cima das setas, desenhados em pixels
        shapes.forEach { if (it.badge) drawBadge(canvas, it.f1, it.r1) }
    }

    /** Selo "!!" ciano no canto superior direito da casa. */
    private fun drawBadge(c: Canvas, f: Int, r: Int) {
        val cell = boardSize / 8f
        val rad = cell * 0.28f
        val cx = boardLeft + (f + 0.70f) * cell
        val cy = boardTop + (r + 0.30f) * cell

        paint.style = Paint.Style.FILL
        paint.color = 0x55000000
        c.drawCircle(cx, cy + rad * 0.12f, rad * 1.05f, paint) // sombra
        paint.color = 0xFFFFFFFF.toInt()
        c.drawCircle(cx, cy, rad, paint)                       // anel branco
        paint.color = 0xFF00B7C9.toInt()
        c.drawCircle(cx, cy, rad * 0.84f, paint)               // miolo ciano

        badgeText.textSize = rad * 1.05f
        val fm = badgeText.fontMetrics
        c.drawText("!!", cx, cy - (fm.ascent + fm.descent) / 2f, badgeText)
    }

    /** Contorno fino mostrando que o modo desenho está ligado. */
    private fun drawBorder(c: Canvas) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = 0xFFC8FF00.toInt()
        paint.alpha = 170
        rect.set(boardLeft, boardTop, boardLeft + boardSize, boardTop + boardSize)
        c.drawRoundRect(rect, 4f * density, 4f * density, paint)
    }

    private fun drawCalibration(c: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = 0x33C8FF00
        c.drawRect(boardLeft, boardTop, boardLeft + boardSize, boardTop + boardSize, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = 0x99FFFFFF.toInt()
        val cell = boardSize / 8f
        for (i in 0..8) {
            c.drawLine(boardLeft + i * cell, boardTop, boardLeft + i * cell, boardTop + boardSize, paint)
            c.drawLine(boardLeft, boardTop + i * cell, boardLeft + boardSize, boardTop + i * cell, paint)
        }
        paint.strokeWidth = 2.5f * density
        paint.color = 0xFFC8FF00.toInt()
        c.drawRect(boardLeft, boardTop, boardLeft + boardSize, boardTop + boardSize, paint)

        // dica no centro
        val text = "Mova o quadrado e ajuste pela bolinha"
        val tw = textPaint.measureText(text)
        val padH = 14f * density
        val padV = 10f * density
        val cx = boardLeft + boardSize / 2f
        val cy = boardTop + boardSize / 2f
        paint.style = Paint.Style.FILL
        paint.color = 0xE6101010.toInt()
        rect.set(cx - tw / 2f - padH, cy - textPaint.textSize - padV / 2f, cx + tw / 2f + padH, cy + padV)
        c.drawRoundRect(rect, 12f * density, 12f * density, paint)
        c.drawText(text, cx, cy - textPaint.textSize * 0.1f, textPaint)

        // alça de redimensionar (canto inferior direito)
        val hx = boardLeft + boardSize
        val hy = boardTop + boardSize
        paint.style = Paint.Style.FILL
        paint.color = 0xFFC8FF00.toInt()
        c.drawCircle(hx, hy, 22f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = 0xFF101010.toInt()
        c.drawCircle(hx, hy, 22f * density, paint)
        paint.strokeCap = Paint.Cap.ROUND
        c.drawLine(hx - 7f * density, hy + 7f * density, hx + 7f * density, hy - 7f * density, paint)
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawShape(c: Canvas, s: Shape, alpha: Float) {
        val x1 = s.f1 + 0.5f
        val y1 = s.r1 + 0.5f
        val x2 = s.f2 + 0.5f
        val y2 = s.r2 + 0.5f
        paint.color = s.color
        paint.alpha = (alpha * 255).toInt()

        if (s.f1 == s.f2 && s.r1 == s.r2) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.08f
            c.drawCircle(x1, y1, 0.45f, paint)
            return
        }

        val dx = x2 - x1
        val dy = y2 - y1
        val len = hypot(dx, dy)
        val ux = dx / len
        val uy = dy / len
        val margin = 0.12f
        val head = 0.45f
        val half = 0.28f
        val tx = x2 - ux * margin
        val ty = y2 - uy * margin
        val bx = tx - ux * head
        val by = ty - uy * head
        val px = -uy
        val py = ux

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.15f
        paint.strokeCap = Paint.Cap.ROUND
        c.drawLine(x1, y1, bx, by, paint)

        paint.style = Paint.Style.FILL
        path.reset()
        path.moveTo(tx, ty)
        path.lineTo(bx + px * half, by + py * half)
        path.lineTo(bx - px * half, by - py * half)
        path.close()
        c.drawPath(path, paint)
        paint.strokeCap = Paint.Cap.BUTT
    }

    // ---------------- Toque ----------------

    private fun insideBoard(x: Float, y: Float) =
        x >= boardLeft && x <= boardLeft + boardSize && y >= boardTop && y <= boardTop + boardSize

    private fun cellAt(x: Float, y: Float): IntArray {
        val f = (((x - boardLeft) / boardSize) * 8).toInt().coerceIn(0, 7)
        val r = (((y - boardTop) / boardSize) * 8).toInt().coerceIn(0, 7)
        return intArrayOf(f, r)
    }

    private fun addShape(a: IntArray, b: IntArray) {
        val color = colors[colorIndex]
        val i = shapes.indexOfFirst {
            !it.badge && it.f1 == a[0] && it.r1 == a[1] && it.f2 == b[0] && it.r2 == b[1]
        }
        if (i >= 0) {
            val same = shapes[i].color == color
            shapes.removeAt(i)
            if (same) return // mesma forma e cor: remove
        }
        shapes.add(Shape(a[0], a[1], b[0], b[1], color))
    }

    private fun toggleBadge(cell: IntArray) {
        val i = shapes.indexOfFirst { it.badge && it.f1 == cell[0] && it.r1 == cell[1] }
        if (i >= 0) shapes.removeAt(i)
        else shapes.add(Shape(cell[0], cell[1], cell[0], cell[1], 0, badge = true))
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        return when (mode) {
            Mode.OFF -> false
            Mode.DRAW -> touchDraw(e)
            Mode.CALIBRATE -> touchCalibrate(e)
        }
    }

    private fun touchDraw(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!insideBoard(e.x, e.y)) return true
                start = cellAt(e.x, e.y)
                cur = start
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (start != null) {
                    cur = cellAt(e.x, e.y)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val s = start
                if (s != null) {
                    val end = cellAt(e.x, e.y)
                    if (tool == Tool.BADGE) {
                        // selo só vale se o dedo soltou na mesma casa
                        if (s[0] == end[0] && s[1] == end[1]) toggleBadge(s)
                    } else {
                        addShape(s, end)
                    }
                }
                start = null
                cur = null
                bump()
            }
            MotionEvent.ACTION_CANCEL -> {
                start = null
                cur = null
                invalidate()
            }
        }
        return true
    }

    private fun touchCalibrate(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hx = boardLeft + boardSize
                val hy = boardTop + boardSize
                dragMode = when {
                    hypot(e.x - hx, e.y - hy) < 48 * density -> 2
                    insideBoard(e.x, e.y) -> 1
                    else -> 0
                }
                lastX = e.x
                lastY = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastX
                val dy = e.y - lastY
                if (dragMode == 1) {
                    boardLeft += dx
                    boardTop += dy
                } else if (dragMode == 2) {
                    boardSize = max(80 * density, max(e.x - boardLeft, e.y - boardTop))
                }
                lastX = e.x
                lastY = e.y
                if (abs(dx) + abs(dy) > 0f) version++
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragMode != 0) onBoardChanged?.invoke(boardLeft, boardTop, boardSize)
                dragMode = 0
                bump()
            }
        }
        return true
    }
}
