package com.example.bikelight.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.bikelight.data.local.AppDatabase
import com.example.bikelight.data.local.SensorReading
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SyncReadingsWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.getDatabase(applicationContext)
        val dao = db.sensorReadingDao()
        val pendientes: List<SensorReading> = dao.getUnsyncedReadings()

        Log.d("BikeLightSync", "Iniciando Worker. Lecturas pendientes: ${pendientes.size}")

        if (pendientes.isEmpty()) {
            return Result.success()
        }

        val currentUid = FirebaseAuth.getInstance().currentUser?.uid
        if (currentUid == null && pendientes.all { it.uid.isEmpty() }) {
            Log.e("BikeLightSync", "Error: No hay usuario autenticado (UID nulo)")
            return Result.retry()
        }

        // Buscar una red con salida real a Internet (ej. Datos Móviles) en caso de estar atado al Wi-Fi local del ESP32
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val redPrevia = cm.boundNetworkForProcess
        val redConInternet = cm.allNetworks.firstOrNull { net ->
            val caps = cm.getNetworkCapabilities(net)
            caps != null &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

        if (redConInternet != null && redPrevia != redConInternet) {
            cm.bindProcessToNetwork(redConInternet)
            Log.d("BikeLightSync", "Enrutando sincronización por red con salida a Internet")
        }

        val firebaseRef = FirebaseDatabase.getInstance().reference

        return try {
            for (lectura in pendientes) {
                val targetUid = lectura.uid.ifEmpty { currentUid } ?: continue

                val payload = mapOf(
                    "distanceCm" to lectura.distanceCm,
                    "riskLevel" to lectura.riskLevel,
                    "proximityMode" to lectura.proximityMode,
                    "timestamp" to lectura.timestamp
                )

                val nodoLecturas = firebaseRef.child("users")
                    .child(targetUid)
                    .child("sensor_readings")

                val key = nodoLecturas.push().key ?: lectura.timestamp.toString()

                nodoLecturas.child(key)
                    .setValue(payload)
                    .awaitTask()

                dao.markAsSynced(lectura.id)
                Log.d("BikeLightSync", "Lectura ID=${lectura.id} subida con clave=$key")
            }
            Result.success()
        } catch (e: Exception) {
            Log.e("BikeLightSync", "Fallo al subir a Realtime Database: ${e.message}", e)
            Result.retry()
        } finally {
            // Restaurar el enlace de red hacia el ESP32 si estaba activo antes de iniciar el Worker
            if (redPrevia != null && cm.boundNetworkForProcess != redPrevia) {
                cm.bindProcessToNetwork(redPrevia)
            }
        }
    }

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result ->
            cont.resume(result)
        }
        addOnFailureListener { exception ->
            cont.resumeWithException(exception)
        }
    }
}