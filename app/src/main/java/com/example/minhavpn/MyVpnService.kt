package com.example.minhavpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor

class MyVpnService : VpnService() {

    private var tunel: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> iniciar()
            "STOP" -> parar()
        }
        return START_NOT_STICKY
    }

    private fun iniciar() {
        if (tunel != null) return
        tunel = Builder()
            .setSession("Minha VPN")
            .addAddress("10.0.0.2", 32)
            .addRoute("10.0.0.0", 24)
            .establish()
    }

    private fun parar() {
        tunel?.close()
        tunel = null
        stopSelf()
    }

    override fun onDestroy() {
        parar()
        super.onDestroy()
    }
}
