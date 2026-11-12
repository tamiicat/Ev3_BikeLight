package com.example.bikelight.ui.devices

import android.net.Network
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

object UdpClientManager {
    private const val TAG = "UdpClientManager"
    private const val UDP_PORT = 4210
    private const val ACK_TIMEOUT_MS = 800
    private const val MAX_INTENTOS = 3

    /**
     * Destino de los comandos de LEDs.
     * - "192.168.4.255": broadcast de la red del ESP32 -> llega al Arduino (Nodo A)
     *   sin necesidad de conocer su IP (que asigna el DHCP del ESP32).
     * - Si prefieres IP fija del Arduino, cámbiala aquí (ej. "192.168.4.2").
     * - "192.168.4.1" solo sirve si el ESP32 reenvía los JSON al Arduino.
     */
    var targetIp: String = "192.168.4.255"

    // Red del ESP32 (sin internet). La asigna MainActivity al conectarse.
    @Volatile
    var esp32Network: Network? = null

    /**
     * Envía el JSON y espera la confirmación {"ok":true} del Arduino.
     * Devuelve true solo si el dispositivo confirmó el comando.
     */
    suspend fun sendCommand(commandJson: String): Boolean = withContext(Dispatchers.IO) {
        val network = esp32Network
        if (network == null) {
            Log.w(TAG, "Sin red del ESP32: comando no enviado")
            return@withContext false
        }

        val data = commandJson.toByteArray(Charsets.UTF_8)

        for (intento in 1..MAX_INTENTOS) {
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = ACK_TIMEOUT_MS
                    // Fuerza el envío por el Wi-Fi del ESP32 y no por datos móviles
                    network.bindSocket(socket)

                    val address = InetAddress.getByName(targetIp)
                    socket.send(DatagramPacket(data, data.size, address, UDP_PORT))

                    val buf = ByteArray(128)
                    val respuesta = DatagramPacket(buf, buf.size)
                    socket.receive(respuesta)

                    val texto = String(respuesta.data, 0, respuesta.length, Charsets.UTF_8)
                    if (texto.contains("\"ok\":true")) {
                        Log.d(TAG, "ACK recibido (intento $intento): $texto")
                        return@withContext true
                    }
                }
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "Sin ACK (intento $intento/$MAX_INTENTOS)")
            } catch (e: Exception) {
                Log.e(TAG, "Error UDP (intento $intento/$MAX_INTENTOS)", e)
            }
        }
        false
    }
}