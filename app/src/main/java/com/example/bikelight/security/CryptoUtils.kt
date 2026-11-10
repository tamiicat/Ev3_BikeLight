package com.example.bikelight.security

import android.util.Base64
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BIT = 128 // longitud de autenticación de GCM
    private const val IV_LENGTH_BYTE = 12  // longitud IV en GCM
    // 32 caracteres = AES-256
    private const val SECRET_KEY_STRING = "MiClaveSecretaSuperSegura32Bytes"

    private fun getSecretKey(): SecretKeySpec {
        return SecretKeySpec(SECRET_KEY_STRING.toByteArray(Charsets.UTF_8), ALGORITHM)
    }

    // cifra un texto plano y devuelve un String en Base64 listo para Firebase.
    fun encrypt(plainText: String?): String {
        if (plainText.isNullOrEmpty()) return ""

        try {
            // generar un vector de inicialización (IV) aleatorio y único
            val iv = ByteArray(IV_LENGTH_BYTE)
            SecureRandom().nextBytes(iv)

            // inicializar Cipher en modo ENCRYPT
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey(), spec)

            // cifrar el texto
            val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

            // empaquetar IV + Datos cifrados en un solo arreglo de bytes
            val byteBuffer = ByteBuffer.allocate(iv.size + cipherBytes.size)
            byteBuffer.put(iv)
            byteBuffer.put(cipherBytes)

            // devolver en formato Base64 para guardarlo como texto en Firebase
            return Base64.encodeToString(byteBuffer.array(), Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            // en caso de error, retorna el valor original o maneja la excepción
            return plainText
        }
    }

    fun decrypt(cipherTextBase64: String?): String {
        if (cipherTextBase64.isNullOrEmpty()) return ""

        try {
            // decodificar la cadena Base64 a bytes
            val combinedBytes = Base64.decode(cipherTextBase64, Base64.NO_WRAP)

            // extraer el IV y los bytes cifrados
            val byteBuffer = ByteBuffer.wrap(combinedBytes)
            val iv = ByteArray(IV_LENGTH_BYTE)
            byteBuffer.get(iv)

            val cipherBytes = ByteArray(byteBuffer.remaining())
            byteBuffer.get(cipherBytes)

            // inicializar Cipher en modo DECRYPT con el IV extraído
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)

            // descifrar y convertir a texto plano
            val originalBytes = cipher.doFinal(cipherBytes)
            return String(originalBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            // retorna la cadena tal cual si no se pudo descifrar
            return cipherTextBase64
        }
    }
}