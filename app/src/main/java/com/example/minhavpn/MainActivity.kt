package com.example.minhavpn

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

data class Servidor(
    val nome: String,
    val endereco: String,
    val porta: Int,
    val sni: String,
    val protocolo: String
)

class MainActivity : AppCompatActivity() {

    private lateinit var btnConectar: Button
    private lateinit var txtStatus: TextView
    private lateinit var spServidores: Spinner
    private lateinit var adapter: ArrayAdapter<String>
    private val servidores = mutableListOf<Servidor>()
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
        spServidores = findViewById(R.id.spServidores)

        adapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_item, mutableListOf()
        ) {
            override fun getView(pos: Int, v: View?, parent: ViewGroup): View {
                val t = super.getView(pos, v, parent) as TextView
                t.setTextColor(Color.WHITE)
                return t
            }

            override fun getDropDownView(pos: Int, v: View?, parent: ViewGroup): View {
                val t = super.getDropDownView(pos, v, parent) as TextView
                t.setTextColor(Color.WHITE)
                t.setBackgroundColor(Color.parseColor("#1E1E1E"))
                t.setPadding(24, 24, 24, 24)
                return t
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spServidores.adapter = adapter

        carregarServidores()
        atualizarLista()

        findViewById<Button>(R.id.btnAdicionar).setOnClickListener { mostrarDialogo() }
        findViewById<Button>(R.id.btnExcluir).setOnClickListener { excluirSelecionado() }

        btnConectar.setOnClickListener {
            if (conectado) {
                pararVpn()
            } else if (servidores.isEmpty()) {
                Toast.makeText(this, "Adicione um servidor primeiro", Toast.LENGTH_SHORT).show()
            } else {
                val intent = VpnService.prepare(this)
                if (intent != null) pedirPermissao.launch(intent) else iniciarVpn()
            }
        }
    }

    // ---------- Servidores ----------

    private fun mostrarDialogo() {
        val view = layoutInflater.inflate(R.layout.dialog_servidor, null)
        val edtNome = view.findViewById<EditText>(R.id.edtNome)
        val edtEndereco = view.findViewById<EditText>(R.id.edtEndereco)
        val edtPorta = view.findViewById<EditText>(R.id.edtPorta)
        val edtSni = view.findViewById<EditText>(R.id.edtSni)
        val spProtocolo = view.findViewById<Spinner>(R.id.spProtocolo)

        spProtocolo.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("VLESS", "VMess", "Trojan", "WireGuard")
        )

        AlertDialog.Builder(this)
            .setTitle("Adicionar servidor")
            .setView(view)
            .setPositiveButton("Salvar") { _, _ ->
                val nome = edtNome.text.toString().trim()
                val endereco = edtEndereco.text.toString().trim()
                val porta = edtPorta.text.toString().toIntOrNull()
                if (nome.isEmpty() || endereco.isEmpty() || porta == null) {
                    Toast.makeText(this, "Preencha nome, endereço e porta", Toast.LENGTH_SHORT).show()
                } else {
                    servidores.add(
                        Servidor(
                            nome, endereco, porta,
                            edtSni.text.toString().trim(),
                            spProtocolo.selectedItem.toString()
                        )
                    )
                    salvarServidores()
                    atualizarLista()
                    spServidores.setSelection(servidores.size - 1)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun excluirSelecionado() {
        val pos = spServidores.selectedItemPosition
        if (servidores.isNotEmpty() && pos in servidores.indices) {
            servidores.removeAt(pos)
            salvarServidores()
            atualizarLista()
        }
    }

    private fun atualizarLista() {
        adapter.clear()
        if (servidores.isEmpty()) {
            adapter.add("Nenhum servidor adicionado")
        } else {
            adapter.addAll(servidores.map { "${it.nome}  •  ${it.protocolo}" })
        }
        adapter.notifyDataSetChanged()
    }

    private fun salvarServidores() {
        val arr = JSONArray()
        servidores.forEach {
            arr.put(
                JSONObject()
                    .put("nome", it.nome)
                    .put("endereco", it.endereco)
                    .put("porta", it.porta)
                    .put("sni", it.sni)
                    .put("protocolo", it.protocolo)
            )
        }
        getSharedPreferences("vpn", Context.MODE_PRIVATE)
            .edit().putString("lista", arr.toString()).apply()
    }

    private fun carregarServidores() {
        val txt = getSharedPreferences("vpn", Context.MODE_PRIVATE)
            .getString("lista", "[]") ?: "[]"
        val arr = JSONArray(txt)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            servidores.add(
                Servidor(
                    o.getString("nome"), o.getString("endereco"), o.getInt("porta"),
                    o.optString("sni"), o.optString("protocolo", "VLESS")
                )
            )
        }
    }

    // ---------- VPN ----------

    private fun iniciarVpn() {
        val pos = spServidores.selectedItemPosition
        val s = servidores.getOrNull(pos)
        val intent = Intent(this, MyVpnService::class.java).setAction("START")
        if (s != null) {
            intent.putExtra("endereco", s.endereco)
                .putExtra("porta", s.porta)
                .putExtra("sni", s.sni)
                .putExtra("protocolo", s.protocolo)
        }
        startService(intent)
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
        spServidores.isEnabled = !ligado
    }
}
