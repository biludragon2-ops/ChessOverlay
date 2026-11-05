package com.example.setasoverlay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private val bg = 0xFF101010.toInt()
    private val lime = 0xFFC8FF00.toInt()
    private val grey = 0xFF6B6B6B.toInt()

    private lateinit var dotStatus: View
    private lateinit var txtStatus: TextView
    private lateinit var cardPerm: View
    private lateinit var cardStart: View
    private lateinit var cardRunning: View
    private lateinit var txtRunInfo: TextView
    private lateinit var btnPerm: Button
    private lateinit var btnStartRec: Button
    private lateinit var btnStartPlain: Button
    private lateinit var btnStop: Button

    private val handler = Handler(Looper.getMainLooper())

    // Atualiza a tela enquanto ela está visível (por exemplo, se você parar pela notificação)
    private val poll = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    // Resultado do pedido de gravação de tela ("Iniciar com gravador")
    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode == RESULT_OK && data != null) {
            launchService(res.resultCode, data)
        } else {
            Toast.makeText(
                this,
                "Gravação de tela não permitida. Use \"Iniciar sem gravador\" se preferir.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        @Suppress("DEPRECATION")
        window.statusBarColor = bg
        @Suppress("DEPRECATION")
        window.navigationBarColor = bg

        val root = findViewById<View>(R.id.root)
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        val l = root.paddingLeft
        val t = root.paddingTop
        val r = root.paddingRight
        val b = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom)
            insets
        }

        dotStatus = findViewById(R.id.dotStatus)
        txtStatus = findViewById(R.id.txtStatus)
        cardPerm = findViewById(R.id.cardPerm)
        cardStart = findViewById(R.id.cardStart)
        cardRunning = findViewById(R.id.cardRunning)
        txtRunInfo = findViewById(R.id.txtRunInfo)
        btnPerm = findViewById(R.id.btnPerm)
        btnStartRec = findViewById(R.id.btnStartRec)
        btnStartPlain = findViewById(R.id.btnStartPlain)
        btnStop = findViewById(R.id.btnStop)

        btnPerm.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        btnStartRec.setOnClickListener {
            askNotificationPermission()
            val mgr = getSystemService(MediaProjectionManager::class.java)
            capture.launch(mgr.createScreenCaptureIntent())
        }
        btnStartPlain.setOnClickListener {
            askNotificationPermission()
            launchService(null, null)
        }
        btnStop.setOnClickListener {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
            Toast.makeText(this, "Desligado.", Toast.LENGTH_SHORT).show()
            handler.postDelayed({ refresh() }, 400)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(poll)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
    }

    /** Android 13+: permissão para mostrar a notificação do serviço (não bloqueia o app). */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun launchService(code: Int?, data: Intent?) {
        val i = Intent(this, OverlayService::class.java)
        if (code != null && data != null) {
            i.putExtra(OverlayService.EXTRA_CODE, code)
            i.putExtra(OverlayService.EXTRA_DATA, data)
        }
        ContextCompat.startForegroundService(this, i)
        Toast.makeText(this, "Ativo. Abra o app de xadrez.", Toast.LENGTH_SHORT).show()
        handler.postDelayed({ refresh() }, 400)
    }

    private fun refresh() {
        val perm = Settings.canDrawOverlays(this)
        val run = OverlayService.running
        val rec = OverlayService.capturing

        cardPerm.visibility = if (!perm && !run) View.VISIBLE else View.GONE
        cardStart.visibility = if (perm && !run) View.VISIBLE else View.GONE
        cardRunning.visibility = if (run) View.VISIBLE else View.GONE

        ViewCompat.setBackgroundTintList(dotStatus, ColorStateList.valueOf(if (run) lime else grey))
        txtStatus.text = if (run) "ATIVO" else "PARADO"

        txtRunInfo.text = if (rec) {
            "Gravador: ligado. As setas somem quando uma peça se mexe."
        } else {
            "Gravador: desligado. Apague os desenhos com a lixeira ou o desfazer."
        }
    }
}
