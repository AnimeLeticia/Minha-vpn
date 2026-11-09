package com.example.minhavpn

import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import org.json.JSONObject
import java.io.File

class MyVpnService : VpnService(), PlatformInterface, CommandServerHandler {

    companion object {
        @Volatile var ativo: Boolean = false
        @Volatile var ouvinte: ((String, String) -> Unit)? = null
        @Volatile var setupFeito: Boolean = false
    }

    private var servidor: CommandServer? = null
    private var tunel: ParcelFileDescriptor? = null
    private var monitor: ConnectivityManager.NetworkCallback? = null
    private var tls: TlsTunel? = null
    private val notif: NotificacaoVpn by lazy { NotificacaoVpn(this) }
    @Volatile private var ocupado: Boolean = false

    private fun avisar(tipo: String, msg: String) {
        ouvinte?.invoke(tipo, msg)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val acao: String? = intent?.action
        if (intent != null && acao == "START") {
            val json: String = intent.getStringExtra("servidor") ?: "{}"
            Thread { iniciar(json) }.start()
        } else if (acao == "STOP") {
            parar()
        }
        return START_NOT_STICKY
    }

    private fun prepararLibbox() {
        if (setupFeito) return
        val base = File(filesDir, "sb")
        val trabalho = File(base, "work")
        val temp = File(cacheDir, "sb")
        base.mkdirs()
        trabalho.mkdirs()
        temp.mkdirs()
        val o = SetupOptions()
        o.setBasePath(base.path)
        o.setWorkingPath(trabalho.path)
        o.setTempPath(temp.path)
        o.setLogMaxLines(2000L)
        Libbox.setup(o)
        setupFeito = true
    }

    private fun iniciar(json: String) {
        if (ocupado || servidor != null) return
        ocupado = true
        try {
            val dados = JSONObject(json)
            val nomeServidor: String = dados.optString("nome")

            // Se houver SNI no servidor SSH, o app testa e abre um túnel TLS local
            val sni: String = dados.optString("sni").trim()
            if (sni.isNotEmpty() && dados.optString("protocolo") == "SSH") {
                val t = TlsTunel(
                    this,
                    dados.optString("endereco").trim(),
                    dados.optInt("porta", 443),
                    sni
                ) { m -> avisar("log", m) }
                val erroTls: String? = t.testar()
                if (erroTls != null) throw Exception(erroTls)
                val portaLocal: Int = t.iniciar()
                tls = t
                avisar("log", "TLS ativo com SNI " + sni)
                dados.put("endereco", "127.0.0.1")
                dados.put("porta", portaLocal)
            }

            val cfg = ConfigBuilder.montar(dados, File(filesDir, "sb/work/box.log"))
            prepararLibbox()
            Libbox.checkConfig(cfg)
            val s = Libbox.newCommandServer(this, this)
            servidor = s
            s.start()
            s.startOrReloadService(cfg, OverrideOptions())
            ativo = true
            notif.iniciar(nomeServidor)
            avisar("on", "")
        } catch (e: Throwable) {
            val m: String = e.message ?: e.toString()
            limpar()
            avisar("erro", m)
        } finally {
            ocupado = false
        }
    }

    private fun limpar() {
        try { servidor?.closeService() } catch (e: Throwable) { }
        try { servidor?.close() } catch (e: Throwable) { }
        servidor = null
        try { tls?.parar() } catch (e: Throwable) { }
        tls = null
        try { tunel?.close() } catch (e: Throwable) { }
        tunel = null
        pararMonitor()
        ativo = false
        try { notif.parar() } catch (e: Throwable) { }
    }

    private fun parar() {
        Thread {
            limpar()
            avisar("off", "")
            stopSelf()
        }.start()
    }

    override fun onRevoke() {
        parar()
    }

    override fun onDestroy() {
        try { limpar() } catch (e: Throwable) { }
        super.onDestroy()
    }

    // ---------- CommandServerHandler ----------

    override fun connectSSHAgent(): Int {
        throw Exception("não suportado")
    }

    override fun getSystemProxyStatus(): SystemProxyStatus? = null

    override fun serviceReload() { }

    override fun serviceStop() {
        parar()
    }

    override fun setSystemProxyEnabled(enabled: Boolean) { }

    override fun triggerNativeCrash() { }

    override fun writeDebugMessage(message: String?) {
        if (message != null) avisar("log", message)
    }

    // ---------- PlatformInterface ----------

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun openTun(options: TunOptions): Int {
        if (VpnService.prepare(this) != null) {
            throw Exception("Permissão de VPN ausente ou revogada")
        }
        val b = Builder()
        b.setSession("CIPHER VPN")
        b.setMtu(options.getMTU())

        var temV4 = false
        val v4 = options.getInet4Address()
        while (v4.hasNext()) {
            val a = v4.next()
            b.addAddress(a.address(), a.prefix())
            temV4 = true
        }
        var temV6 = false
        val v6 = options.getInet6Address()
        while (v6.hasNext()) {
            val a = v6.next()
            b.addAddress(a.address(), a.prefix())
            temV6 = true
        }

        if (options.getAutoRoute()) {
            val dns = options.getDNSServerAddress()
            while (dns.hasNext()) {
                b.addDnsServer(dns.next())
            }
            val r4 = options.getInet4RouteAddress()
            if (r4.hasNext()) {
                while (r4.hasNext()) {
                    val a = r4.next()
                    b.addRoute(a.address(), a.prefix())
                }
            } else if (temV4) {
                b.addRoute("0.0.0.0", 0)
            }
            val r6 = options.getInet6RouteAddress()
            if (r6.hasNext()) {
                while (r6.hasNext()) {
                    val a = r6.next()
                    b.addRoute(a.address(), a.prefix())
                }
            } else if (temV6) {
                b.addRoute("::", 0)
            }
        }

        val pfd = b.establish() ?: throw Exception("O Android recusou criar a interface de VPN")
        tunel = pfd
        return pfd.fd
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val cm = getSystemService(ConnectivityManager::class.java)
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                empurrar(listener, network)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                empurrar(listener, network)
            }

