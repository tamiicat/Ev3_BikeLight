package com.example.bikelight.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.bikelight.data.local.AppDatabase
import com.example.bikelight.security.CryptoUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

// función principal para armar y enviar la alerta SOS
object SosSender {
    suspend fun dispararAlertaSos(context: Context) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        val prefs = context.getSharedPreferences("BikeLightPrefs", Context.MODE_PRIVATE)

        // carga configuración del msj SOS y preferencias (GPS y bicicleta)
        val mensajeBase = prefs.getString("sos_mensaje_texto", "¡EMERGENCIA! He sufrido un incidente en bicicleta.")
            ?: "¡EMERGENCIA! He sufrido un incidente en bicicleta."
        val incluirGps = prefs.getBoolean("sos_incluir_gps", true)
        val incluirBici = prefs.getBoolean("sos_incluir_bici", false)

        var telefonoContacto = ""
        var datosBiciTexto = ""

        if (uid != null) {
            val db = AppDatabase.getDatabase(context)
            val user = db.userDao().getUserProfile(uid)

            // intenta obtener y descifrar el teléfono de emergencia
            if (user != null && !user.emergencyContactPhone.isNullOrBlank()) {
                // si numero está cifrado, se descrifra
                telefonoContacto = CryptoUtils.decrypt(user.emergencyContactPhone)
            } else {
                // si no está en Room, leer directamente de firebase
                try {
                    val snapshot = FirebaseDatabase.getInstance().reference
                        .child("users").child(uid).child("emergencyContactPhone").get().await()
                    val telCifrado = snapshot.getValue(String::class.java)
                    if (!telCifrado.isNullOrBlank()) {
                        telefonoContacto = CryptoUtils.decrypt(telCifrado)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            // prepara los datos de la bicicleta para incluirlos en el msj
            if (user != null && incluirBici) {
                datosBiciTexto = "\n[Bicicleta: ${user.bikeTipo} ${user.bikeMarca} ${user.bikeModelo} - Color: ${user.bikeColor}]"
            }
        }

        withContext(Dispatchers.Main) {
            // valida que existe un número de contacto de emergencia
            if (telefonoContacto.isBlank()) {
                Toast.makeText(context, "No tienes un teléfono de emergencia configurado. Ve a Preferencias.", Toast.LENGTH_LONG).show()
                return@withContext
            }

            val builder = StringBuilder(mensajeBase)

            if (incluirBici && datosBiciTexto.isNotBlank()) {
                builder.append(datosBiciTexto)
            }

            // obtener coordenadas GPS
            if (incluirGps) {
                val ubicacionActual = obtenerCoordenadasReales(context)
                if (ubicacionActual != null) {
                    val lat = ubicacionActual.latitude
                    val lng = ubicacionActual.longitude
                    builder.append("\nMi ubicación actual:\nhttps://maps.google.com/?q=$lat,$lng")
                } else {
                    builder.append("\n(No fue posible obtener coordenadas GPS en este momento. Revisa si la ubicación está activa)")
                }
            }

            val mensajeFinal = builder.toString()

            // dispara el 'Intent' para enviar un SMS
            try {
                val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("smsto:${telefonoContacto.replace(" ", "")}")
                    putExtra("sms_body", mensajeFinal)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(smsIntent)
            } catch (e: Exception) {
                Toast.makeText(context, "No se pudo abrir la aplicación de mensajes", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // obtiene la última ubicación del dispositivo mediante GPS o Red
    @SuppressLint("MissingPermission")
    private fun obtenerCoordenadasReales(context: Context): Location? {
        val hasFine = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            return null
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        val gpsLocation = if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } else null

        val networkLocation = if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } else null
        // compara cual ubicación es más reciente
        return when {
            gpsLocation != null && networkLocation != null -> {
                if (gpsLocation.time > networkLocation.time) gpsLocation else networkLocation
            }
            gpsLocation != null -> gpsLocation
            else -> networkLocation
        }
    }
}