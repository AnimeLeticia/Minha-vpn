package com.example.minhavpn

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var btnConectar: Button
    private lateinit var txtStatus: TextView
    private var conectado = false

    private val pedirPermissao =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) iniciarVpn()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnConectar = findViewById(R.id.btnConectar)
        txtStatus = findViewById(R.id.txtStatus)

        btnConectar.setOnClickListener {
            if (conectado) {
                pararVpn()
            } else {
                val intent = VpnService.prepare(this)
                if (intent != null) pedirPermissao.launch(intent) else iniciarVpn()
            }
        }
    }

    private fun iniciarVpn() {
        startService(Intent(this, MyVpnService::class.java).setAction("START"))
        atualizarTela(true)
    }

    private fun pararVpn() {
        startService(Intent(this, MyVpnService::class.java).setAction("STOP"))
        atualizarTela(false)
    }

    private fun atualizarTela(ligado: Boolean) {
        conectado = ligado
        txtStatus.text = if (ligado) "Conectado" else "Desconectado"
        txtStatus.setTextColor(Color.parseColor(if (ligado) "#43A047" else "#9E9E9E"))
        btnConectar.text = if (ligado) "DESLIGAR" else "LIGAR"
        btnConectar.backgroundTintList =
            ColorStateList.valueOf(Color.parseColor(if (ligado) "#43A047" else "#E53935"))
    }
}
