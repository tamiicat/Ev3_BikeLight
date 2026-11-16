package com.example.bikelight.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.bikelight.MainActivity
import com.example.bikelight.data.local.AppDatabase
import com.example.bikelight.databinding.FragmentModoProximidadBinding
import com.example.bikelight.ui.devices.SensorProximidad
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ModoProximidadFragment : Fragment() {
    private var _binding: FragmentModoProximidadBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentModoProximidadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cargarModoActual()
        binding.cardModoCarretera.setOnClickListener { cambiarModo("Carretera") }
        binding.cardModoCiclovia.setOnClickListener { cambiarModo("Ciclovía") }
        binding.cardModoCiudad.setOnClickListener { cambiarModo("Ciudad") }
        binding.cardModoNocturno.setOnClickListener { cambiarModo("Nocturno") }
    }

    private fun cargarModoActual() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val appContext = requireContext().applicationContext
        val db = AppDatabase.getDatabase(appContext)

        lifecycleScope.launch(Dispatchers.IO) {
            val user = db.userDao().getUserProfile(uid)
            val modo = user?.proximityMode ?: ModosProximidad.MODO_POR_DEFECTO

            ModosProximidad.guardarModo(appContext, modo)
            SensorProximidad.establecerRango(ModosProximidad.rangoCm(modo))

            withContext(Dispatchers.Main) {
                _binding?.tvModoSeleccionado?.text = "Modo activo: $modo"
            }
        }
    }

    private fun cambiarModo(nuevoModo: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val appContext = requireContext().applicationContext
        val db = AppDatabase.getDatabase(appContext)
        val rangoCm = ModosProximidad.rangoCm(nuevoModo)

        binding.tvModoSeleccionado.text = "Modo activo: $nuevoModo"

        ModosProximidad.guardarModo(appContext, nuevoModo)
        SensorProximidad.fijarRango(rangoCm)

        (activity as? MainActivity)?.programarSincronizacionFirebase()

        lifecycleScope.launch(Dispatchers.IO) {
            db.userDao().updateProximityMode(uid, nuevoModo)

            FirebaseDatabase.getInstance().reference.child("users").child(uid)
                .child("proximityMode").setValue(nuevoModo)

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    appContext,
                    "Modo $nuevoModo activado (rango: $rangoCm cm)",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}