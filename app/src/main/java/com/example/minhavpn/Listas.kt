package com.example.minhavpn

import io.nekohasekai.libbox.NetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.StringIterator

class ListaTexto(private val itens: List<String>) : StringIterator {
    private var pos = 0
    override fun hasNext(): Boolean = pos < itens.size
    override fun len(): Int = itens.size
    override fun next(): String = itens[pos++]
}

class ListaInterfaces(private val itens: List<NetworkInterface>) : NetworkInterfaceIterator {
    private var pos = 0
    override fun hasNext(): Boolean = pos < itens.size
    override fun next(): NetworkInterface = itens[pos++]
}
