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
import com.example.bikelight.MainActivity
import com.example.bikelight.data.local.AppDatabase
import com.example.bikelight.data.local.UserProfile
import com.example.bikelight.databinding.FragmentHomeDashboardBinding
import com.example.bikelight.security.CryptoUtils
import com.example.bikelight.ui.devices.SimuladorProximidadFragment
import com.example.bikelight.ui.settings.PreferenciasFragment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class HomeDashboardFragment : Fragment() {
    // Registra el contrato para solicitar permisos de SMS y ubicación (GPS) necesarios para el SOS automático
    private val sosPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val smsConcedido = permissions[Manifest.permission.SEND_SMS] == true
        val gpsConcedido = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (smsConcedido) {
            if (!gpsConcedido) {
                Toast.makeText(
                    requireContext(),
                    "Sin permiso de GPS se enviará la alerta sin coordenadas exactas",
                    Toast.LENGTH_SHORT
                ).show()
            }
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                SosSender.dispararAlertaSos(requireContext())
            }
        } else {
            Toast.makeText(
                requireContext(),
                "Se requiere permiso de SMS para enviar la alerta SOS automáticamente",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private var _binding: FragmentHomeDashboardBinding? = null
    private val binding get() = _binding!!

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

        // Configura la navegación de cada tarjeta del dashboard hacia su respectivo fragmento
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

        // Configuración de la alerta SOS de emergencia al hacer clic en el botón correspondiente
        binding.cardContactoEmergencia.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("¡ALERTA DE EMERGENCIA!")
                .setMessage("¿Deseas enviar inmediatamente el mensaje SOS a tu contacto de emergencia con tu ubicación?")
                .setPositiveButton("ENVIAR SOS") { _, _ ->
                    val tienePermisoSms = ContextCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.SEND_SMS
                    ) == PackageManager.PERMISSION_GRANTED

                    val tienePermisoFine = ContextCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED

                    val tienePermisoCoarse = ContextCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED

                    // Solicita permisos de SMS y ubicación si no están concedidos; de lo contrario, dispara el SOS directamente
                    if (!tienePermisoSms || (!tienePermisoFine && !tienePermisoCoarse)) {
                        sosPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.SEND_SMS,
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

    // Carga los datos del usuario en segundo plano y descifra información clave si no existe localmente
    private fun cargarDatosUsuario() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val contextSafe = context ?: return

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(contextSafe)
            val userLocal = db.userDao().getUserProfile(uid)

            if (userLocal == null) {
                FirebaseDatabase.getInstance().reference.child("users").child(uid).get()
                    .addOnSuccessListener { snapshot ->
                        val userFirebase = snapshot.getValue(UserProfile::class.java)
                        if (userFirebase != null) {
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
        _binding = null
    }
}