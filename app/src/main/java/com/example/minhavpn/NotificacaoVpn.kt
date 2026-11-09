package com.example.minhavpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.TrafficStats
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat

class NotificacaoVpn(private val servico: Service) {

    private val canal: String = "cipher_vpn_status"
    private val idNotif: Int = 1
    private val principal = Handler(Looper.getMainLooper())

    private var nome: String = ""
    private var inicio: Long = 0L
    private var rx0: Long = 0L
    private var tx0: Long = 0L
    private var rxAnt: Long = 0L
    private var txAnt: Long = 0L
    private var tAnt: Long = 0L
    private var rodando: Boolean = false

    private val tarefa: Runnable = object : Runnable {
        override fun run() {
            if (!rodando) return
            atualizar()
            principal.postDelayed(this, 1000L)
        }
    }

    fun iniciar(nomeServidor: String) {
        principal.post {
            nome = nomeServidor
            criarCanal()
            val uid: Int = Process.myUid()
            rx0 = lido(TrafficStats.getUidRxBytes(uid))
            tx0 = lido(TrafficStats.getUidTxBytes(uid))
            rxAnt = rx0
            txAnt = tx0
            tAnt = SystemClock.elapsedRealtime()
            inicio = System.currentTimeMillis()
            rodando = true
            try {
                servico.startForeground(idNotif, montar(0L, 0L, 0L, 0L))
            } catch (e: Throwable) { }
            principal.postDelayed(tarefa, 1000L)
        }
    }

    fun parar() {
        principal.post {
            rodando = false
            principal.removeCallbacks(tarefa)
            try {
                servico.stopForeground(true)
            } catch (e: Throwable) { }
        }
    }

    private fun lido(v: Long): Long = if (v < 0L) 0L else v

    private fun criarCanal() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = servico.getSystemService(NotificationManager::class.java)
            val c = NotificationChannel(canal, "Status da VPN", NotificationManager.IMPORTANCE_LOW)
            c.setShowBadge(false)
            nm.createNotificationChannel(c)
        }
    }

    private fun atualizar() {
        try {
            val uid: Int = Process.myUid()
            val rx: Long = lido(TrafficStats.getUidRxBytes(uid))
            val tx: Long = lido(TrafficStats.getUidTxBytes(uid))
            val agora: Long = SystemClock.elapsedRealtime()
            var dt: Long = agora - tAnt
            if (dt < 1L) dt = 1L
            val velRx: Long = (rx - rxAnt) * 1000L / dt
            val velTx: Long = (tx - txAnt) * 1000L / dt
            rxAnt = rx
            txAnt = tx
            tAnt = agora
            val nm = servico.getSystemService(NotificationManager::class.java)
            nm.notify(idNotif, montar(velRx, velTx, rx - rx0, tx - tx0))
        } catch (e: Throwable) { }
    }

    private fun tam(b: Long): String {
        val v: Double = b.toDouble()
        return if (b < 1024L) {
            b.toString() + " B"
        } else if (b < 1048576L) {
            String.format("%.1f KB", v / 1024.0)
        } else if (b < 1073741824L) {
            String.format("%.1f MB", v / 1048576.0)
        } else {
            String.format("%.2f GB", v / 1073741824.0)
        }
    }

    private fun montar(velRx: Long, velTx: Long, totRx: Long, totTx: Long): Notification {
        val abrir = Intent(servico, MainActivity::class.java)
        abrir.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        val piAbrir = PendingIntent.getActivity(
            servico, 0, abrir, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val desligar = Intent(servico, MyVpnService::class.java)
        desligar.action = "STOP"
        val piParar = PendingIntent.getService(
            servico, 1, desligar, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val linha: String = "\u2193 " + tam(velRx) + "/s    \u2191 " + tam(velTx) + "/s"
        val total: String = "Baixado: " + tam(totRx) + "    Enviado: " + tam(totTx)

        return NotificationCompat.Builder(servico, canal)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("CIPHER VPN \u2022 Conectado")
            .setContentText(linha)
            .setSubText(nome)
            .setStyle(NotificationCompat.BigTextStyle().bigText(linha + "\n" + total))
            .setContentIntent(piAbrir)
            .addAction(0, "Desconectar", piParar)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(inicio)
            .setUsesChronometer(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
