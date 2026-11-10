package com.example.bikelight.ui.auth

import android.content.res.Configuration
import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.bikelight.R
import com.example.bikelight.databinding.ActivityResetPasswordBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException

class ResetPasswordActivity : AppCompatActivity() {
    private lateinit var binding: ActivityResetPasswordBinding
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResetPasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // configuración según tema de aplicación
        val ModoOscuro = (resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

        if (ModoOscuro) {
            binding.imgLogoReset.setImageResource(R.drawable.bici_oscuro)
        } else {
            binding.imgLogoReset.setImageResource(R.drawable.bici)
        }

        auth = FirebaseAuth.getInstance()

        // enviar correo de recuperación de contraseña
        binding.btnSendResetEmail.setOnClickListener {
            val email = binding.etResetEmail.text.toString().trim()

            if (email.isEmpty()) {
                binding.etResetEmail.error = "Ingresa tu correo"
                binding.etResetEmail.requestFocus()
                return@setOnClickListener
            }

            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                binding.etResetEmail.error = "Ingresa un correo electrónico válido"
                binding.etResetEmail.requestFocus()
                return@setOnClickListener
            }

            enviarCorreoRecuperacion(email)
        }
        // vuelve a pantalla anterior
        binding.btnBackToLogin.setOnClickListener {
            finish()
        }
    }
    // utiliza firebase auth para enviar el correo de restablecimiento
    private fun enviarCorreoRecuperacion(email: String) {
        binding.btnSendResetEmail.isEnabled = false

        auth.sendPasswordResetEmail(email).addOnCompleteListener { task ->
            binding.btnSendResetEmail.isEnabled = true
            if (task.isSuccessful) {
                Toast.makeText(
                    this,
                    "Enlace enviado a $email. Revisa tu bandeja de entrada o spam.",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            } else {
                val exception = task.exception
                // verifica que el correo ingresado esté asociado a una cuenta
                if (exception is FirebaseAuthInvalidUserException) {
                    binding.etResetEmail.error = "Este correo no está registrado en BikeLight"
                    binding.etResetEmail.requestFocus()
                } else {
                    val errorMsg = exception?.localizedMessage ?: "No se pudo enviar el correo"
                    Toast.makeText(this, "Error: $errorMsg", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}