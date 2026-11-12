package com.example.bikelight.ui.devices

import android.net.Network
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** Los dos dispositivos de la red BikeLight. */
enum class Nodo(val id: String, val nombre: String) {
    A("A", "Arduino (Nodo A)"),
    B("B", "ESP32 (Nodo B)");

    companion object {
        val todos: Set<Nodo> = values().toSet()
    }
}

object UdpClientManager {
    private const val TAG = "UdpClientManager"
    private const val UDP_PORT = 4210
    private const val ACK_TIMEOUT_MS = 800L
    private const val MAX_INTENTOS = 3

    /**
     * Broadcast de la red del ESP32: llega a los dos nodos. Cada nodo decide si el
     * comando es para él según el campo opcional "nodo" ("A" o "B") del JSON.
     */
    var targetIp: String = "192.168.4.255"

    // Red del ESP32 (sin internet). La asigna MainActivity al conectarse.
    @Volatile
    var esp32Network: Network? = null

    /**
     * Envía el JSON a los nodos indicados y devuelve los que CONFIRMARON (ACK).
     * - nodos = ambos (por defecto): el JSON se envía tal cual y lo aplican los dos.
     * - nodos = uno solo: se añade "nodo" al JSON para que solo ese nodo lo aplique.
     * Los reintentos solo se dirigen a los nodos que aún no confirmaron.
     */
    suspend fun enviar(commandJson: String, nodos: Set<Nodo> = Nodo.todos): Set<Nodo> =
        withContext(Dispatchers.IO) {
            val network = esp32Network
            if (network == null) {
                Log.w(TAG, "Sin red del ESP32: comando no enviado")
                return@withContext emptySet()
            }

            val confirmados = mutableSetOf<Nodo>()

            for (intento in 1..MAX_INTENTOS) {
                val pendientes = nodos - confirmados
                if (pendientes.isEmpty()) break

                try {
                    DatagramSocket().use { socket ->
                        socket.broadcast = true
                        // Fuerza el envío por el Wi-Fi del ESP32 y no por datos móviles
                        network.bindSocket(socket)

                        val payload = armarPayload(commandJson, pendientes).toByteArray(Charsets.UTF_8)
                        socket.send(
                            DatagramPacket(payload, payload.size, InetAddress.getByName(targetIp), UDP_PORT)
                        )

                        // Esperar ACK de todos los pendientes dentro de la ventana de tiempo
                        val limite = SystemClock.elapsedRealtime() + ACK_TIMEOUT_MS
                        val buf = ByteArray(128)
                        while (!confirmados.containsAll(nodos)) {
                            val restante = limite - SystemClock.elapsedRealtime()
                            if (restante <= 0) break
                            socket.soTimeout = restante.toInt()

                            val respuesta = DatagramPacket(buf, buf.size)
                            try {
                                socket.receive(respuesta)
                            } catch (e: SocketTimeoutException) {
                                break
                            }

                            val nodo = interpretarAck(String(respuesta.data, 0, respuesta.length, Charsets.UTF_8))
                            if (nodo != null && nodo in nodos) {
                                confirmados += nodo
                                Log.d(TAG, "ACK de ${nodo.nombre} (intento $intento)")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error UDP (intento $intento/$MAX_INTENTOS)", e)
                }
            }
            confirmados
        }

    /** Compatibilidad: true solo si TODOS los nodos pedidos confirmaron. */
    suspend fun sendCommand(commandJson: String, nodos: Set<Nodo> = Nodo.todos): Boolean =
        enviar(commandJson, nodos).containsAll(nodos)

    // Si se pide un solo nodo, se añade "nodo" para que el otro ignore el comando
    private fun armarPayload(json: String, pendientes: Set<Nodo>): String {
        if (pendientes.size >= Nodo.todos.size) return json
        return try {
            JSONObject(json).put("nodo", pendientes.first().id).toString()
        } catch (e: Exception) {
            json
        }
    }

    // Devuelve el nodo que confirmó, o null si el ACK no es válido
    private fun interpretarAck(texto: String): Nodo? {
        return try {
            val obj = JSONObject(texto)
            if (!obj.optBoolean("ok", false)) return null
            Nodo.values().firstOrNull { it.id == obj.optString("nodo") }
        } catch (e: Exception) {
            null
        }
    }
}