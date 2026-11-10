package com.example.bikelight.ui.settings

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.example.bikelight.MainActivity
import com.example.bikelight.databinding.FragmentPreferenciasBinding

class PreferenciasFragment : Fragment() {
    private var _binding: FragmentPreferenciasBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPreferenciasBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val mainActivity = activity as? MainActivity

        // tema aplicación
        binding.cardTemaApp.setOnClickListener {
            mostrarDialogoTema()
        }

        // unidades de medidas
        binding.cardUnidadesMedida.setOnClickListener {
            mainActivity?.cargarFragment(UnidadesMedidaFragment(), "Unidades de Medida")
        }

        // contacto de emergencia
        binding.cardConfigContacto.setOnClickListener {
            mainActivity?.cargarFragment(EmergencyContactFragment(), "Contacto de Emergencia")
        }

        // mensaje SOS
        binding.cardConfigMensajeSos.setOnClickListener {
            mainActivity?.cargarFragment(MensajeSosFragment(), "Configuración Mensaje SOS")
        }
    }
    // selección del tema de aplicación
    private fun mostrarDialogoTema() {
        val prefs = requireContext().getSharedPreferences("BikeLightPrefs", Context.MODE_PRIVATE)
        val modoActual = prefs.getInt("app_theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        val opciones = arrayOf("Modo Claro", "Modo Oscuro", "Predeterminado del sistema")

        val indiceSeleccionado = when (modoActual) {
            AppCompatDelegate.MODE_NIGHT_NO -> 0
            AppCompatDelegate.MODE_NIGHT_YES -> 1
            else -> 2
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Tema de la aplicación")
            .setSingleChoiceItems(opciones, indiceSeleccionado) { dialog, which ->
                val nuevoModo = when (which) {
                    0 -> AppCompatDelegate.MODE_NIGHT_NO
                    1 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                // guarda preferencias y aplica el modo seleccionado
                prefs.edit().putInt("app_theme_mode", nuevoModo).apply()
                AppCompatDelegate.setDefaultNightMode(nuevoModo)

                Toast.makeText(requireContext(), "Tema aplicado: ${opciones[which]}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}