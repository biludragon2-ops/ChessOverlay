package com.example.setasoverlay

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max

/**
 * Observa a imagem do tabuleiro (pela gravação de tela do Android) e apaga as setas
 * quando uma peça se mexe. Não analisa lances: só compara se as cores das casas mudaram.
 */
class BoardWatcher(
    private val context: Context,
    private val projection: MediaProjection,
    private val board: BoardView,
    private val onStopped: () -> Unit
) {
    companion object {
        private const val SCALE = 0.25f      // captura em 1/4 do tamanho (leve)
        private const val TICK_MS = 400L     // quantas vezes olha a tela
        private const val REBASE_MS = 500L   // espera após desenhar antes de guardar a referência
        private const val CELL_DIFF = 28     // diferença média de cor para uma casa "mudar" (0 a 255)
        private const val MIN_CELLS = 2      // casas mudadas para considerar que houve lance
    }

    private val handler = Handler(Looper.getMainLooper())
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null

    private var frame: ByteArray? = null
    private var fw = 0
    private var fh = 0
    private var rowStride = 0
    private var pixStride = 4

    private var screenW = 1
    private var screenH = 1

    private var baseline: IntArray? = null
    private var seenVersion = -1
    private var baselineDueAt = 0L
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                step()
            } catch (e: Exception) {
                // um quadro ruim não deve derrubar o app
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun start() {
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (running) {
                    stop()
                    onStopped()
                }
            }
        }, handler)

        val size = realSize()
        screenW = size[0]
        screenH = size[1]
        val w = max(1, (screenW * SCALE).toInt())
        val h = max(1, (screenH * SCALE).toInt())
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        display = projection.createVirtualDisplay(
            "setas-board", w, h, context.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
        )
        running = true
        handler.postDelayed(tick, TICK_MS)
    }

    /** Chamado quando a tela gira. */
    fun onConfigChanged() {
        if (!running) return
        try {
            val size = realSize()
            screenW = size[0]
            screenH = size[1]
            val w = max(1, (screenW * SCALE).toInt())
            val h = max(1, (screenH * SCALE).toInt())
            display?.resize(w, h, context.resources.displayMetrics.densityDpi)
            val nr = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            display?.surface = nr.surface
            reader?.close()
            reader = nr
            frame = null
            baseline = null
            seenVersion = -1
        } catch (e: Exception) {
            stop()
            onStopped()
        }
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        runCatching { display?.release() }
        runCatching { reader?.close() }
        display = null
        reader = null
        runCatching { projection.stop() }
    }

    // ---------------- Lógica ----------------

    private fun step() {
        grab()
        val f = frame ?: return
        val now = SystemClock.uptimeMillis()

        // houve mudança visual feita por nós (desenho, modo, calibração): refaz a referência
        if (board.version != seenVersion) {
            seenVersion = board.version
            baseline = null
            baselineDueAt = now + REBASE_MS
            return
        }
        if (!board.hasShapes || board.busy) return

        val base = baseline
        if (base == null) {
            if (now >= baselineDueAt) baseline = signature(f)
            return
        }

        val cur = signature(f) ?: return
        val covered = board.coveredCells()
        var changed = 0
        for (i in 0 until 64) {
            if (covered[i]) continue
            val d = (abs(cur[i * 3] - base[i * 3]) +
                    abs(cur[i * 3 + 1] - base[i * 3 + 1]) +
                    abs(cur[i * 3 + 2] - base[i * 3 + 2])) / 3
            if (d > CELL_DIFF) changed++
        }
        if (changed >= MIN_CELLS) board.clearShapes() // sobe a versão e refaz a referência
    }

    /** Copia o último quadro disponível. Se a tela está parada, mantém o anterior. */
    private fun grab() {
        val img = reader?.acquireLatestImage() ?: return
        try {
            val p = img.planes[0]
            val buf = p.buffer
            buf.rewind()
            val need = buf.remaining()
            val cached = frame
            val arr = if (cached != null && cached.size == need) cached else ByteArray(need)
            buf.get(arr)
            frame = arr
            fw = img.width
            fh = img.height
            rowStride = p.rowStride
            pixStride = p.pixelStride
        } finally {
            img.close()
        }
    }

    /** Cor média de cada uma das 64 casas (r, g, b), ou null se não der para medir. */
    private fun signature(f: ByteArray): IntArray? {
        val size = board.boardSize()
        if (size <= 0f) return null

        // posição da camada na tela (pode haver deslocamento da barra de status)
        val loc = IntArray(2)
        board.getLocationOnScreen(loc)
        val left = board.boardLeft() + loc[0]
        val top = board.boardTop() + loc[1]

        val sx = fw.toFloat() / screenW
        val sy = fh.toFloat() / screenH
        val sig = IntArray(64 * 3)
        val n = 4

        for (r in 0..7) {
            for (c in 0..7) {
                var sr = 0
                var sg = 0
                var sb = 0
                var cnt = 0
                for (j in 0 until n) {
                    for (i in 0 until n) {
                        // pontos dentro dos 70% centrais da casa
                        val px = ((left + (c + 0.15f + 0.7f * (i + 0.5f) / n) * size / 8f) * sx).toInt()
                        val py = ((top + (r + 0.15f + 0.7f * (j + 0.5f) / n) * size / 8f) * sy).toInt()
                        if (px < 0 || py < 0 || px >= fw || py >= fh) continue
                        val o = py * rowStride + px * pixStride
                        if (o + 2 >= f.size) continue
                        sr += f[o].toInt() and 0xFF
                        sg += f[o + 1].toInt() and 0xFF
                        sb += f[o + 2].toInt() and 0xFF
                        cnt++
                    }
                }
                if (cnt == 0) return null
                val k = (r * 8 + c) * 3
                sig[k] = sr / cnt
                sig[k + 1] = sg / cnt
                sig[k + 2] = sb / cnt
            }
        }
        return sig
    }

    @Suppress("DEPRECATION")
    private fun realSize(): IntArray {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            intArrayOf(b.width(), b.height())
        } else {
            val m = android.util.DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(m)
            intArrayOf(m.widthPixels, m.heightPixels)
        }
    }
}
