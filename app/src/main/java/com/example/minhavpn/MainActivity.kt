package com.example.minhavpn

import android.content.Context
import android.content.DialogInterface
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
    private val servidores: MutableList<Servidor> = mutableListOf()
    private var conectado: Boolean = false

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

        val listaInicial: MutableList<String> = mutableListOf()
        adapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_item, listaInicial
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val t = super.getView(position, convertView, parent) as TextView
                t.setTextColor(Color.WHITE)
                return t
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val t = super.getDropDownView(position, convertView, parent) as TextView
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
        val view: View = layoutInflater.inflate(R.layout.dialog_servidor, null)
        val edtNome: EditText = view.findViewById(R.id.edtNome)
        val edtEndereco: EditText = view.findViewById(R.id.edtEndereco)
        val edtPorta: EditText = view.findViewById(R.id.edtPorta)
        val edtSni: EditText = view.findViewById(R.id.edtSni)
        val spProtocolo: Spinner = view.findViewById(R.id.spProtocolo)

        val protocolos: List<String> = listOf("VLESS", "VMess", "Trojan", "WireGuard")
        val adapterProtocolo = ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_dropdown_item, protocolos
        )
        spProtocolo.adapter = adapterProtocolo

        val aoSalvar = DialogInterface.OnClickListener { _: DialogInterface, _: Int ->
            val nome: String = edtNome.text.toString().trim()
            val endereco: String = edtEndereco.text.toString().trim()
            val porta: Int? = edtPorta.text.toString().toIntOrNull()
            if (nome.isEmpty() || endereco.isEmpty() || porta == null) {
                Toast.makeText(this, "Preencha nome, endereço e porta", Toast.LENGTH_SHORT).show()
            } else {
                val novo = Servidor(
                    nome,
                    endereco,
                    porta,
                    edtSni.text.toString().trim(),
                    spProtocolo.selectedItem.toString()
                )
                servidores.add(novo)
                salvarServidores()
                atualizarLista()
                spServidores.setSelection(servidores.size - 1)
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Adicionar servidor")
            .setView(view)
            .setPositiveButton("Salvar", aoSalvar)
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun excluirSelecionado() {
        val pos: Int = spServidores.selectedItemPosition
        if (servidores.isNotEmpty() && pos >= 0 && pos < servidores.size) {
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
            for (s in servidores) {
                adapter.add(s.nome + "  •  " + s.protocolo)
            }
        }
        adapter.notifyDataSetChanged()
    }

    private fun salvarServidores() {
        val arr = JSONArray()
        for (s in servidores) {
            val o = JSONObject()
            o.put("nome", s.nome)
            o.put("endereco", s.endereco)
            o.put("porta", s.porta)
            o.put("sni", s.sni)
            o.put("protocolo", s.protocolo)
            arr.put(o)
        }
        getSharedPreferences("vpn", Context.MODE_PRIVATE)
            .edit().putString("lista", arr.toString()).apply()
    }

    private fun carregarServidores() {
        val txt: String = getSharedPreferences("vpn", Context.MODE_PRIVATE)
            .getString("lista", "[]") ?: "[]"
        val arr = JSONArray(txt)
        for (i in 0 until arr.length()) {
            val o: JSONObject = arr.getJSONObject(i)
            servidores.add(
                Servidor(
                    o.getString("nome"),
                    o.getString("endereco"),
                    o.getInt("porta"),
                    o.optString("sni", ""),
                    o.optString("protocolo", "VLESS")
                )
            )
        }
    }

    // ---------- VPN ----------

    private fun iniciarVpn() {
        val pos: Int = spServidores.selectedItemPosition
        val s: Servidor? = servidores.getOrNull(pos)
        val intent = Intent(this, MyVpnService::class.java)
        intent.action = "START"
        if (s != null) {
            intent.putExtra("endereco", s.endereco)
            intent.putExtra("porta", s.porta)
            intent.putExtra("sni", s.sni)
            intent.putExtra("protocolo", s.protocolo)
        }
        startService(intent)
        atualizarTela(true)
    }

    private fun pararVpn() {
        val intent = Intent(this, MyVpnService::class.java)
        intent.action = "STOP"
        startService(intent)
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
