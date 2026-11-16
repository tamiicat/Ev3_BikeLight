package com.example.bikelight.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
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
        // configura las tarjetas de modo proximidad
        binding.cardModoCarretera.setOnClickListener { cambiarModo("Carretera") }
        binding.cardModoCiclovia.setOnClickListener { cambiarModo("Ciclovía") }
        binding.cardModoCiudad.setOnClickListener { cambiarModo("Ciudad") }
        binding.cardModoNocturno.setOnClickListener { cambiarModo("Nocturno") }
    }

    // consulta el modo actual almacenado en Room y lo muestra en la parte superior
    private fun cargarModoActual() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val appContext = requireContext().applicationContext
        val db = AppDatabase.getDatabase(appContext)

        lifecycleScope.launch(Dispatchers.IO) {
            val user = db.userDao().getUserProfile(uid)
            val modo = user?.proximityMode ?: ModosProximidad.MODO_POR_DEFECTO

            // deja la copia local alineada con Room (el simulador lee de aquí)
            ModosProximidad.guardarModo(appContext, modo)

            withContext(Dispatchers.Main) {
                _binding?.tvModoSeleccionado?.text = "Modo activo: $modo"
            }
        }
    }

    // cambia y guarda el nuevo modo seleccionado en bd local y realtime database
    private fun cambiarModo(nuevoModo: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val appContext = requireContext().applicationContext
        val db = AppDatabase.getDatabase(appContext)
        val rangoCm = ModosProximidad.rangoCm(nuevoModo)

        binding.tvModoSeleccionado.text = "Modo activo: $nuevoModo"

        // copia local inmediata: el simulador y el envío al Arduino leen de aquí
        ModosProximidad.guardarModo(appContext, nuevoModo)

        // envía el nuevo rango al Arduino (Nodo A) de inmediato y lo deja memorizado
        // para las próximas suscripciones
        SensorProximidad.fijarRango(rangoCm)

        lifecycleScope.launch(Dispatchers.IO) {
            // guardar localmente
            db.userDao().updateProximityMode(uid, nuevoModo)

            // sincronizar en firebase
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