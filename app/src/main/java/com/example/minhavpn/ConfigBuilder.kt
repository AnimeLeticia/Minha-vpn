package com.example.minhavpn

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ConfigBuilder {

    fun montar(s: JSONObject, logFile: File): String {
        val proto = s.optString("protocolo")
        if (proto != "SSH") {
            throw Exception("O protocolo " + proto + " ainda não está ligado ao motor. Use um servidor SSH.")
        }
        val host = s.optString("endereco").trim()
        val porta = s.optInt("porta", 22)
        val usuario = s.optString("usuario")
        val senha = s.optString("senha")
        if (host.isEmpty() || usuario.isEmpty() || senha.isEmpty()) {
            throw Exception("Servidor SSH incompleto: confira endereço, usuário e senha")
        }

        logFile.parentFile?.mkdirs()
        if (logFile.exists()) logFile.delete()

        val log = JSONObject()
        log.put("level", "info")
        log.put("timestamp", true)
        log.put("output", logFile.path)

        val dnsServers = JSONArray()
        dnsServers.put(
            JSONObject().put("type", "udp").put("tag", "dns-direct").put("server", "8.8.8.8")
        )
        dnsServers.put(
            JSONObject().put("type", "tcp").put("tag", "dns-proxy")
                .put("server", "8.8.8.8").put("detour", "proxy")
        )
        val dns = JSONObject()
        dns.put("servers", dnsServers)
        dns.put("final", "dns-proxy")

        val enderecos = JSONArray()
        enderecos.put("172.19.0.1/30")
        enderecos.put("fdfe:dcba:9876::1/126")
        val tun = JSONObject()
        tun.put("type", "tun")
        tun.put("tag", "tun-in")
        tun.put("address", enderecos)
        tun.put("mtu", 1400)
        tun.put("auto_route", true)
        tun.put("stack", "system")

        val ssh = JSONObject()
        ssh.put("type", "ssh")
        ssh.put("tag", "proxy")
        ssh.put("server", host)
        ssh.put("server_port", porta)
        ssh.put("user", usuario)
        ssh.put("password", senha)

        val direto = JSONObject()
        direto.put("type", "direct")
        direto.put("tag", "direct")

        val regras = JSONArray()
        regras.put(JSONObject().put("action", "sniff"))
        regras.put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
        val rota = JSONObject()
        rota.put("auto_detect_interface", true)
        rota.put("default_domain_resolver", "dns-direct")
        rota.put("final", "proxy")
        rota.put("rules", regras)

        val raiz = JSONObject()
        raiz.put("log", log)
        raiz.put("dns", dns)
        raiz.put("inbounds", JSONArray().put(tun))
        raiz.put("outbounds", JSONArray().put(ssh).put(direto))
        raiz.put("route", rota)
        return raiz.toString()
    }
}
