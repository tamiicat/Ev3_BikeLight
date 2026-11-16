package com.example.bikelight.data.local

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SensorReadingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReading(reading: SensorReading): Long

    @Query("SELECT * FROM sensor_readings WHERE isSynced = 0 ORDER BY timestamp ASC")
    suspend fun getUnsyncedReadings(): List<SensorReading>

    @Query("UPDATE sensor_readings SET isSynced = 1 WHERE id = :readingId")
    suspend fun markAsSynced(readingId: Int): Int

    @Query("SELECT COUNT(*) FROM sensor_readings WHERE isSynced = 0")
    fun observePendingCount(): LiveData<Int>

    @Query("SELECT * FROM sensor_readings ORDER BY timestamp DESC LIMIT 15")
    fun observeRecentReadings(): LiveData<List<SensorReading>>

    @Query("SELECT * FROM sensor_readings ORDER BY timestamp DESC")
    fun observeAllReadings(): LiveData<List<SensorReading>>
}