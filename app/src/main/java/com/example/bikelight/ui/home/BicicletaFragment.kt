package com.example.bikelight.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.bikelight.data.local.AppDatabase
import com.example.bikelight.data.local.UserProfile
import com.example.bikelight.databinding.FragmentBicicletaBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BicicletaFragment : Fragment() {
    private var _binding: FragmentBicicletaBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBicicletaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cargarDatosBicicleta()
        // guardar cambios de la bicicleta
        binding.btnGuardarBici.setOnClickListener {
            guardarDatosBicicleta()
        }
    }
    // si el campo está vacío devuelve el valor por defecto
    private fun valorOValorPorDefecto(valor: String?, porDefecto: String): String {
        return if (valor.isNullOrBlank()) porDefecto else valor
    }
    // cargar datos de la bicicleta desde la bd local y sincroniza con firebase
    private fun cargarDatosBicicleta() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val safeContext = context ?: return

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(safeContext)
            val user = db.userDao().getUserProfile(uid)

            withContext(Dispatchers.Main) {
                user?.let { pintarFichaYCampos(it) }
            }

            // sincronizar datos desde firebase realtime database
            FirebaseDatabase.getInstance().reference.child("users").child(uid).get()
                .addOnSuccessListener { snapshot ->
                    try {
                        val userFirebase = snapshot.getValue(UserProfile::class.java)
                        userFirebase?.let { profile ->
                            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                                pintarFichaYCampos(profile)
                            }
                            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                                db.userDao().saveUserProfile(profile)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                .addOnFailureListener {
                }
        }
    }
    // actualiza la tarjeta e inputs con datos registrados
    private fun pintarFichaYCampos(user: UserProfile) {
        if (_binding == null) return

        val anoString = if (user.bikeAno == null || user.bikeAno == 0) null else user.bikeAno.toString()

        // actualiza tarjeta superior
        binding.tvDisplayTipo.text = "Tipo: ${valorOValorPorDefecto(user.bikeTipo, "No registrado")}"
        binding.tvDisplayMarca.text = "Marca: ${valorOValorPorDefecto(user.bikeMarca, "No registrada")}"
        binding.tvDisplayModelo.text = "Modelo: ${valorOValorPorDefecto(user.bikeModelo, "No registrado")}"
        binding.tvDisplayAno.text = "Año: ${valorOValorPorDefecto(anoString, "No registrado")}"
        binding.tvDisplayColor.text = "Color: ${valorOValorPorDefecto(user.bikeColor, "No registrado")}"

        // rellena campos del formulario (si es null se deja vacío)
        binding.etBikeTipo.setText(user.bikeTipo ?: "")
        binding.etBikeMarca.setText(user.bikeMarca ?: "")
        binding.etBikeModelo.setText(user.bikeModelo ?: "")
        binding.etBikeAno.setText(anoString ?: "")
        binding.etBikeColor.setText(user.bikeColor ?: "")
    }
    // guarda los nuevos datos en Room y firebase
    private fun guardarDatosBicicleta() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val safeContext = context ?: return

        val tipo = binding.etBikeTipo.text.toString().trim()
        val marca = binding.etBikeMarca.text.toString().trim()
        val modelo = binding.etBikeModelo.text.toString().trim()
        val anoStr = binding.etBikeAno.text.toString().trim()
        val anoInt = anoStr.toIntOrNull() // convierte enter
        val color = binding.etBikeColor.text.toString().trim()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(safeContext)
            // actualiza localmente la bd Room
            db.userDao().updateBikeInfo(uid, tipo, marca, modelo, anoStr, color)

            val bikeMap: Map<String, Any?> = mapOf(
                "bikeTipo" to tipo,
                "bikeMarca" to marca,
                "bikeModelo" to modelo,
                "bikeAno" to anoInt,
                "bikeColor" to color
            )
            // actualiza en firebase realtime database
            FirebaseDatabase.getInstance().reference.child("users").child(uid)
                .updateChildren(bikeMap)
                .addOnSuccessListener {
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                        if (_binding != null) {
                            binding.tvDisplayTipo.text = "Tipo: ${valorOValorPorDefecto(tipo, "No registrado")}"
                            binding.tvDisplayMarca.text = "Marca: ${valorOValorPorDefecto(marca, "No registrada")}"
                            binding.tvDisplayModelo.text = "Modelo: ${valorOValorPorDefecto(modelo, "No registrado")}"
                            binding.tvDisplayAno.text = "Año: ${valorOValorPorDefecto(anoStr, "No registrado")}"
                            binding.tvDisplayColor.text = "Color: ${valorOValorPorDefecto(color, "No registrado")}"
                            Toast.makeText(safeContext, "Información guardada exitosamente", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null // limpia bingind previniendo fugas de memoria
    }
}