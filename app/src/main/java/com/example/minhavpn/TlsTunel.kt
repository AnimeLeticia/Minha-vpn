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
    @Volatile private var ultimoAviso: Long = 0L

    private fun avisarUmaVez(m: String) {
        val agora: Long = System.currentTimeMillis()
        if (agora - ultimoAviso > 10000L) {
            ultimoAviso = agora
            aviso(m)
        }
    }

    private fun explicar(m: String): String {
        val baixo: String = m.lowercase()
        return if (baixo.contains("timed out")) {
            "O servidor aceitou a conexão mas ficou calado no TLS. A porta " + porta +
                " provavelmente não é TLS (pode ser SSH puro ou WebSocket), ou o SNI foi descartado."
        } else if (baixo.contains("reset") || baixo.contains("abort")) {
            "A conexão foi derrubada durante o TLS: o servidor ou a operadora recusou esta porta ou este SNI."
        } else if (baixo.contains("wrong version") || baixo.contains("not look like") || baixo.contains("unexpected")) {
            "A porta " + porta + " não fala TLS (provavelmente é SSH puro)."
        } else {
            "Falha no TLS: " + m
        }
    }

    // Retorna null se tudo certo, ou a explicação do problema
    fun testar(): String? {
        val d: InetAddress = try {
            InetAddress.getByName(host)
        } catch (e: Throwable) {
            return "Não consegui resolver o endereço " + host + ". Use o IP do servidor."
        }
        destino = d
        val bruto = Socket()
        try {
            vpn.protect(bruto)
            val t0: Long = System.currentTimeMillis()
            try {
                bruto.connect(InetSocketAddress(d, porta), 8000)
            } catch (e: Throwable) {
                return "Sem conexão com " + host + ":" + porta + " (" + (e.message ?: "tempo esgotado") +
                    "). Confira o IP, a porta e se a operadora bloqueia."
            }
            aviso("Teste: conectou ao servidor em " + (System.currentTimeMillis() - t0) + " ms")
            bruto.soTimeout = 10000
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<TrustManager>(ConfiaEmTudo()), null)
            val ssl = ctx.socketFactory.createSocket(bruto, sni, porta, true) as SSLSocket
            val params = ssl.sslParameters
            val nomes = ArrayList<SNIServerName>()
            nomes.add(SNIHostName(sni))
            params.serverNames = nomes
            ssl.sslParameters = params
            try {
                ssl.startHandshake()
            } catch (e: Throwable) {
                return explicar(e.message ?: e.toString())
            }
            aviso("Teste: TLS ok (" + ssl.session.protocol + ")")
            ssl.soTimeout = 8000
            val buf = ByteArray(8)
            var n = 0
            try {
                n = ssl.getInputStream().read(buf)
            } catch (e: Throwable) {
                return "TLS ok, mas o servidor não mandou o banner SSH em 8 s. Há um SSH atrás do TLS na porta " + porta + "?"
            }
            val texto: String = if (n > 0) String(buf, 0, n) else ""
            if (!texto.startsWith("SSH-")) {
                val limpo: String = texto.replace(Regex("[^\\x20-\\x7E]"), "?")
                return "TLS ok, mas o que veio depois não é SSH (recebi: " + limpo + ")"
            }
            aviso("Teste: SSH respondeu atrás do TLS")
            return null
        } catch (e: Throwable) {
            return "Falha no teste TLS: " + (e.message ?: e.toString())
        } finally {
            try { bruto.close() } catch (e: Throwable) { }
        }
    }

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
            avisarUmaVez("TLS falhou: " + (e.message ?: e.toString()))
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