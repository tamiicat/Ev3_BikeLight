package com.example.bikelight.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.bikelight.MainActivity
import com.example.bikelight.data.local.AppDatabase
import com.example.bikelight.data.local.SensorReading
import com.example.bikelight.data.local.UserProfile
import com.example.bikelight.data.sync.SyncReadingsWorker
import com.example.bikelight.databinding.FragmentHomeDashboardBinding
import com.example.bikelight.security.CryptoUtils
import com.example.bikelight.ui.devices.SimuladorProximidadFragment
import com.example.bikelight.ui.settings.PreferenciasFragment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

class HomeDashboardFragment : Fragment() {

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val concedido = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (concedido) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                SosSender.dispararAlertaSos(requireContext())
            }
        } else {
            Toast.makeText(
                requireContext(),
                "Sin permiso de GPS se enviará la alerta sin coordenadas exactas",
                Toast.LENGTH_SHORT
            ).show()
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                SosSender.dispararAlertaSos(requireContext())
            }
        }
    }

    private var _binding: FragmentHomeDashboardBinding? = null
    private val binding get() = _binding!!

    private var modoProximidadActual: String = "Ciudad"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val mainActivity = activity as? MainActivity

        cargarDatosUsuario()
        iniciarRecepcionOfflineFirst(mainActivity)
        programarSincronizacionFirebase()

        binding.cardSimuladorProximidad.setOnClickListener {
            mainActivity?.cargarFragment(SimuladorProximidadFragment(), "Proximidad del Sensor")
        }

        binding.cardEstadoWifi.setOnClickListener {
            mainActivity?.cargarFragment(EstadoWifiFragment(), "Estado WiFi")
        }

        binding.cardConfigWifi.setOnClickListener {
            mainActivity?.cargarFragment(WifiConfigFragment(), "Configuración Wi-Fi")
        }

        binding.cardBicicleta.setOnClickListener {
            mainActivity?.cargarFragment(BicicletaFragment(), "Mi Bicicleta")
        }

        binding.cardModoProximidad.setOnClickListener {
            mainActivity?.cargarFragment(ModoProximidadFragment(), "Modo Proximidad")
        }

        binding.cardConfigBuzzer.setOnClickListener {
            mainActivity?.cargarFragment(BuzzerFragment(), "Configuración Buzzer")
        }

        binding.cardConfigLed.setOnClickListener {
            mainActivity?.cargarFragment(LedConfigFragment(), "Configuración LED")
        }

        binding.cardPreferencias.setOnClickListener {
            mainActivity?.cargarFragment(PreferenciasFragment(), "Preferencias")
        }

        binding.cardContactoEmergencia.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("¡ALERTA DE EMERGENCIA!")
                .setMessage("¿Deseas enviar inmediatamente el mensaje SOS a tu contacto de emergencia con tu ubicación?")
                .setPositiveButton("ENVIAR SOS") { _, _ ->
                    val tienePermisoFine = ContextCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED

                    val tienePermisoCoarse = ContextCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED

                    if (!tienePermisoFine && !tienePermisoCoarse) {
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    } else {
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            SosSender.dispararAlertaSos(requireContext())
                        }
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun iniciarRecepcionOfflineFirst(mainActivity: MainActivity?) {
        val udp = mainActivity?.udpClient ?: return
        udp.startListening()

        udp.onMessageReceived = { rawPayload ->
            val contextSafe = context
            if (contextSafe != null) {
                val uid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
                val lecturaParseada = parsearPaqueteMicrocontrolador(rawPayload, uid)

                if (lecturaParseada != null) {
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        val db = AppDatabase.getDatabase(contextSafe)
                        db.sensorReadingDao().insertReading(lecturaParseada)
                        programarSincronizacionFirebase()
                    }
                }
            }
        }
    }

    private fun parsearPaqueteMicrocontrolador(payload: String, uid: String): SensorReading? {
        return try {
            val ahora = System.currentTimeMillis()
            if (payload.startsWith("{")) {
                val json = JSONObject(payload)
                val dist = json.optDouble("distancia", json.optDouble("distance", -1.0)).toFloat()
                if (dist < 0f) return null
                val riesgo = json.optString("riesgo", calcularNivelRiesgo(dist))
                SensorReading(
                    uid = uid,
                    distanceCm = dist,
                    riskLevel = riesgo,
                    proximityMode = modoProximidadActual,
                    timestamp = ahora,
                    isSynced = false
                )
            } else {
                val partes = payload.split(",")
                val dist = partes[0].trim().toFloatOrNull() ?: return null
                val riesgo = if (partes.size > 1) partes[1].trim() else calcularNivelRiesgo(dist)
                SensorReading(
                    uid = uid,
                    distanceCm = dist,
                    riskLevel = riesgo,
                    proximityMode = modoProximidadActual,
                    timestamp = ahora,
                    isSynced = false
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun calcularNivelRiesgo(distanciaCm: Float): String {
        return when {
            distanciaCm <= 100f -> "CRITICO"
            distanciaCm <= 200f -> "MEDIO"
            else -> "BAJO"
        }
    }

    private fun programarSincronizacionFirebase() {
        val contextSafe = context ?: return

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncWorkRequest = OneTimeWorkRequestBuilder<SyncReadingsWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(contextSafe).enqueueUniqueWork(
            "SyncSensorReadingsOfflineWork",
            ExistingWorkPolicy.KEEP,
            syncWorkRequest
        )
    }

    fun enviarComandoAlEsp32(comando: String) {
        val mainActivity = activity as? MainActivity
        mainActivity?.udpClient?.sendCommand(comando)
    }

    private fun cargarDatosUsuario() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val contextSafe = context ?: return

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(contextSafe)
            val userLocal = db.userDao().getUserProfile(uid)

            if (userLocal != null) {
                modoProximidadActual = userLocal.proximityMode
            } else {
                FirebaseDatabase.getInstance().reference.child("users").child(uid).get()
                    .addOnSuccessListener { snapshot ->
                        val userFirebase = snapshot.getValue(UserProfile::class.java)
                        if (userFirebase != null) {
                            modoProximidadActual = userFirebase.proximityMode
                            val userDescifrado = userFirebase.copy(
                                fullName = CryptoUtils.decrypt(userFirebase.fullName),
                                phone = CryptoUtils.decrypt(userFirebase.phone),
                                emergencyContactName = CryptoUtils.decrypt(userFirebase.emergencyContactName),
                                emergencyContactPhone = CryptoUtils.decrypt(userFirebase.emergencyContactPhone),
                                emergencyContactEmail = CryptoUtils.decrypt(userFirebase.emergencyContactEmail)
                            )
                            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                                db.userDao().saveUserProfile(userDescifrado)
                            }
                        }
                    }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        val mainActivity = activity as? MainActivity
        mainActivity?.udpClient?.onMessageReceived = null
        _binding = null
    }
}