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

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var conectado: Boolean = false
    private var pendente: String = "{}"

    private val pedirPermissao =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) iniciarVpn() else avisarTela(false)
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.web)
        web.settings.javaScriptEnabled = true
        web.addJavascriptInterface(Ponte(), "Android")
        web.loadUrl("file:///android_asset/index.html")
    }

    private fun avisarTela(ligado: Boolean) {
        web.evaluateJavascript("onVpnState(" + ligado + ")", null)
    }

    private fun iniciarVpn() {
        val intent = Intent(this, MyVpnService::class.java)
        intent.action = "START"
        try {
            val o = JSONObject(pendente)
            intent.putExtra("endereco", o.optString("endereco"))
            intent.putExtra("porta", o.optInt("porta"))
            intent.putExtra("sni", o.optString("sni"))
            intent.putExtra("protocolo", o.optString("protocolo"))
        } catch (e: Exception) {
        }
        startService(intent)
        conectado = true
        avisarTela(true)
    }

    private fun pararVpn() {
        val intent = Intent(this, MyVpnService::class.java)
        intent.action = "STOP"
        startService(intent)
        conectado = false
        avisarTela(false)
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
            return conectado
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
    }
}
