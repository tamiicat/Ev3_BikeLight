package com.example.bikelight.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface UserDao {

    // obtiene el perfil completo
    @Query("SELECT * FROM user_profile WHERE uid = :userId LIMIT 1")
    suspend fun getUserProfile(userId: String): UserProfile?

    // guardar o sobrescribir perfil del usuario
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveUserProfile(profile: UserProfile): Long

    // actualiza los datos de la bicicleta
    @Query("""
        UPDATE user_profile 
        SET bikeTipo = :tipo, 
            bikeMarca = :marca, 
            bikeModelo = :modelo, 
            bikeAno = :ano, 
            bikeColor = :color 
        WHERE uid = :userId
    """)
    suspend fun updateBikeInfo(
        userId: String,
        tipo: String,
        marca: String,
        modelo: String,
        ano: String,
        color: String
    ): Int

    // actualizar datos personales básicos
    @Query("""
        UPDATE user_profile 
        SET fullName = :fullName, 
            phone = :phone 
        WHERE uid = :userId
    """)
    suspend fun updatePersonalInfo(
        userId: String,
        fullName: String,
        phone: String
    ): Int

    // actualizar modo de proximidad seleccionado
    @Query("UPDATE user_profile SET proximityMode = :mode WHERE uid = :userId")
    suspend fun updateProximityMode(userId: String, mode: String): Int

    // actualiza contacto de emergencia (para el botón SOS)
    @Query("""
        UPDATE user_profile 
        SET emergencyContactName = :name, 
            emergencyContactPhone = :phone, 
            emergencyContactEmail = :email 
        WHERE uid = :userId
    """)
    suspend fun updateEmergencyContact(
        userId: String,
        name: String,
        phone: String,
        email: String
    ): Int
}