package com.example.bikelight.ui.devices

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.bikelight.R
import com.example.bikelight.databinding.FragmentSimuladorProximidadBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

class SimuladorProximidadFragment : Fragment() {
    private var _binding: FragmentSimuladorProximidadBinding? = null
    private val binding get() = _binding!!

    // métrico o imperial sincronizado con preferencias de unidades de medidas
    private var unidadMedida: String = "METRICO"
    private var simulandoActivo: Boolean = true

    // rango de simulación en metros
    private val maxDistanciaMts = 5.0f
    private val minDistanciaMts = 0.2f
    private var distanciaActualMts = 4.5f
    private var acercando = true

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSimuladorProximidadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // carga las preferencias de unidades
        cargarUnidadPreferida()
        configurarListeners()
        iniciarBucleSimulacion()
    }
    // lee las preferencias de unidades de medidas
    private fun cargarUnidadPreferida() {
        val prefs = requireContext().getSharedPreferences("BikeLightPrefs", Context.MODE_PRIVATE)
        unidadMedida = prefs.getString("unidad_medida", "METRICO") ?: "METRICO"
        actualizarTextoUnidad()
    }
    // actualiza texto informativo
    private fun actualizarTextoUnidad() {
        val texto = if (unidadMedida == "METRICO") "Métrico (cm, m)" else "Imperial (in, ft)"
        binding.tvUnidadConfigurada.text = "Unidad activa: $texto"
    }
    // configura los eventos de clic
    private fun configurarListeners() {
        // botón pra alternar entre 'pausa' y 'reunudar' simulación
        binding.btnIniciarPausar.setOnClickListener {
            simulandoActivo = !simulandoActivo
            binding.btnIniciarPausar.text = if (simulandoActivo) "Pausar" else "Reanudar"
        }

        binding.btnCambiarUnidad.setOnClickListener {
            // permite alternar la unidad de prueba y guardarla en preferencias
            unidadMedida = if (unidadMedida == "METRICO") "IMPERIAL" else "METRICO"
            requireContext().getSharedPreferences("BikeLightPrefs", Context.MODE_PRIVATE)
                .edit().putString("unidad_medida", unidadMedida).apply()

            actualizarTextoUnidad()
            renderizarLectura(distanciaActualMts)
        }
    }
    // inicia un bucle utilizando corrutinas y lifecycle para simulación de sensor de proximidad
    private fun iniciarBucleSimulacion() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    if (simulandoActivo) {
                        // fluctuación continua y aleatoria
                        val delta = (Random.nextFloat() * 0.15f) + 0.05f
                        if (acercando) {
                            distanciaActualMts -= delta
                            if (distanciaActualMts <= minDistanciaMts) acercando = false
                        } else {
                            distanciaActualMts += delta
                            if (distanciaActualMts >= maxDistanciaMts) acercando = true
                        }
                        // actualiza con distancia calculada
                        renderizarLectura(distanciaActualMts)
                    }
                    delay(300) // pausa entre cada lectura
                }
            }
        }
    }
    // procesa y renderia la lectura actual
    private fun renderizarLectura(metros: Float) {
        // cálculo de %  de cercanía barra de progreso
        val factorCercania = ((1.0f - (metros / maxDistanciaMts)).coerceIn(0f, 1f) * 100).toInt()
        binding.pbProximidad.progress = factorCercania

        // conversión y formateo según unidades
        val textoDistancia = if (unidadMedida == "METRICO") {
            if (metros < 1.0f) {
                val cm = (metros * 100).toInt()
                "$cm cm"
            } else {
                String.format("%.2f m", metros)
            }
        } else {
            // conversión a pies y pulgadas
            val totalPies = metros * 3.28084f
            if (totalPies < 1.0f) {
                val pulgadas = (totalPies * 12).toInt()
                "$pulgadas in"
            } else {
                String.format("%.2f ft", totalPies)
            }
        }

        binding.tvDistanciaValor.text = textoDistancia

        // estados de advertencia y colores del tema
        when {
            metros < 2.0f -> {
                binding.tvEstadoAlerta.text = "¡ALERTA CRÍTICA: Objeto muy cercano!"
                binding.tvEstadoAlerta.setTextColor(Color.parseColor("#FF5252")) // Rojo advertencia
                binding.cardDisplaySensor.strokeColor = ContextCompat.getColor(requireContext(), R.color.bikelight_sos)
                binding.cardDisplaySensor.strokeWidth = 4
            }
            metros < 3.5f -> {
                binding.tvEstadoAlerta.text = "Precaución: Objeto detectado"
                binding.tvEstadoAlerta.setTextColor(Color.parseColor("#FFB74D")) // Naranja precaución
                binding.cardDisplaySensor.strokeWidth = 0
            }
            else -> {
                binding.tvEstadoAlerta.text = "Distancia segura"
                binding.tvEstadoAlerta.setTextColor(Color.parseColor("#81C784")) // Verde seguro
                binding.cardDisplaySensor.strokeWidth = 0
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}