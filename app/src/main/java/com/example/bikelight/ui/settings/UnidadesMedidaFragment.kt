package com.example.bikelight.ui.settings

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.bikelight.R
import com.example.bikelight.databinding.FragmentUnidadesMedidaBinding

class UnidadesMedidaFragment : Fragment() {
    private var _binding: FragmentUnidadesMedidaBinding? = null
    private val binding get() = _binding!!

    // almacena sistema métrico o imperial
    private var unidadSeleccionada: String = "METRICO"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUnidadesMedidaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        cargarPreferenciaGuardada()
        // seleccionar modo de unidad de médida
        binding.cardMetrico.setOnClickListener {
            seleccionarModo("METRICO")
        }

        binding.cardImperial.setOnClickListener {
            seleccionarModo("IMPERIAL")
        }

        binding.btnGuardarUnidades.setOnClickListener {
            guardarPreferencia()
        }
    }
    // confirma y giarda preferencia seleccionada
    private fun seleccionarModo(modo: String) {
        unidadSeleccionada = modo
        actualizarEstiloVisual()
    }
    // actualiza según elección
    private fun actualizarEstiloVisual() {
        val colorCard = ContextCompat.getColor(requireContext(), R.color.bikelight_card)
        val colorCardDark = ContextCompat.getColor(requireContext(), R.color.bikelight_card_dark)

        if (unidadSeleccionada == "METRICO") {
            binding.tvUnidadSeleccionada.text = "Sistema activo: Métrico (km/h, m)"
            binding.cardMetrico.setCardBackgroundColor(colorCard)
            binding.cardMetrico.strokeWidth = 6

            binding.cardImperial.setCardBackgroundColor(colorCardDark)
            binding.cardImperial.strokeWidth = 0
        } else {
            binding.tvUnidadSeleccionada.text = "Sistema activo: Imperial (mph, ft)"
            binding.cardImperial.setCardBackgroundColor(colorCard)
            binding.cardImperial.strokeWidth = 6

            binding.cardMetrico.setCardBackgroundColor(colorCardDark)
            binding.cardMetrico.strokeWidth = 0
        }
    }
    // lee preferencia almacenada
    private fun cargarPreferenciaGuardada() {
        val prefs = requireContext().getSharedPreferences("BikeLightPrefs", Context.MODE_PRIVATE)
        unidadSeleccionada = prefs.getString("unidad_medida", "METRICO") ?: "METRICO"
        actualizarEstiloVisual()
    }
    // guarda la unidad elegida
    private fun guardarPreferencia() {
        val prefs = requireContext().getSharedPreferences("BikeLightPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("unidad_medida", unidadSeleccionada).apply()

        val texto = if (unidadSeleccionada == "METRICO") "Métrico" else "Imperial"
        Toast.makeText(requireContext(), "Preferencias guardadas: Sistema $texto", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}