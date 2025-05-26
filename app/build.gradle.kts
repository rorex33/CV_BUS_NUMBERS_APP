/**
 * Конфигурация сборки для Android-приложения TransportReader.
 *
 * Основные функции:
 * - Поддержка работы с камерой (CameraX)
 * - Интеграция PyTorch для ML-инференса
 * - Поддержка Jetpack Compose
 * - Оптимизация под современные ARM-архитектуры
 */

plugins {
    id("com.android.application")          // Плагин для Android-приложений
    id("org.jetbrains.kotlin.android")     // Поддержка Kotlin
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22"  // Сериализация Kotlin
}

android {
    namespace = "com.example.transportreader"
    compileSdk = 34  // Target Android 14

    defaultConfig {
        applicationId = "com.example.transportreader"
        minSdk = 28  // Android 9 (Pie)
        targetSdk = 34
        versionCode = 1  // Версия для маркета
        versionName = "1.0"

        // Настройка NDK для оптимизации под ARM-архитектуры
        ndk {
            abiFilters.clear()
            abiFilters.add("arm64-v8a")    // 64-битные ARM устройства
            abiFilters.add("armeabi-v7a")  // 32-битные ARM устройства
        }
    }

    // Включение современных функций Android
    buildFeatures {
        viewBinding = true      // Генерация ViewBinding
        compose = true          // Включение Jetpack Compose
    }

    // Настройки компиляции Java
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Настройки Kotlin
    kotlinOptions {
        jvmTarget = "17"  // Целевая версия JVM
    }

    // Настройки Jetpack Compose
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"  // Версия компилятора Compose
    }
}

/**
 * Зависимости проекта.
 *
 * Структура:
 * 1. Базовые Android-зависимости
 * 2. CameraX для работы с камерой
 * 3. PyTorch для машинного обучения
 * 4. Jetpack Compose для UI
 * 5. Вспомогательные библиотеки
 */
dependencies {
    // Базовые Android-библиотеки
    implementation("androidx.core:core-ktx:1.12.0")          // Kotlin extensions
    implementation("androidx.appcompat:appcompat:1.7.0")     // AppCompat

    // CameraX (современный API для работы с камерой)
    implementation("androidx.camera:camera-camera2:1.2.3")   // Camera2 API
    implementation("androidx.camera:camera-core:1.3.0")      // Базовые функции
    implementation("androidx.camera:camera-lifecycle:1.2.3") // Управление жизненным циклом
    implementation("androidx.camera:camera-view:1.2.3")      // Превью камеры

    // PyTorch (машинное обучение)
    implementation("org.pytorch:pytorch_android:1.13.1")              // Основная библиотека
    implementation("org.pytorch:pytorch_android_torchvision:1.13.1")  // TorchVision

    // UI компоненты
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")  // ConstraintLayout

    // Jetpack Compose (современный UI-фреймворк)
    implementation("androidx.activity:activity-compose:1.8.2")         // Compose Activity
    implementation("androidx.compose.ui:ui:1.6.0")                     // Базовые компоненты
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.0")     // Инструменты превью
    implementation("androidx.compose.material3:material3:1.2.0")       // Material Design 3
    debugImplementation("androidx.compose.ui:ui-tooling:1.6.0")        // Инструменты для отладки

    // Тестирование (Instrumented Tests)
    implementation("androidx.test.ext:junit:1.1.5")
    implementation("androidx.test.espresso:espresso-core:3.5.1")
    implementation("androidx.test:runner:1.5.2")
    implementation("androidx.test:rules:1.5.0")
}