package com.example.bikelight.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [UserProfile::class], version = 2, exportSchema = false) // <- Se sube la versión a 2
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        // obtiene instancia única de bd
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bikelight_db"
                )
                    .fallbackToDestructiveMigration() // evita el cierre forzos
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}