package com.example.bikelight.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.bikelight.data.local.AppDatabase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await

class SyncReadingsWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val db = AppDatabase.getDatabase(applicationContext)
            val dao = db.sensorReadingDao()
            val unsynced = dao.getUnsyncedReadings()

            if (unsynced.isEmpty()) {
                return Result.success()
            }

            val uid = FirebaseAuth.getInstance().currentUser?.uid
            if (uid.isNullOrEmpty()) {
                return Result.retry()
            }

            val databaseRef = FirebaseDatabase.getInstance().reference
                .child("users")
                .child(uid)
                .child("sensor_readings")

            for (reading in unsynced) {
                val data = mapOf(
                    "distanceCm" to reading.distanceCm,
                    "riskLevel" to reading.riskLevel,
                    "proximityMode" to reading.proximityMode,
                    "timestamp" to reading.timestamp
                )
                databaseRef.push().setValue(data).await()
                dao.markAsSynced(reading.id)
            }

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}
