package com.example.bikelight.ui.settings

import android.os.Bundle
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.bikelight.data.local.AppDatabase
import com.example.bikelight.data.local.UserProfile
import com.example.bikelight.databinding.FragmentEmergencyContactBinding
import com.example.bikelight.security.CryptoUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EmergencyContactFragment : Fragment() {

    private var _binding: FragmentEmergencyContactBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEmergencyContactBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cargarDatosContacto()
        // guardar datos de contacto de emergencia
        binding.btnSaveContact.setOnClickListener {
            guardarDatosContacto()
        }
    }
    // retorna valor por defecto si el texto es nulo
    private fun valorOValorPorDefecto(valor: String?, porDefecto: String): String {
        return if (valor.isNullOrBlank()) porDefecto else valor
    }
    // carga datos de bd local y sincroniza con firebase
    private fun cargarDatosContacto() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val safeContext = context ?: return

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(safeContext)
            val user = db.userDao().getUserProfile(uid)

            withContext(Dispatchers.Main) {
                user?.let { pintarFichaYCampos(it) }
            }

            // sincronizar desde realtime database
            FirebaseDatabase.getInstance().reference.child("users").child(uid).get()
                .addOnSuccessListener { snapshot ->
                    try {
                        val userFirebase = snapshot.getValue(UserProfile::class.java)
                        userFirebase?.let { profile ->
                            // descifrad a texto legible
                            val profileDescifrado = profile.copy(
                                emergencyContactName = CryptoUtils.decrypt(profile.emergencyContactName),
                                emergencyContactPhone = CryptoUtils.decrypt(profile.emergencyContactPhone),
                                emergencyContactEmail = CryptoUtils.decrypt(profile.emergencyContactEmail)
                            )

                            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                                pintarFichaYCampos(profileDescifrado)
                            }
                            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                                db.userDao().saveUserProfile(profileDescifrado)
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
    // actualiza tarjeta y campos del formulario
    private fun pintarFichaYCampos(user: UserProfile) {
        if (_binding == null) return

        binding.tvDisplayContactName.text = "Nombre: ${valorOValorPorDefecto(user.emergencyContactName, "No registrado")}"
        binding.tvDisplayContactPhone.text = "Teléfono: ${valorOValorPorDefecto(user.emergencyContactPhone, "No registrado")}"
        binding.tvDisplayContactEmail.text = "Correo: ${valorOValorPorDefecto(user.emergencyContactEmail, "No registrado")}"

        binding.etEmergencyContactName.setText(user.emergencyContactName ?: "")
        binding.etEmergencyContactPhone.setText(user.emergencyContactPhone ?: "")
        binding.etEmergencyContactEmail.setText(user.emergencyContactEmail ?: "")
    }
    // valida, cifra y guarda información del contacto de emrgencia localmente y en la nube
    private fun guardarDatosContacto() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val safeContext = context ?: return

        val name = binding.etEmergencyContactName.text.toString().trim()
        val phone = binding.etEmergencyContactPhone.text.toString().trim()
        val email = binding.etEmergencyContactEmail.text.toString().trim()

        if (name.isEmpty()) {
            Toast.makeText(safeContext, "Por favor ingresa un nombre", Toast.LENGTH_SHORT).show()
            return
        }

        if (phone.isEmpty()) {
            Toast.makeText(safeContext, "Por favor ingresa un teléfono", Toast.LENGTH_SHORT).show()
            return
        }

        if (email.isNotEmpty() && !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Toast.makeText(safeContext, "El formato de correo no es válido", Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(safeContext)

            // guardar en local (Room) en texto plano
            db.userDao().updateEmergencyContact(uid, name, phone, email)

            // datos a cifrar antes de enviar a firebase
            val contactMapCifrado: Map<String, Any?> = mapOf(
                "emergencyContactName" to CryptoUtils.encrypt(name),
                "emergencyContactPhone" to CryptoUtils.encrypt(phone),
                "emergencyContactEmail" to CryptoUtils.encrypt(email)
            )

            // enviar información actualizada a firebase
            FirebaseDatabase.getInstance().reference.child("users").child(uid)
                .updateChildren(contactMapCifrado)
                .addOnSuccessListener {
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                        if (_binding != null) {
                            binding.tvDisplayContactName.text = "Nombre: ${valorOValorPorDefecto(name, "No registrado")}"
                            binding.tvDisplayContactPhone.text = "Teléfono: ${valorOValorPorDefecto(phone, "No registrado")}"
                            binding.tvDisplayContactEmail.text = "Correo: ${valorOValorPorDefecto(email, "No registrado")}"
                            Toast.makeText(safeContext, "Contacto guardado exitosamente", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .addOnFailureListener {
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                        Toast.makeText(safeContext, "Error al sincronizar con el servidor", Toast.LENGTH_SHORT).show()
                    }
                }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}