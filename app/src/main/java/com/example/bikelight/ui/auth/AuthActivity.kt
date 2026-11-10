package com.example.bikelight.ui.auth

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.bikelight.MainActivity
import com.example.bikelight.R
import com.example.bikelight.data.local.AppDatabase
import com.example.bikelight.data.local.UserProfile
import com.example.bikelight.databinding.ActivityAutenticacionBinding
import com.example.bikelight.security.CryptoUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AuthActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAutenticacionBinding
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // inicializa el viewbinding para inflar la interfaz de autenticación
        binding = ActivityAutenticacionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // detecta el tema de la aplicación
        val ModoOscuro = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        // cambia el logo según el modo (claro u oscuro)
        if (ModoOscuro) {
            binding.imgLogo.setImageResource(R.drawable.bici_oscuro)
        } else {
            binding.imgLogo.setImageResource(R.drawable.bici)
        }

        // obtiene la instancia de la autenticación de firebase
        auth = FirebaseAuth.getInstance()

        // comprobar si ya existe una sesión iniciada
        if (auth.currentUser != null) {
            irAMain()
            return
        }

        binding.btnAction.text = "Iniciar Sesión"
        binding.tvSwitchMode.text = "¿No tienes cuenta? Regístrate aquí"

        // iniciar sesión
        binding.btnAction.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val pass = binding.etPassword.text.toString().trim()

            if (email.isEmpty()) {
                binding.etEmail.error = "Ingresa tu correo"
                binding.etEmail.requestFocus()
                return@setOnClickListener
            }
            if (pass.isEmpty()) {
                binding.etPassword.error = "Ingresa tu contraseña"
                binding.etPassword.requestFocus()
                return@setOnClickListener
            }
            iniciarSesion(email, pass)
        }

        // registrar cuenta
        binding.tvSwitchMode.setOnClickListener {
            val intent = Intent(this, RegisterActivity::class.java)
            startActivity(intent)
        }

        // restablecer contraseña
        binding.tvForgotPassword.setOnClickListener {
            val intent = Intent(this, ResetPasswordActivity::class.java)
            startActivity(intent)
        }
    }
    // función para autenticar al usuario
    private fun iniciarSesion(email: String, pass: String) {
        auth.signInWithEmailAndPassword(email, pass).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val uid = auth.currentUser?.uid
                if (uid != null) {
                    sincronizarPerfilYContinuar(uid)
                } else {
                    irAMain()
                }
            } else {
                val errorMsg = task.exception?.localizedMessage ?: "Error de autenticación"
                Toast.makeText(this, "Error al iniciar sesión: $errorMsg", Toast.LENGTH_LONG).show()
            }
        }
    }
    // sincroniza y descifra información del perfil del usuario
    private fun sincronizarPerfilYContinuar(uid: String) {
        val dbRef = FirebaseDatabase.getInstance().reference.child("users").child(uid)

        dbRef.get().addOnSuccessListener { snapshot ->
            val userFirebase = snapshot.getValue(UserProfile::class.java)
            if (userFirebase != null) {
                // descifrar datos sensibles
                val userDescifrado = userFirebase.copy(
                    email = CryptoUtils.decrypt(userFirebase.email),
                    fullName = CryptoUtils.decrypt(userFirebase.fullName),
                    phone = CryptoUtils.decrypt(userFirebase.phone),
                    emergencyContactName = CryptoUtils.decrypt(userFirebase.emergencyContactName),
                    emergencyContactPhone = CryptoUtils.decrypt(userFirebase.emergencyContactPhone),
                    emergencyContactEmail = CryptoUtils.decrypt(userFirebase.emergencyContactEmail)
                )
                // guarda perfil descifrado en bd local Room
                lifecycleScope.launch(Dispatchers.IO) {
                    val localDb = AppDatabase.getDatabase(applicationContext)
                    localDb.userDao().saveUserProfile(userDescifrado)

                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@AuthActivity, "¡Bienvenidx!", Toast.LENGTH_SHORT).show()
                        irAMain()
                    }
                }
            } else {
                irAMain()
            }
        }.addOnFailureListener { e ->
            Log.e("AuthActivity", "Error al sincronizar perfil: ${e.message}")
            irAMain()
        }
    }
    // navegaa la actividad inicial
    private fun irAMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}