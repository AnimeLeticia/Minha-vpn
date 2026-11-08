package com.example.minhavpn

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var pendente: String = "{}"

    private val pedirPermissao =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                iniciarVpn()
            } else {
                js("onVpnError(" + JSONObject.quote("Permissão de VPN negada") + ")")
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.web)
        web.settings.javaScriptEnabled = true
        web.addJavascriptInterface(Ponte(), "Android")
        MyVpnService.ouvinte = { tipo, msg -> runOnUiThread { tratar(tipo, msg) } }
        web.loadUrl("file:///android_asset/index.html")
    }

    override fun onDestroy() {
        MyVpnService.ouvinte = null
        super.onDestroy()
    }

    private fun js(codigo: String) {
        web.evaluateJavascript(codigo, null)
    }

    private fun tratar(tipo: String, msg: String) {
        if (tipo == "on") {
            js("onVpnState(true)")
        } else if (tipo == "off") {
            js("onVpnState(false)")
        } else if (tipo == "erro") {
            js("onVpnError(" + JSONObject.quote(msg) + ")")
        } else if (tipo == "log") {
            js("onVpnLog(" + JSONObject.quote(msg) + ")")
        }
    }

    private fun iniciarVpn() {
        val intent = Intent(this, MyVpnService::class.java)
        intent.action = "START"
        intent.putExtra("servidor", pendente)
        startService(intent)
    }

    private fun pararVpn() {
        val intent = Intent(this, MyVpnService::class.java)
        intent.action = "STOP"
        startService(intent)
    }

    inner class Ponte {
        @JavascriptInterface
        fun carregar(): String {
            return getSharedPreferences("vpn", Context.MODE_PRIVATE)
                .getString("lista", "[]") ?: "[]"
        }

        @JavascriptInterface
        fun salvar(json: String) {
            getSharedPreferences("vpn", Context.MODE_PRIVATE)
                .edit().putString("lista", json).apply()
        }

        @JavascriptInterface
        fun estaLigado(): Boolean {
            return MyVpnService.ativo
        }

        @JavascriptInterface
        fun ligar(json: String) {
            runOnUiThread {
                pendente = json
                val pedido: Intent? = VpnService.prepare(this@MainActivity)
                if (pedido != null) pedirPermissao.launch(pedido) else iniciarVpn()
            }
        }

        @JavascriptInterface
        fun desligar() {
            runOnUiThread { pararVpn() }
        }

        @JavascriptInterface
        fun logs(): String {
            try {
                val f = File(filesDir, "sb/work/box.log")
                if (!f.exists()) return ""
                RandomAccessFile(f, "r").use { r ->
                    val tam: Long = r.length()
                    val n: Int = if (tam > 6000L) 6000 else tam.toInt()
                    val buf = ByteArray(n)
                    r.seek(tam - n)
                    r.readFully(buf)
                    return String(buf)
                }
            } catch (e: Exception) {
                return ""
            }
        }
    }
}
