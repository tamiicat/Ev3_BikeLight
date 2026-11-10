plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    id("com.google.gms.google-services")
}

android {
    namespace = "com.example.bikelight"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.bikelight"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // extensión de idiomas
    implementation(libs.androidx.core.ktx)
    // componentes visuales modernos
    implementation(libs.androidx.appcompat)
    // componentes material design
    implementation(libs.material)
    // diseñador de vistas basado en restricciones visuales
    implementation(libs.androidx.constraintlayout)
    // extensiones para actividades y APIs modernas
    implementation(libs.androidx.activity.ktx)

    // SERVICIOS FIREBASE
    // BOM: gestiona las versiones de todas las librerías de firebase
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    // autenticación de usuarios
    implementation("com.google.firebase:firebase-auth-ktx")
    // conexión con firebase realtime database
    implementation("com.google.firebase:firebase-database-ktx")
    // envío y recepción de notificaicones push y mensajes remotos
    implementation("com.google.firebase:firebase-messaging-ktx")
    // registro de métricas, eventos de uso y comportamiento del usuario dentro de la aplicación
    implementation("com.google.firebase:firebase-analytics-ktx")

    // PERSISTENCIA LOCAL - ROOM DATABASE
    val roomVersion = "2.7.0-alpha01"
    // motor principal de la bd local SQLite
    implementation("androidx.room:room-runtime:$roomVersion")
    // soporte de kotlin coroutines y flow para realizar consultas a la bd
    implementation("androidx.room:room-ktx:$roomVersion")
    // procesador de anotaciones que valida en tiempo de compilación las consultas SQL
    ksp("androidx.room:room-compiler:$roomVersion")

    // CONCURRENCIA Y CICLO DE VIDA
    // soporte para corrutinas en android
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // permite cancelar automáticamente las corrutinas, evitnado fugas de memoria
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    // ejecutar tareas asíncrocinas ligadas al ciclo de vida
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // CONEXIÓN WIFI
    implementation("androidx.fragment:fragment-ktx:1.8.4")

    // PRUEBAS Y TESTING
    // framework estándar para ejecutar pruebas unitarias locales
    testImplementation(libs.junit)
    // extensión para ejecutar pruebas instrumentadas de JUnit
    androidTestImplementation(libs.androidx.junit)
    // framework para pruebas instrumentadas de interfaz de usuario
    androidTestImplementation(libs.androidx.espresso.core)
}