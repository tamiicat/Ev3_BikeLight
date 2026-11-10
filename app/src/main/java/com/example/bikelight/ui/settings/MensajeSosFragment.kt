package com.example.bikelight.ui.settings

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.bikelight.databinding.FragmentMensajeSosBinding
import com.example.bikelight.security.CryptoUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

class MensajeSosFragment : Fragment() {
    private var _binding: FragmentMensajeSosBinding? = null
    private val binding get() = _binding!!

    private val mensajePorDefecto = "¡EMERGENCIA! He sufrido un incidente en bicicleta."

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMensajeSosBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        cargarConfiguracion()

        // ver msj en tiempo real
        binding.etMensajeSos.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                actualizarVistaPrevia()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        // escucha cambios de switches para incluir ubicación e info de bicicleta
        binding.switchIncluirUbicacion.setOnCheckedChangeListener { _, _ ->
            actualizarVistaPrevia()
        }

        binding.switchIncluirBici.setOnCheckedChangeListener { _, _ ->
            actualizarVistaPrevia()
        }
        // guardar msj personalizado de ayuda
        binding.btnGuardarMensajeSos.setOnClickListener {
            guardarConfiguracion()
        }
        // restaurar valores predeterminados
        binding.btnRestaurarDefault.setOnClickListener {
            binding.etMensajeSos.setText(mensajePorDefecto)
            binding.switchIncluirUbicacion.isChecked = true
            binding.switchIncluirBici.isChecked = false
            actualizarVistaPrevia()
            Toast.makeText(requireContext(), "Valores predeterminados restaurados", Toast.LENGTH_SHORT).show()
        }
    }
    // carga configuración local y sincroniza co firebase
    private fun cargarConfiguracion() {
        val prefs = requireContext().getSharedPreferences("BikeLightPrefs", Context.MODE_PRIVATE)
        val mensajeGuardado = prefs.getString("sos_mensaje_texto", mensajePorDefecto) ?: mensajePorDefecto
        val incluirGps = prefs.getBoolean("sos_incluir_gps", true)
        val incluirBici = prefs.getBoolean("sos_incluir_bici", false)

        binding.etMensajeSos.setText(mensajeGuardado)
        binding.switchIncluirUbicacion.isChecked = incluirGps
        binding.switchIncluirBici.isChecked = incluirBici

        actualizarVistaPrevia()

        // sincronizar y descifra desde firebase
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseDatabase.getInstance().reference.child("users").child(uid).child("sosMessage").get()
            .addOnSuccessListener { snapshot ->
                val cifradoRemoto = snapshot.getValue(String::class.java)
                if (!cifradoRemoto.isNullOrBlank()) {
                    val descifrado = CryptoUtils.decrypt(cifradoRemoto)
                    if (_binding != null && descifrado.isNotBlank()) {
                        binding.etMensajeSos.setText(descifrado)
                        actualizarVistaPrevia()
                        // actualizar local
                        prefs.edit().putString("sos_mensaje_texto", descifrado).apply()
                    }
                }
            }
    }
    // actualiza vista previa con el texto ingresado
    private fun actualizarVistaPrevia() {
        val textoBase = binding.etMensajeSos.text.toString().trim().ifEmpty { mensajePorDefecto }
        val builder = StringBuilder(textoBase)

        if (binding.switchIncluirBici.isChecked) {
            builder.append("\n[Bicicleta: Ruta / Trek / Negra]")
        }

        if (binding.switchIncluirUbicacion.isChecked) {
            builder.append("\nUbicación: https://maps.google.com/?q=-33.4489,-70.6693")
        }

        binding.tvVistaPreviaMensaje.text = builder.toString()
    }
    // guarda el msj localmente y respalda en firebase
    private fun guardarConfiguracion() {
        val mensajeFinal = binding.etMensajeSos.text.toString().trim().ifEmpty { mensajePorDefecto }
        val incluirGps = binding.switchIncluirUbicacion.isChecked
        val incluirBici = binding.switchIncluirBici.isChecked

        // guardado local
        val prefs = requireContext().getSharedPreferences("BikeLightPrefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("sos_mensaje_texto", mensajeFinal)
            putBoolean("sos_incluir_gps", incluirGps)
            putBoolean("sos_incluir_bici", incluirBici)
            apply()
        }

        // respaldo cifrado en firebase
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid != null) {
            val dataCifrada = mapOf(
                "sosMessage" to CryptoUtils.encrypt(mensajeFinal)
            )
            FirebaseDatabase.getInstance().reference.child("users").child(uid)
                .updateChildren(dataCifrada)
        }

        Toast.makeText(requireContext(), "Configuración SOS guardada correctamente", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}