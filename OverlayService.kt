package com.example.setasoverlay

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import kotlin.math.hypot
import kotlin.math.max

class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.setasoverlay.STOP"
        private const val CHANNEL_ID = "setas"
        private const val NOTIF_ID = 1
        private const val LIME = 0xFFC8FF00.toInt()

        const val EXTRA_CODE = "capture_code"
        const val EXTRA_DATA = "capture_data"

        /** Lido pela tela inicial para mostrar o estado. */
        @Volatile
        var running = false

        /** Verdadeiro enquanto a gravação de tela está ligada (apagar sozinho). */
        @Volatile
        var capturing = false
    }

    private class Btn(val view: FloatButton, val lp: WindowManager.LayoutParams)

    private lateinit var wm: WindowManager
    private var board: BoardView? = null
    private lateinit var boardLp: WindowManager.LayoutParams
    private val btns = mutableListOf<Btn>()
    private var watcher: BoardWatcher? = null

    private lateinit var btnDraw: FloatButton
    private lateinit var btnBadge: FloatButton
    private lateinit var btnColor: FloatButton
    private lateinit var btnCalib: FloatButton

    private val prefs by lazy { getSharedPreferences("setas_v3", MODE_PRIVATE) }
    private val density by lazy { resources.displayMetrics.density }
    private val btnSize get() = (44 * density).toInt()
    private val gap get() = (8 * density).toInt()

    private val overlayType: Int
        get() = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        val wantCapture = data != null && code == Activity.RESULT_OK

        startForegroundCompat(wantCapture)
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (board == null) createOverlay()
        if (wantCapture && data != null) startCapture(code, data)
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (board != null) clampButtons()
        watcher?.onConfigChanged()
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        watcher?.stop()
        watcher = null
        capturing = false
        if (board != null) {
            runCatching { wm.removeView(board) }
            btns.forEach { runCatching { wm.removeView(it.view) } }
        }
        btns.clear()
        board = null
    }

    // ---------------- Notificação (serviço em primeiro plano) ----------------

    private fun startForegroundCompat(capture: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Setas", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("Setas ativo")
            .setContentText("Toque para abrir, ou em Parar para remover os botões")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Parar", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (capture) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(NOTIF_ID, n, type)
        } else if (Build.VERSION.SDK_INT >= 29 && capture) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ---------------- Overlay ----------------

    private fun createOverlay() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dm = resources.displayMetrics

        val b = BoardView(this)
        b.setBoard(
            prefs.getFloat("left", 0f),
            prefs.getFloat("top", dm.heightPixels * 0.25f),
            prefs.getFloat("size", dm.widthPixels.toFloat())
        )
        b.colorIndex = prefs.getInt("color", 0)
        b.onBoardChanged = { l, t, s ->
            prefs.edit().putFloat("left", l).putFloat("top", t).putFloat("size", s).apply()
        }

        boardLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, // começa deixando o toque passar
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        wm.addView(b, boardLp)
        board = b

        // Botões adicionados depois ficam por cima da camada do tabuleiro
        btnDraw = makeButton("draw", FloatButton.Kind.DRAW, 0, LIME) { toggleMode(BoardView.Mode.DRAW) }
        btnBadge = makeButton("badge", FloatButton.Kind.BADGE, 1, LIME) { toggleBadge() }
        btnColor = makeButton("color", FloatButton.Kind.COLOR, 2, LIME) { nextColor() }
        makeButton("undo", FloatButton.Kind.UNDO, 3, LIME) { board?.undo() }
        makeButton("clear", FloatButton.Kind.CLEAR, 4, LIME) { board?.clearShapes() }
        btnCalib = makeButton("calib", FloatButton.Kind.CALIBRATE, 5, LIME) {
            toggleMode(BoardView.Mode.CALIBRATE)
        }
        refreshButtons()
        running = true
    }

    private fun toggleMode(target: BoardView.Mode) {
        val b = board ?: return
        setMode(if (b.mode == target) BoardView.Mode.OFF else target)
    }

    /** Liga o selo "!!": se o modo desenho estiver desligado, liga junto. Toque de novo volta às setas. */
    private fun toggleBadge() {
        val b = board ?: return
        b.tool = if (b.tool == BoardView.Tool.BADGE) BoardView.Tool.ARROW else BoardView.Tool.BADGE
        if (b.tool == BoardView.Tool.BADGE && b.mode != BoardView.Mode.DRAW) {
            setMode(BoardView.Mode.DRAW)
        } else {
            refreshButtons()
        }
    }

    private fun setMode(target: BoardView.Mode) {
        val b = board ?: return
        b.mode = target
        // só captura toque quando está desenhando ou calibrando
        boardLp.flags = if (b.mode == BoardView.Mode.OFF)
            baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else
            baseFlags
        wm.updateViewLayout(b, boardLp)
        refreshButtons()
    }

    private fun nextColor() {
        val b = board ?: return
        b.colorIndex = (b.colorIndex + 1) % b.colors.size
        prefs.edit().putInt("color", b.colorIndex).apply()
        refreshButtons()
    }

    private fun refreshButtons() {
        val b = board ?: return
        btnDraw.active = b.mode == BoardView.Mode.DRAW
        btnBadge.active = b.mode == BoardView.Mode.DRAW && b.tool == BoardView.Tool.BADGE
        btnCalib.active = b.mode == BoardView.Mode.CALIBRATE
        btnColor.dotColor = b.colors[b.colorIndex]
    }

    private fun startCapture(code: Int, data: Intent) {
        val b = board ?: return
        watcher?.stop()
        try {
            val mgr = getSystemService(MediaProjectionManager::class.java)
            val projection = mgr.getMediaProjection(code, data) ?: return
            watcher = BoardWatcher(this, projection, b) {
                watcher = null
                capturing = false
                Toast.makeText(this, "Gravação de tela encerrada: as setas não somem mais sozinhas", Toast.LENGTH_LONG).show()
            }.also {
                it.start()
                capturing = true
            }
        } catch (e: Exception) {
            watcher = null
            Toast.makeText(this, "Não foi possível iniciar a gravação de tela", Toast.LENGTH_LONG).show()
        }
    }

    private fun clampButtons() {
        val dm = resources.displayMetrics
        val size = btnSize
        btns.forEach {
            it.lp.x = it.lp.x.coerceIn(0, max(0, dm.widthPixels - size))
            it.lp.y = it.lp.y.coerceIn(0, max(0, dm.heightPixels - size))
            runCatching { wm.updateViewLayout(it.view, it.lp) }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeButton(
        id: String,
        kind: FloatButton.Kind,
        index: Int,
        activeColor: Int,
        onTap: () -> Unit
    ): FloatButton {
        val dm = resources.displayMetrics
        val size = btnSize

        val v = FloatButton(this, kind, activeColor).apply { alpha = 0.92f }

        // padrão: coluna na lateral direita, a partir de 40% da altura
        val lp = WindowManager.LayoutParams(size, size, overlayType, baseFlags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("${id}_x", dm.widthPixels - size - gap)
            y = prefs.getInt("${id}_y", (dm.heightPixels * 0.30f).toInt() + index * (size + gap))
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        val slop = 8 * density

        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    board?.bump()
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && hypot(dx, dy) < slop) return@setOnTouchListener true
                    moved = true
                    val m = resources.displayMetrics
                    lp.x = (startX + dx).toInt().coerceIn(0, max(0, m.widthPixels - size))
                    lp.y = (startY + dy).toInt().coerceIn(0, max(0, m.heightPixels - size))
                    wm.updateViewLayout(view, lp)
                }
                MotionEvent.ACTION_UP -> {
                    board?.bump()
                    view.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    if (moved) {
                        prefs.edit().putInt("${id}_x", lp.x).putInt("${id}_y", lp.y).apply()
                    } else {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onTap()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                }
            }
            true
        }

        wm.addView(v, lp)
        btns.add(Btn(v, lp))
        return v
    }
}