            override fun onLost(network: Network) {
                try { listener.updateDefaultInterface("", -1, false, false) } catch (e: Throwable) { }
            }
        }
        monitor = cb
        if (Build.VERSION.SDK_INT >= 31) {
            cm.registerBestMatchingNetworkCallback(req, cb, Handler(Looper.getMainLooper()))
        } else {
            cm.requestNetwork(req, cb)
        }
    }

    private fun empurrar(l: InterfaceUpdateListener, n: Network) {
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val lp = cm.getLinkProperties(n) ?: return
            val nome: String = lp.interfaceName ?: return
            var idx = 0
            try {
                idx = java.net.NetworkInterface.getByName(nome)?.index ?: 0
            } catch (e: Throwable) { }
            val caps = cm.getNetworkCapabilities(n)
            val caro = caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            l.updateDefaultInterface(nome, idx, caro, false)
        } catch (e: Throwable) { }
    }

    private fun pararMonitor() {
        val cb = monitor ?: return
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb)
        } catch (e: Throwable) { }
        monitor = null
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        pararMonitor()
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val lista = ArrayList<NetworkInterface>()
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            for (n in cm.allNetworks) {
                val lp = cm.getLinkProperties(n) ?: continue
                val caps = cm.getNetworkCapabilities(n) ?: continue
                val nome: String = lp.interfaceName ?: continue
                val ni = java.net.NetworkInterface.getByName(nome) ?: continue
                val item = NetworkInterface()
                item.setIndex(ni.index)
                item.setMTU(ni.mtu)
                item.setName(nome)

                val ends = ArrayList<String>()
                for (a in ni.interfaceAddresses) {
                    val h: String = a.address.hostAddress ?: continue
                    ends.add(h.substringBefore('%') + "/" + a.networkPrefixLength)
                }
                item.setAddresses(ListaTexto(ends))

                var f = 0
                if (ni.isUp) f = f or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                if (ni.isLoopback) f = f or OsConstants.IFF_LOOPBACK
                if (ni.isPointToPoint) f = f or OsConstants.IFF_POINTOPOINT
                if (ni.supportsMulticast()) f = f or OsConstants.IFF_MULTICAST
                item.setFlags(f)

                val tipo: Int = if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    Libbox.InterfaceTypeWIFI
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    Libbox.InterfaceTypeCellular
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    Libbox.InterfaceTypeEthernet
                } else {
                    Libbox.InterfaceTypeOther
                }
                item.setType(tipo)

                val dnsLista = ArrayList<String>()
                for (d in lp.dnsServers) {
                    val h: String = d.hostAddress ?: continue
                    dnsLista.add(h.substringBefore('%'))
                }
                item.setDNSServer(ListaTexto(dnsLista))
                item.setGateway(ListaTexto(ArrayList<String>()))
                item.setMetered(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
                lista.add(item)
            }
        } catch (e: Throwable) { }
        return ListaInterfaces(lista)
    }

    override fun cancelNotification(tag: String, id: Int) { }
    override fun sendNotification(n: Notification) { }
    override fun checkPlatformShell() { }
    override fun clearDNSCache() { }
    override fun registerMyInterface(nome: String) { }
    override fun startNeighborMonitor(l: NeighborUpdateListener) { }
    override fun closeNeighborMonitor(l: NeighborUpdateListener) { }

    override fun includeAllNetworks(): Boolean = false
    override fun underNetworkExtension(): Boolean = false
    override fun usePlatformBridge(): Boolean = false
    override fun usePlatformShell(): Boolean = false
    override fun useProcFS(): Boolean = false
    override fun tailscaleHostname(): String = ""
    override fun localDNSTransport(): LocalDNSTransport? = null
    override fun readWIFIState(): WIFIState? = null

    override fun createBridge(options: BridgeOptions): BridgeSession {
        throw Exception("não suportado")
    }

    override fun findConnectionOwner(a: Int, b: String, c: Int, d: String, e: Int): ConnectionOwner {
        throw Exception("não suportado")
    }

    override fun lookupSFTPServer(): String {
        throw Exception("não suportado")
    }

    override fun lookupUser(nome: String): PlatformUser {
        throw Exception("não suportado")
    }

    override fun openShellSession(
        u: PlatformUser, a: String, b: StringIterator, c: String, d: Int, e: Int
    ): ShellSession {
        throw Exception("não suportado")
    }

    override fun readSystemSSHHostKey(): String {
        throw Exception("não suportado")
    }
}