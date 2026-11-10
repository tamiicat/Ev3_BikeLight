package com.example.bikelight.ui.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.bikelight.R
import com.example.bikelight.data.local.AppDatabase
import com.example.bikelight.data.local.UserProfile
import com.example.bikelight.databinding.ActivityRegisterBinding
import com.example.bikelight.security.CryptoUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RegisterActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRegisterBinding
    private lateinit var auth: FirebaseAuth
    private val dbRef = FirebaseDatabase.getInstance().reference
    private lateinit var localDb: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // verificación del tema de aplicación
        val ModoOscuro = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        // cambia el logo según el tema aplicado (claro u oscuro)
        if (ModoOscuro) {
            binding.imgLogo.setImageResource(R.drawable.bici_oscuro)
        } else {
            binding.imgLogo.setImageResource(R.drawable.bici)
        }

        auth = FirebaseAuth.getInstance()
        localDb = AppDatabase.getDatabase(this)

        // registrar cuentaa
        binding.btnAction.setOnClickListener {
            val fullName = binding.etFullName.text.toString().trim()
            val username = binding.etUsername.text.toString().trim()
            val email = binding.etEmail.text.toString().trim()
            val phone = binding.etPhone.text.toString().trim()
            val pass = binding.etPassword.text.toString().trim()

            if (fullName.isEmpty()) {
                binding.etFullName.error = "El nombre completo es obligatorio"
                binding.etFullName.requestFocus()
                return@setOnClickListener
            }
            if (username.isEmpty()) {
                binding.etUsername.error = "El nombre de usuario es obligatorio"
                binding.etUsername.requestFocus()
                return@setOnClickListener
            }
            if (email.isEmpty()) {
                binding.etEmail.error = "El correo electrónico es obligatorio"
                binding.etEmail.requestFocus()
                return@setOnClickListener
            }
            if (phone.isEmpty()) {
                binding.etPhone.error = "El teléfono es obligatorio"
                binding.etPhone.requestFocus()
                return@setOnClickListener
            }
            if (pass.isEmpty() || pass.length < 8) {
                binding.etPassword.error = "La contraseña debe tener mínimo 8 caracteres"
                binding.etPassword.requestFocus()
                return@setOnClickListener
            }

            registrarUsuario(email, pass, fullName, username, phone)
        }

        // volver al login
        binding.tvSwitchMode.setOnClickListener {
            finish()
        }
    }
    // crear usuario en firebase authentication y almcanemaiento de datos
    private fun registrarUsuario(
        email: String,
        pass: String,
        fullName: String,
        username: String,
        phone: String
    ) {
        auth.createUserWithEmailAndPassword(email, pass).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val firebaseUser = auth.currentUser ?: return@addOnCompleteListener
                val uid = firebaseUser.uid

                // enviar correo de bienvenida
                firebaseUser.sendEmailVerification().addOnCompleteListener { emailTask ->
                    if (emailTask.isSuccessful) {
                        // correo enviado
                    }
                }

                // perfil guardado local en Room (texto plano)
                val userLocal = UserProfile(
                    uid = uid,
                    email = email,
                    fullName = fullName,
                    username = username,
                    phone = phone
                )

                // datos sensibles cifrados
                val firebaseUserData = mapOf(
                    "uid" to uid,
                    "email" to CryptoUtils.encrypt(email),
                    "username" to username,
                    "fullName" to CryptoUtils.encrypt(fullName),
                    "phone" to CryptoUtils.encrypt(phone)
                )

                // guardar en realtime database
                dbRef.child("users").child(uid).updateChildren(firebaseUserData).addOnCompleteListener { syncTask ->
                    if (syncTask.isSuccessful) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            localDb.userDao().saveUserProfile(userLocal)
                            withContext(Dispatchers.Main) {
                                auth.signOut()

                                Toast.makeText(
                                    this@RegisterActivity,
                                    "¡Cuenta creada! Hemos enviado un correo a $email para confirmar tu cuenta.",
                                    Toast.LENGTH_LONG
                                ).show()

                                irAAuth()
                            }
                        }
                    } else {
                        Toast.makeText(
                            this@RegisterActivity,
                            "Usuario creado con aviso: ${syncTask.exception?.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } else {
                val errorMsg = task.exception?.localizedMessage ?: "No se pudo registrar"
                Toast.makeText(this, "Error al crear cuenta: $errorMsg", Toast.LENGTH_LONG).show()
            }
        }
    }
    // redigirige a la autenticación
    private fun irAAuth() {
        val intent = Intent(this, AuthActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}