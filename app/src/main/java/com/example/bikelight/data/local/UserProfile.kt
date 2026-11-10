package com.example.bikelight.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_profile")
data class UserProfile(
    @PrimaryKey
    // campos usuario
    val uid: String = "",
    val email: String = "",
    val fullName: String = "",
    val username: String = "",
    val phone: String = "",
    val emergencyContactName: String = "",
    val emergencyContactPhone: String = "",
    val emergencyContactEmail: String = "",
    val proximityMode: String = "Ciudad", // urbano, carretera, ciclovia, nocturno
    // campos bicicleta
    var bikeTipo: String = "",
    var bikeMarca: String = "",
    var bikeModelo: String = "",
    var bikeAno: Int = 0,
    var bikeColor: String = ""
)