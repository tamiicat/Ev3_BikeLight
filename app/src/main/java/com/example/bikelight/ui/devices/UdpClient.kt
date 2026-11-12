package com.example.bikelight

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

class UdpClient(private val ipAddress: String, private val port: Int) {

    private val sendExecutor = Executors.newSingleThreadExecutor()
    private val receiveExecutor = Executors.newSingleThreadExecutor()

    private var socket: DatagramSocket? = null

    @Volatile
    private var isListening = false

    // Callback que notificará a la app cada vez que llegue un dato del microcontrolador
    var onMessageReceived: ((String) -> Unit)? = null

    private fun getOrCreateSocket(): DatagramSocket {
        if (socket == null || socket!!.isClosed) {
            socket = DatagramSocket(port).apply {
                reuseAddress = true
            }
        }
        return socket!!
    }

    // Forzar la recreación del Socket cuando la red se reconecta
    fun resetSocket() {
        sendExecutor.execute {
            try {
                socket?.close()
                socket = DatagramSocket(port).apply {
                    reuseAddress = true
                }
                println("UdpClient: Socket UDP reiniciado correctamente en puerto $port")
                startListening()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Inicia la escucha continua de paquetes enviados por el ESP32 / Arduino
    fun startListening() {
        if (isListening) return
        isListening = true

        receiveExecutor.execute {
            val buffer = ByteArray(512)
            while (isListening) {
                try {
                    val activeSocket = getOrCreateSocket()
                    val packet = DatagramPacket(buffer, buffer.size)
                    activeSocket.receive(packet)

                    val mensaje = String(packet.data, 0, packet.length).trim()
                    if (mensaje.isNotEmpty()) {
                        println("UdpClient: Dato recibido del microcontrolador -> '$mensaje'")
                        onMessageReceived?.invoke(mensaje)
                    }
                } catch (e: Exception) {
                    if (isListening) {
                        println("UdpClient: Esperando reconexión del socket receptor (${e.message})")
                        try {
                            Thread.sleep(1000)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
            }
        }
    }

    // Enviar comando ('1' o '0' u otros datos)
    fun sendCommand(command: String) {
        sendExecutor.execute {
            try {
                val activeSocket = getOrCreateSocket()
                val address = InetAddress.getByName(ipAddress)
                val msgBytes = command.toByteArray()
                val packet = DatagramPacket(msgBytes, msgBytes.size, address, port)
                socket?.send(packet)
                println("UdpClient: Enviado '$command' a $ipAddress:$port")
            } catch (e: Exception) {
                println("UdpClient: Error al enviar comando: ${e.message}")
                socket?.close()
                socket = null
            }
        }
    }

    fun close() {
        isListening = false
        sendExecutor.execute {
            socket?.close()
            socket = null
        }
    }
}