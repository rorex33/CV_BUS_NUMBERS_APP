package com.example.transportreader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import android.graphics.ImageFormat
import android.graphics.YuvImage
import android.graphics.Rect
import com.example.transportreader.databinding.ActivityMainBinding
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.torchvision.TensorImageUtils
import java.io.File
import java.util.*
import java.io.FileOutputStream
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors


// Главная Activity приложения — захватывает камеру, обрабатывает кадры через модель PyTorch,
// озвучивает результаты через TTS (TextToSpeech)
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        // Загрузка нативных библиотек PyTorch при запуске
        init {
            try {
                System.loadLibrary("pytorch_jni_lite")
                System.loadLibrary("fbjni")
                Log.d("PyTorch", "Native libraries loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("PyTorch", "Native load error", e)
            }
        }
    }

    private lateinit var binding: ActivityMainBinding         // View binding для доступа к элементам UI
    private var textToSpeech: TextToSpeech? = null            // TTS для озвучивания результатов
    private var model: Module? = null                          // Модель PyTorch для распознавания
    private val executor = Executors.newSingleThreadExecutor() // Фоновый поток для загрузки и анализа
    private var cameraProvider: ProcessCameraProvider? = null // Провайдер камеры

    // Запрос разрешения на использование камеры
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startApp() // Если разрешение получено, запускаем логику приложения
        } else {
            // Показываем объяснение или диалог с переходом в настройки
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                showPermissionRationale()
            } else {
                showPermissionDeniedDialog()
            }
        }
    }

    // Запуск активности установки TTS, если движок не установлен
    private val ttsInstallLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* Обработка результата установки TTS (пусто) */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkPermissions() // Проверяем и запрашиваем разрешения
    }

    // Проверка разрешения камеры, с учётом политики Android 13+
    private fun checkPermissions() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                startApp() // Разрешение есть — запускаем приложение
            }

            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                showPermissionRationale() // Показываем пояснение для пользователя
            }

            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA) // Запрашиваем разрешение
            }
        }
    }

    // Диалог с объяснением, зачем нужно разрешение камеры
    private fun showPermissionRationale() {
        AlertDialog.Builder(this)
            .setTitle("Требуется доступ к камере")
            .setMessage("Приложению нужен доступ к камере для распознавания транспорта")
            .setPositiveButton("Разрешить") { _, _ ->
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            .setNegativeButton("Отмена") { _, _ ->
                finish() // Закрываем приложение, если отказ
            }
            .setCancelable(false)
            .show()
    }

    // Диалог при полном отказе в разрешении, с предложением открыть настройки
    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Доступ запрещен")
            .setMessage("Вы запретили доступ к камере. Хотите открыть настройки?")
            .setPositiveButton("Настройки") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("Выйти") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    // Открытие настроек приложения, чтобы пользователь мог включить разрешения вручную
    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    // Основной запуск приложения — инициализация TTS, загрузка модели, настройка камеры
    private fun startApp() {
        initializeTextToSpeech()
        loadModel()
        setupCamera()
    }

    // Callback инициализации TTS
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.language = Locale("ru")
            Log.d("TTS", "TTS initialized")
        } else {
            Log.e("TTS", "TTS initialization failed")
            // Если движок не установлен, предлагаем установить его
            val installIntent = Intent().apply {
                action = TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA
            }
            ttsInstallLauncher.launch(installIntent)
        }
    }

    // Инициализация объекта TextToSpeech
    private fun initializeTextToSpeech() {
        textToSpeech = TextToSpeech(this, this)
    }

    // Загрузка PyTorch-модели из assets (копируем в internal storage, если нужно)
    private fun loadModel() {
        executor.execute {
            try {
                val modelFile = File(filesDir, "model.pt")
                if (!modelFile.exists()) {
                    assets.open("model.pt").use { input ->
                        modelFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                model = Module.load(modelFile.absolutePath)
                runOnUiThread {
                    binding.tvStatus.text = "Готов к работе"
                    showToast("Модель загружена")
                }
            } catch (e: Exception) {
                Log.e("Model", "Load error", e)
                runOnUiThread {
                    showToast("Ошибка загрузки модели")
                    binding.tvStatus.text = "Ошибка модели"
                }
            }
        }
    }

    // Настройка камеры и получение CameraProvider
    private fun setupCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases() // Связываем use cases (preview + анализ)
        }, ContextCompat.getMainExecutor(this))
    }

    // Связываем Preview и ImageAnalysis с жизненным циклом Activity
    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return

        val preview = Preview.Builder()
            .build()
            .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

        val imageAnalyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                // Анализ каждого кадра в отдельном потоке
                it.setAnalyzer(executor) { image -> processImage(image) }
            }

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalyzer
            )
        } catch (e: Exception) {
            Log.e("Camera", "Use case binding failed", e)
        }
    }

    // Обработка каждого кадра с камеры
    private fun processImage(image: ImageProxy) {
        try {
            if (image.format != ImageFormat.YUV_420_888) {
                Log.w("Camera", "Unsupported format: ${image.format}")
                return
            }

            val bitmap = try {
                val rotatedBitmap = image.toBitmap() // Получаем Bitmap с правильным поворотом
                Bitmap.createScaledBitmap(rotatedBitmap, 512, 512, true).also {
                    rotatedBitmap.recycle()
                }
            } catch (e: Exception) {
                Log.e("Camera", "Bitmap conversion failed", e)
                return
            }

            try {
                processWithModel(bitmap) // Прогоняем изображение через модель
            } finally {
                bitmap.recycle() // Освобождаем память
            }
        } finally {
            image.close() // Закрываем ImageProxy, чтобы получить следующий кадр
        }
    }

    // Прогон изображения через PyTorch-модель и вывод результата
    private fun processWithModel(bitmap: Bitmap) {
        try {
            // Преобразуем Bitmap в тензор с нормализацией
            val inputTensor = TensorImageUtils.bitmapToFloat32Tensor(
                bitmap,
                TensorImageUtils.TORCHVISION_NORM_MEAN_RGB,
                TensorImageUtils.TORCHVISION_NORM_STD_RGB
            )

            val outputIValue = model?.forward(IValue.from(inputTensor))
            if (outputIValue == null) {
                Log.e("Model", "Model output is null")
                return
            }

            val outputs = outputIValue.toTuple()
            if (outputs.size < 3) {
                Log.e("Model", "Unexpected output tuple size: ${outputs.size}")
                return
            }

            // Распаковываем выходы: класс, координаты и OCR
            val clsTensor = outputs[0].toTensor()
            val bboxTensor = outputs[1].toTensor()
            val ocrTensor = outputs[2].toTensor()

            val classResult = interpretClassOutput(clsTensor.dataAsFloatArray)
            val plateText = decodeOCROutput(ocrTensor)

            val result = "Тип: $classResult\nНомер: $plateText"

            Log.d("Model", "Result: $result")

            runOnUiThread {
                binding.tvResult.text = result // Отображаем результат в UI
                speak(result)                 // Озвучиваем результат
            }
        } catch (e: Exception) {
            Log.e("Model", "Processing error", e)
        }
    }


    // Конвертация ImageProxy из YUV_420_888 в Bitmap с учетом поворота камеры
    private fun ImageProxy.toBitmap(): Bitmap {
        if (format != ImageFormat.YUV_420_888) {
            throw IllegalArgumentException("Expected YUV420 format, got ${format}")
        }

        // Матрица для поворота изображения
        val rotationMatrix = Matrix().apply {
            postRotate(imageInfo.rotationDegrees.toFloat())
        }

        // Читаем буферы Y, U, V для конвертации в NV21
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        // Создаём YuvImage, сжимаем в JPEG и конвертим в Bitmap
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val outputStream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 80, outputStream)
        val jpegBytes = outputStream.toByteArray()

        // Декодируем JPEG и поворачиваем
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size).let {
            Bitmap.createBitmap(it, 0, 0, width, height, rotationMatrix, true)
        }
    }

    // Преобразуем выход bbox-модели в читаемую строку
    private fun interpretBBoxOutput(output: FloatArray): String {
        if (output.size < 4) return ""
        val (x1, y1, x2, y2) = output
        return "Координаты: (${x1.format(2)}, ${y1.format(2)}) - (${x2.format(2)}, ${y2.format(2)})"
    }

    // Форматирование числа с заданным количеством знаков после запятой
    private fun Float.format(digits: Int) = "%.${digits}f".format(this)

    // Соответствие индексов классов к строковым названиям
    private val classMap = mapOf(
        0 to "Автобус",
        1 to "Троллейбус",
        2 to "Маршрутка",
        3 to "Трамвай",
        4 to "Неизвестный транспорт"
    )

    // Определение класса по выходу модели (выбор класса с максимальным значением)
    private fun interpretClassOutput(output: FloatArray): String {
        val maxIdx = output.indices.maxByOrNull { output[it] } ?: -1
        return classMap[maxIdx] ?: "Неизвестный транспорт"
    }

    // Символы, используемые для распознавания номеров
    private val chars = "0123456789АВЕКМНОРСТУХ"
    private val blankIndex = chars.length // Индекс "blank" символа для CTC декодинга

    // Декодирование выходных данных OCR-модели с помощью CTC алгоритма
    private fun decodeOCROutput(ocrTensor: org.pytorch.Tensor): String {
        val shape = ocrTensor.shape()
        if (shape.size != 2) return ""

        val seqLen = shape[0].toInt()
        val vocabSize = shape[1].toInt()

        val data = ocrTensor.dataAsFloatArray

        val decodedIndices = mutableListOf<Int>()
        for (t in 0 until seqLen) {
            var maxProb = Float.MIN_VALUE
            var maxIdx = blankIndex
            for (v in 0 until vocabSize) {
                val prob = data[t * vocabSize + v]
                if (prob > maxProb) {
                    maxProb = prob
                    maxIdx = v
                }
            }
            decodedIndices.add(maxIdx)
        }

        // Удаляем повторы и blank символы (CTC decoding)
        val sb = StringBuilder()
        var prev = -1
        for (idx in decodedIndices) {
            if (idx != blankIndex && idx != prev && idx < chars.length) {
                sb.append(chars[idx])
            }
            prev = idx
        }
        return sb.toString()
    }

    private var lastSpokenText: String? = null

    // Озвучивание текста с прерыванием предыдущей речи, если она была
    private fun speak(text: String) {
        if (text == lastSpokenText) return  // не повторять один и тот же текст подряд
        lastSpokenText = text
        textToSpeech?.apply {
            stop()
            speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts1")
        }
    }

    private fun showToast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        executor.shutdown()
        cameraProvider?.unbindAll()
    }
}
