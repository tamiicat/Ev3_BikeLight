package com.example.bikelight

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

class UdpClient(private val ipAddress: String, private val port: Int) {

    private val executor = Executors.newSingleThreadExecutor()
    private var socket: DatagramSocket? = null

    // Forzar la recreación del Socket cuando la red se reconecta
    fun resetSocket() {
        executor.execute {
            try {
                socket?.close()
                socket = DatagramSocket()
                println("UdpClient: Socket UDP reiniciado correctamente")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Enviar comando ('1' o '0' u otros datos)
    fun sendCommand(command: String) {
        executor.execute {
            try {
                if (socket == null || socket!!.isClosed) {
                    socket = DatagramSocket()
                }
                val address = InetAddress.getByName(ipAddress)
                val msgBytes = command.toByteArray()
                val packet = DatagramPacket(msgBytes, msgBytes.size, address, port)
                socket?.send(packet)
                println("UdpClient: Enviado '$command' a $ipAddress:$port")
            } catch (e: Exception) {
                println("UdpClient: Error al enviar comando: ${e.message}")
                // Si falla el envío, liberamos el socket para reintentar en la próxima reconexión
                socket?.close()
                socket = null
            }
        }
    }

    fun close() {
        executor.execute {
            socket?.close()
            socket = null
        }
    }
}