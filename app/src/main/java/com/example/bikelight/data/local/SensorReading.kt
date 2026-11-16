package com.example.bikelight.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sensor_readings")
data class SensorReading(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val uid: String = "",
    val distanceCm: Float = 0f,
    val riskLevel: String = "BAJO",
    val proximityMode: String = "Ciudad",
    val timestamp: Long = 0L,
    val isSynced: Boolean = false
)