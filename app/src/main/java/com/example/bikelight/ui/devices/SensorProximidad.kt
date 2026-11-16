package com.example.bikelight.ui.devices

import android.net.Network
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Lectura del sensor ultrasónico del Arduino (Nodo A).
 * @param cm distancia en centímetros, o -1 si el sensor no recibió eco (fuera de alcance)
 * @param umbralCm distancia a la que el Arduino activa la alerta (LEDs/buzzer)
 */
data class LecturaProximidad(val cm: Int, val umbralCm: Int)

object SensorProximidad {
    private const val TAG = "SensorProximidad"
    private const val PUERTO_ARDUINO = 4210
    private const val RENOVAR_SUSCRIPCION_MS = 2000L   // el Arduino mantiene la suscripción 5 s
    private const val SIN_SENAL_MS = 1500L             // sin paquetes durante este tiempo = sin señal
    private const val TIMEOUT_RECEPCION_MS = 300

    private const val INTENTOS_UMBRAL = 3              // reintentos al enviar el rango (UDP no garantiza entrega)
    private const val ESPERA_ACK_MS = 400

    // Rango de detección (cm) del modo de proximidad activo. 0 = aún no definido.
    @Volatile private var rangoCm: Int = 0

    // Alcance propio para que el envío no se cancele si el usuario sale de la pantalla
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // La suscripción lleva el rango: si el Arduino se reinicia, lo recupera en ≤ 2 s
    private fun suscripcion(): String {
        val r = rangoCm
        return if (r > 0) """{"suscribir":true,"nodo":"A","umbral":$r}"""
        else """{"suscribir":true,"nodo":"A"}"""
    }

    /** Solo memoriza el rango; se enviará en la próxima suscripción. */
    fun establecerRango(cm: Int) {
        rangoCm = cm
    }

    /**
     * Memoriza el rango y se lo envía de inmediato al Arduino (Nodo A),
     * aunque la pantalla del simulador no esté abierta. Llamar al cambiar de modo
     * y también cuando la app se conecte a la red del ESP32.
     */
    fun fijarRango(cm: Int) {
        rangoCm = cm
        scope.launch { enviarRango(cm) }
    }

    // Envía {"nodo":"A","umbral":cm} y espera el ACK del Arduino ({"ok":true,"nodo":"A"})
    private fun enviarRango(cm: Int): Boolean {
        val red = UdpClientManager.esp32Network
        if (red == null) {
            Log.w(TAG, "Sin red del ESP32: el rango se enviará con la próxima suscripción")
            return false
        }
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket().also {
                red.bindSocket(it)
                it.broadcast = true
                it.soTimeout = ESPERA_ACK_MS
            }
            val datos = """{"nodo":"A","umbral":$cm}""".toByteArray(Charsets.UTF_8)
            val destino = InetAddress.getByName(UdpClientManager.targetIp)
            val buffer = ByteArray(256)

            for (intento in 1..INTENTOS_UMBRAL) {
                socket.send(DatagramPacket(datos, datos.size, destino, PUERTO_ARDUINO))
                try {
                    val paquete = DatagramPacket(buffer, buffer.size)
                    socket.receive(paquete)
                    val obj = JSONObject(String(paquete.data, 0, paquete.length, Charsets.UTF_8))
                    if (obj.optBoolean("ok") && obj.optString("nodo") == "A") {
                        Log.d(TAG, "Arduino confirmó rango = $cm cm (intento $intento)")
                        return true
                    }
                } catch (e: SocketTimeoutException) {
                    // sin ACK: se reintenta
                }
            }
            Log.w(TAG, "El Arduino no confirmó el rango $cm cm")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error enviando el rango", e)
            false
        } finally {
            socket?.close()
        }
    }

    /**
     * Flujo de lecturas en tiempo real mientras se esté recolectando.
     * Emite null cuando no hay conexión con la red del ESP32 o el Arduino no envía datos.
     * Al cancelarse la recolección (p. ej. al salir de la pantalla) se cierra el socket
     * y el Arduino deja de transmitir cuando vence la suscripción.
     */
    fun lecturas(): Flow<LecturaProximidad?> = flow {
        var socket: DatagramSocket? = null
        var redDelSocket: Network? = null
        var ultimaSuscripcion = 0L
        var ultimoPaquete = 0L
        val buffer = ByteArray(256)

        try {
            while (currentCoroutineContext().isActive) {
                var salida: LecturaProximidad? = null
                var emitir = false
                var pausaMs = 0L

                try {
                    val red = UdpClientManager.esp32Network
                    if (red == null) {
                        // Sin conexión con el ESP32
                        socket?.close()
                        socket = null
                        redDelSocket = null
                        emitir = true
                        pausaMs = 500
                    } else {
                        // (Re)crear el socket si es la primera vez o cambió la red
                        if (socket == null || red !== redDelSocket) {
                            socket?.close()
                            socket = DatagramSocket().also {
                                red.bindSocket(it)      // por el Wi-Fi del ESP32, no por datos móviles
                                it.broadcast = true
                                it.soTimeout = TIMEOUT_RECEPCION_MS
                            }
                            redDelSocket = red
                            ultimaSuscripcion = 0L
                        }
                        val s = socket!!

                        // Renovar la suscripción (el Arduino responde a la IP/puerto de este socket)
                        val ahora = SystemClock.elapsedRealtime()
                        if (ahora - ultimaSuscripcion >= RENOVAR_SUSCRIPCION_MS) {
                            val datos = suscripcion().toByteArray(Charsets.UTF_8)
                            s.send(
                                DatagramPacket(
                                    datos, datos.size,
                                    InetAddress.getByName(UdpClientManager.targetIp), PUERTO_ARDUINO
                                )
                            )
                            ultimaSuscripcion = ahora
                        }

                        // Esperar una lectura
                        try {
                            val paquete = DatagramPacket(buffer, buffer.size)
                            s.receive(paquete)
                            val lectura = interpretar(String(paquete.data, 0, paquete.length, Charsets.UTF_8))
                            if (lectura != null) {
                                ultimoPaquete = SystemClock.elapsedRealtime()
                                salida = lectura
                                emitir = true
                            }
                        } catch (e: SocketTimeoutException) {
                            if (SystemClock.elapsedRealtime() - ultimoPaquete > SIN_SENAL_MS) {
                                salida = null
                                emitir = true
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error en la recepción", e)
                    socket?.close()
                    socket = null
                    redDelSocket = null
                    salida = null
                    emitir = true
                    pausaMs = 1000
                }

                if (emitir) emit(salida)
                if (pausaMs > 0) delay(pausaMs)
            }
        } finally {
            socket?.close()
        }
    }.flowOn(Dispatchers.IO)

    // Solo interesan los paquetes {"evt":"dist","cm":57,"umbral":10}; el resto (ACK, etc.) se ignora
    private fun interpretar(texto: String): LecturaProximidad? {
        return try {
            val obj = JSONObject(texto)
            if (obj.optString("evt") != "dist") return null
            LecturaProximidad(
                cm = obj.optInt("cm", -1),
                umbralCm = obj.optInt("umbral", 10)
            )
        } catch (e: Exception) {
            null
        }
    }
}