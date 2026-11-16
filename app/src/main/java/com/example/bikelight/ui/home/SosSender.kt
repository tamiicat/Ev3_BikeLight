package com.example.bikelight.ui.home

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.telephony.SmsManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.bikelight.data.local.AppDatabase
import com.example.bikelight.security.CryptoUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

object SosSender {
    suspend fun dispararAlertaSos(context: Context) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        val prefs = context.getSharedPreferences("BikeLightPrefs", Context.MODE_PRIVATE)

        val mensajeBase = prefs.getString("sos_mensaje_texto", "¡EMERGENCIA! He sufrido un incidente en bicicleta.")
            ?: "¡EMERGENCIA! He sufrido un incidente en bicicleta."
        val incluirGps = prefs.getBoolean("sos_incluir_gps", true)
        val incluirBici = prefs.getBoolean("sos_incluir_bici", false)

        var telefonoContacto = ""
        var datosBiciTexto = ""

        if (uid != null) {
            val db = AppDatabase.getDatabase(context)
            val user = db.userDao().getUserProfile(uid)

            if (user != null && !user.emergencyContactPhone.isNullOrBlank()) {
                telefonoContacto = CryptoUtils.decrypt(user.emergencyContactPhone)
            } else {
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

            if (user != null && incluirBici) {
                datosBiciTexto = "\n[Bicicleta: ${user.bikeTipo} ${user.bikeMarca} ${user.bikeModelo} - Color: ${user.bikeColor}]"
            }
        }

        withContext(Dispatchers.Main) {
            if (telefonoContacto.isBlank()) {
                Toast.makeText(context, "No tienes un teléfono de emergencia configurado. Ve a Preferencias.", Toast.LENGTH_LONG).show()
                return@withContext
            }

            // Verificar que el permiso SEND_SMS esté concedido
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(context, "Permiso de SMS no concedido. Actívalo en los ajustes de la app.", Toast.LENGTH_LONG).show()
                return@withContext
            }

            val builder = StringBuilder(mensajeBase)

            if (incluirBici && datosBiciTexto.isNotBlank()) {
                builder.append(datosBiciTexto)
            }

            if (incluirGps) {
                val ubicacionActual = obtenerCoordenadasReales(context)
                if (ubicacionActual != null) {
                    val lat = ubicacionActual.latitude
                    val lng = ubicacionActual.longitude
                    builder.append("\nMi ubicación actual:\nhttps://maps.google.com/?q=$lat,$lng")
                } else {
                    builder.append("\n(No fue posible obtener coordenadas GPS en este momento)")
                }
            }

            val mensajeFinal = builder.toString()
            val numeroLimpio = telefonoContacto.replace(" ", "")

            // ENVÍO AUTOMÁTICO SIN ABRIR LA APP DE MENSAJES
            try {
                val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }

                // Divide el mensaje si supera los 160 caracteres (común al incluir links de Maps)
                val partes = smsManager.divideMessage(mensajeFinal)
                smsManager.sendMultipartTextMessage(numeroLimpio, null, partes, null, null)

                Toast.makeText(context, "¡Alerta SOS enviada automáticamente!", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "Error al enviar el SMS: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun obtenerCoordenadasReales(context: Context): Location? {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) return null

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        val gpsLocation = if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } else null

        val networkLocation = if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } else null

        return when {
            gpsLocation != null && networkLocation != null -> {
                if (gpsLocation.time > networkLocation.time) gpsLocation else networkLocation
            }
            gpsLocation != null -> gpsLocation
            else -> networkLocation
        }
    }
}