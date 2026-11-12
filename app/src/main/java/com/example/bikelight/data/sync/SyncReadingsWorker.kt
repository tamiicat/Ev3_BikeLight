package com.example.bikelight.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
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

    override suspend fun doWork(): ListenableWorker.Result {
        val db = AppDatabase.getDatabase(applicationContext)
        val dao = db.sensorReadingDao()
        val pendientes: List<SensorReading> = dao.getUnsyncedReadings()

        if (pendientes.isEmpty()) {
            return ListenableWorker.Result.success()
        }

        val currentUid = FirebaseAuth.getInstance().currentUser?.uid
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

                val key = firebaseRef.child("users")
                    .child(targetUid)
                    .child("sensor_readings")
                    .push()
                    .key ?: lectura.timestamp.toString()

                firebaseRef.child("users")
                    .child(targetUid)
                    .child("sensor_readings")
                    .child(key)
                    .setValue(payload)
                    .awaitTask()

                dao.markAsSynced(lectura.id)
            }
            ListenableWorker.Result.success()
        } catch (e: Exception) {
            ListenableWorker.Result.retry()
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