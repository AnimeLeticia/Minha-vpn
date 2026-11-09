package com.example.minhavpn

import android.net.VpnService
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class ConfiaEmTudo : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) { }
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) { }
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}

class TlsTunel(
    private val vpn: VpnService,
    private val host: String,
    private val porta: Int,
    private val sni: String,
    private val aviso: (String) -> Unit
) {
    private var servidor: ServerSocket? = null
    private var destino: InetAddress? = null
    @Volatile private var ativo: Boolean = false

    fun iniciar(): Int {
        destino = InetAddress.getByName(host)
        val s = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        servidor = s
        ativo = true
        Thread { aceitar(s) }.start()
        return s.localPort
    }

    fun parar() {
        ativo = false
        try { servidor?.close() } catch (e: Throwable) { }
        servidor = null
    }

    private fun aceitar(s: ServerSocket) {
        while (ativo) {
            try {
                val c = s.accept()
                Thread { ligar(c) }.start()
            } catch (e: Throwable) {
                break
            }
        }
    }

    private fun ligar(cliente: Socket) {
        val bruto = Socket()
        try {
            val d: InetAddress = destino ?: throw Exception("destino não resolvido")
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<TrustManager>(ConfiaEmTudo()), null)
            vpn.protect(bruto)
            bruto.keepAlive = true
            bruto.soTimeout = 15000
            bruto.connect(InetSocketAddress(d, porta), 15000)
            val ssl = ctx.socketFactory.createSocket(bruto, sni, porta, true) as SSLSocket
            val params = ssl.sslParameters
            val nomes = ArrayList<SNIServerName>()
            nomes.add(SNIHostName(sni))
            params.serverNames = nomes
            ssl.sslParameters = params
            ssl.startHandshake()
            ssl.soTimeout = 0
            Thread { copiar(cliente, ssl) }.start()
            Thread { copiar(ssl, cliente) }.start()
        } catch (e: Throwable) {
            aviso("TLS falhou: " + (e.message ?: e.toString()))
            try { cliente.close() } catch (x: Throwable) { }
            try { bruto.close() } catch (x: Throwable) { }
        }
    }

    private fun copiar(de: Socket, para: Socket) {
        try {
            val entrada = de.getInputStream()
            val saida = para.getOutputStream()
            val buf = ByteArray(16384)
            while (true) {
                val n = entrada.read(buf)
                if (n < 0) break
                saida.write(buf, 0, n)
                saida.flush()
            }
        } catch (e: Throwable) {
        } finally {
            try { de.close() } catch (e: Throwable) { }
            try { para.close() } catch (e: Throwable) { }
        }
    }
}
