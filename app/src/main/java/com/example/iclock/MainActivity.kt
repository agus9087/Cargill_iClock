package com.example.iclock

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.get
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Suppress("MISSING_DEPENDENCY_SUPERCLASS_WARNING")
class MainActivity : AppCompatActivity() {

    private lateinit var txtPinDisplay: TextView
    private lateinit var textClock: TextView
    private lateinit var textDate: TextView
    private lateinit var previewView: PreviewView
    private lateinit var iaStatusText: TextView

    private lateinit var textReady : TextView
    private var currentPin = ""

    private var faceNetInterpreter: Interpreter? = null
    private val inputSize = 112 // La mayoría de modelos FaceNet usan 112x112 o 160x160

    private lateinit var cameraExecutor: ExecutorService

    private var isRegistering = false
    private var esProcesandoMarcaje = false // <-- AGREGA ESTA LÍNEA AQUÍ ARRIBA

    private val handler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Inicializar vistas
        txtPinDisplay = findViewById(R.id.txtPinDisplay)
        textClock = findViewById(R.id.textClock)
        textDate = findViewById(R.id.textDate)
        previewView = findViewById(R.id.previewView)
        iaStatusText = findViewById(R.id.iaStatusText)
        textReady = findViewById(R.id.txtReady)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Configurar los botones numéricos
        setupKeypad()

        // Configurar botones de acción
        setupActionButtons()

        // Verificar permisos de cámara
        // DENTRO DE onCreate
        // Elimina o comenta esto:
        /*
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
        */
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun startCamera() {
        // IMPORTANTE: Volver a mostrar el preview antes de iniciar
        previewView.alpha = 1f

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            val faceDetectorOptions = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .build()

            val detector = FaceDetection.getClient(faceDetectorOptions)

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImageProxy(imageProxy, detector)
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy, detector: com.google.mlkit.vision.face.FaceDetector) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            detector.process(image)
                .addOnSuccessListener { faces ->
                    // Colocarlo aquí te permite ver en el Logcat cuántos rostros ve la cámara en tiempo real
                    Log.d("FacialBuild", "Rostros detectados en tiempo real: ${faces.size}")

                    // Agregamos !esProcesandoMarcaje para bloquear nuevas detecciones mientras procesamos esta
                    if (faces.isNotEmpty() && !esProcesandoMarcaje) {
                        esProcesandoMarcaje = true // Bloqueamos la entrada

                        // ... dentro de processImageProxy, justo donde detecta la cara[cite: 13]:
                        val face = faces[0]
                        iaStatusText.text = getString(R.string.face_detected)
                        iaStatusText.setTextColor(ContextCompat.getColor(this, R.color.button_blue))

                        // Pasamos imageProxy y face en lugar de depender de previewView
                        val descriptor = obtenerDescriptorFacial(imageProxy, face)

                        if (descriptor != null) {
                            Log.d("FacialBuild", "Descriptor real: ${descriptor.contentToString()}")
                            // ... resto de tu código de validación [cite: 16]

                            // 2. DECIDIR: ¿Estamos guardando un nuevo rostro o fichando?
                            if (isRegistering) {
                                // MODO ENROLAR: Guardamos el PIN y el Descriptor en el JSON
                                guardarUsuarioLocal(currentPin, descriptor)
                            } else {
                                // MODO FICHAR INTELIGENTE: (Por PIN específico o Escaneo facial directo)
                                val usuarioIdentificado = if (currentPin.isNotEmpty()) {
                                    // Si el usuario escribió un PIN, validamos directamente contra ese PIN
                                    val user = buscarUsuarioPorPin(currentPin)
                                    if (user != null && calcularDistancia(descriptor, user.descriptor.toFloatArray()) < 0.7f) {
                                        user
                                    } else {
                                        null
                                    }
                                } else {
                                    // SI NO ESCRIBIÓ PIN -> Recorremos toda la base de datos local para ver de quién es el rostro
                                    buscarUsuarioPorRostro(descriptor)
                                }

                                if (usuarioIdentificado != null) {
                                    iaStatusText.text = "ACCESO CORRECTO: ID ${usuarioIdentificado.pin}"

                                    // AQUÍ ESTÁ TU LOG:
                                    Log.d("FacialBuild", "Rostros detectados: ${faces.size}" + "   " +"Descriptor real: ${descriptor.contentToString()}")

                                    iaStatusText.setTextColor(ContextCompat.getColor(this, R.color.button_blue)) // O un color verde si tienes
                                    registrarFichadaExitosa(usuarioIdentificado.pin)
                                } else {
                                    iaStatusText.text = if (currentPin.isNotEmpty()) "EL ROSTRO NO COINCIDE" else "ROSTRO NO RECONOCIDO"
                                    iaStatusText.setTextColor(android.graphics.Color.RED)
                                }
                            }
                        }

                        // 3. TEMPORIZADOR PARA APAGAR LA CÁMARA (3 segundos)
                        handler.postDelayed({
                            stopCamera()
                            previewView.alpha = 0f
                            isRegistering = false // Resetear modo de registro
                            esProcesandoMarcaje = false // Liberamos el bloqueo para el próximo uso de la cámara
                            iaStatusText.text = getString(R.string.ia_system_waiting)
                            iaStatusText.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
                        }, 3000)

                    } else if (faces.isEmpty() && !esProcesandoMarcaje) {
                        // Si no hay rostros y tampoco estamos en medio de un proceso de apagado, se queda esperando
                        iaStatusText.text = getString(R.string.ia_system_waiting)
                        iaStatusText.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Face detection failed", e)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    private fun extractFaceBitmap(imageProxy: ImageProxy, face: com.google.mlkit.vision.face.Face): Bitmap {
        val bitmap = previewView.bitmap // Forma rápida si usas PreviewView
        val rect = face.boundingBox

        // Ajustar el recorte para que no se salga de los bordes del bitmap
        val safeRect = Rect(
            rect.left.coerceAtLeast(0),
            rect.top.coerceAtLeast(0),
            rect.right.coerceAtMost(bitmap?.width ?: 0),
            rect.bottom.coerceAtMost(bitmap?.height ?: 0)
        )

        return Bitmap.createBitmap(bitmap!!, safeRect.left, safeRect.top, safeRect.width(), safeRect.height())
    }

    private fun runFaceNet(faceBitmap: Bitmap): FloatArray {
        if (faceNetInterpreter == null) initInterpreter() // Inicializa solo si es nulo

        val input = convertBitmapToByteBuffer(faceBitmap)
        val output = Array(1) { FloatArray(192) }

        faceNetInterpreter?.run(input, output)
        return output[0]
    }

    // 1. Agrega esta anotación para autorizar el uso de imageProxy.image
    @androidx.camera.core.ExperimentalGetImage
    private fun obtenerDescriptorFacial(imageProxy: ImageProxy, face: com.google.mlkit.vision.face.Face): FloatArray? {
        try {
            // 1. Convertimos el frame de la cámara (YUV_420_888 o similar) a un Bitmap real en memoria
            val mediaImage = imageProxy.image ?: return null

            // Convertir la imagen proxy a Bitmap respetando su rotación exacta
            val bitmapOriginal = previewView.bitmap ?: return null
            // Nota: Si previewView sigue fallando, la alternativa ideal es usar el Bitmap del ImageProxy,
            // pero probemos primero asegurando el recorte correcto:

            val rect = face.boundingBox

            // Asegurar que el cuadro delimitador no se salga de las dimensiones del bitmap
            val left = rect.left.coerceAtLeast(0)
            val top = rect.top.coerceAtLeast(0)
            val width = rect.width().coerceAtMost(bitmapOriginal.width - left)
            val height = rect.height().coerceAtMost(bitmapOriginal.height - top)

            if (width <= 0 || height <= 0) return null

            // 2. Recortamos el rostro de manera segura
            val rostroBitmap = Bitmap.createBitmap(bitmapOriginal, left, top, width, height)

            // 3. Ejecutamos la inferencia con el bitmap recortado
            return ejecutarInferenciaTFLite(rostroBitmap)
        } catch (e: Exception) {
            Log.e("TFLite", "Error al procesar el bitmap del rostro: ${e.message}")
            return null
        }
    }

    private fun compararConBaseDeDatos(nuevoDescriptor: FloatArray) {
        // Ejemplo: Si la distancia es < 0.7, es el mismo rostro
        // val esMismoUsuario = calcularDistanciaEuclidiana(nuevoDescriptor, descriptorGuardado) < 0.7
    }


    // 1. Cargar el modelo (puedes llamarlo en el onCreate)
    private fun loadModelFile(modelPath: String): MappedByteBuffer {
        val fileDescriptor = assets.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength

        val mappedBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

        // Es buena práctica cerrar estos recursos, el mapeo en memoria persistirá
        inputStream.close()
        fileDescriptor.close()

        return mappedBuffer
    }

    // 2. Inicializar el Intérprete
    private fun initInterpreter() {
        try {
            val options = Interpreter.Options().apply {
                setNumThreads(1) // Forzar un solo hilo en CPU para pruebas estables
            }
            faceNetInterpreter = Interpreter(loadModelFile("mobile_face_net.tflite"), options)
            Log.d("TFLite", "Modelo inicializado en modo seguro de un solo hilo.")
        } catch (e: Exception) {
            Log.e("TFLite", "Error al cargar el modelo: ${e.message}")
        }
    }

    // 3. La función que necesitas para obtener los 128 números
    private fun ejecutarInferenciaTFLite(faceBitmap: Bitmap): FloatArray {
        // CAMBIAR DE 128 A 192
        val output = arrayOf(FloatArray(192))

        val inputBuffer = convertBitmapToByteBuffer(faceBitmap)

        if (faceNetInterpreter == null) {
            initInterpreter()
        }

        if (faceNetInterpreter == null) {
            Log.e("TFLite", "El intérprete sigue siendo NULL.")
            return FloatArray(192) // CAMBIAR DE 128 A 192
        }

        try {
            faceNetInterpreter!!.run(inputBuffer, output)
            Log.d("TFLite", "¡Inferencia exitosa! Primeros valores: ${output[0].take(3)}")
        } catch (e: Exception) {
            Log.e("TFLite", "Error crítico en run(): ${e.message}")
            e.printStackTrace()
        }

        return output[0]
    }

    // 4. Preprocesamiento: Convertir Bitmap a ByteBuffer
    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        // Redimensionar al tamaño del modelo (112x112)
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

        // 4 bytes por float * 112 * 112 * 3 canales (RGB)
        val byteBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputSize * inputSize)
        scaledBitmap.getPixels(intValues, 0, scaledBitmap.width, 0, 0, scaledBitmap.width, scaledBitmap.height)

        byteBuffer.rewind() // Asegurar que el puntero inicie exactamente en 0

        for (pixelValue in intValues) {
            // Extraer componentes RGB de forma limpia
            val r = (pixelValue shr 16) and 0xFF
            val g = (pixelValue shr 8) and 0xFF
            val b = pixelValue and 0xFF

            // Configuración Estándar MobileFaceNet (-1 a 1)
            byteBuffer.putFloat((r - 127.5f) / 127.5f)
            byteBuffer.putFloat((g - 127.5f) / 127.5f)
            byteBuffer.putFloat((b - 127.5f) / 127.5f)
        }

        return byteBuffer
    }





    // No olvides incluir esta función para que el código anterior funcione
    private fun stopCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                //Abre la camara luego de conceder el permiso a cámara.
                //startCamera()
                Toast.makeText(this, "Permisos concedidos", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permisos no concedidos", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val TAG = "iClockCamera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        handler.post(clockRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockRunnable)
    }

    private fun updateClock() {
        val calendar = Calendar.getInstance()
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val dateFormat = SimpleDateFormat("EEEE, d 'of' MMMM", Locale.getDefault())

        textClock.text = timeFormat.format(calendar.time)
        textDate.text = dateFormat.format(calendar.time)
    }

    private fun setupActionButtons() {
        findViewById<Button>(R.id.btnMarcaje).setOnClickListener {
            // 1. Verificamos si el PIN está vacío
            if (currentPin.isEmpty()) {
                // 2. Verificamos si tenemos permisos antes de abrir la cámara
                if (allPermissionsGranted()) {
                    startCamera()
                    Toast.makeText(this, "Iniciando reconocimiento facial...", Toast.LENGTH_SHORT).show()
                } else {
                    // Si no hay permisos, los pedimos
                    ActivityCompat.requestPermissions(
                        this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
                    )
                }
            } else {
                // Si el PIN tiene algo, mostramos un aviso
                Toast.makeText(this, "Limpia el PIN para usar reconocimiento facial", Toast.LENGTH_SHORT).show()
            }
        }

        // ... resto de tus botones (btnRostro, txtReset, btnConfirm)

        findViewById<Button>(R.id.btnConfirm).setOnClickListener {
            if (currentPin.isNotEmpty()) {
                val usuario = buscarUsuarioPorPin(currentPin)
                if (usuario != null) {
                    Toast.makeText(this, "Marcaje manual exitoso", Toast.LENGTH_SHORT).show()
                    registrarFichadaExitosa(currentPin) // Graba en log_asistencia.txt

                    // Limpiamos pantalla
                    currentPin = ""
                    txtPinDisplay.text = getString(R.string.pin_hint)
                } else {
                    Toast.makeText(this, "Error: El PIN no existe", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Botón Enrolar Rostro (PIN +4 y abre la cámara)
        findViewById<Button>(R.id.btnRostro).setOnClickListener {
            if (currentPin.length >= 4) { // Validamos que el PIN tenga al menos 4 dígitos
                Toast.makeText(this, "Coloque su rostro frente a la cámara", Toast.LENGTH_LONG).show()
                // Aquí indicamos que el próximo rostro detectado será para GUARDAR (Enrolar)
                isRegistering = true
                startCamera()
            } else {
                Toast.makeText(this, "Por favor, ingrese primero su PIN", Toast.LENGTH_SHORT).show()
            }
        }


        // Botón Reset (Limpia PIN y DETIENE la cámara)
        findViewById<TextView>(R.id.txtReset).setOnClickListener {
            currentPin = ""
            txtPinDisplay.text = getString(R.string.pin_hint)

            // Llamamos a la función para apagar la cámara
            stopCamera()
            previewView.alpha = 0f // Se pone en negro inmediatamente al resetear

            Toast.makeText(this, getString(R.string.btn_reset), Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupKeypad() {
        // Lista de IDs de tus botones numéricos (0-9)
        val buttonIds = listOf(
            R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4,
            R.id.btn5, R.id.btn6, R.id.btn7, R.id.btn8, R.id.btn9
        )

        for (id in buttonIds) {
            findViewById<Button>(id).setOnClickListener {
                val button = it as Button
                appendPin(button.text.toString())
            }
        }

        // Botón Borrar (C)
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            currentPin = ""
            txtPinDisplay.text = getString(R.string.pin_hint)
        }

        // Botón Confirmar (Checkmark)
        findViewById<Button>(R.id.btnConfirm).setOnClickListener {
            if (currentPin.isNotEmpty()) {
                Toast.makeText(this, getString(R.string.validating_pin, currentPin), Toast.LENGTH_SHORT).show()
                // Aquí iría tu lógica de validación
            }
        }
    }

    private fun appendPin(digit: String) {
        if (currentPin.length < 6) { // Limite de 6 dígitos por ejemplo
            currentPin += digit
            // Opcional: mostrar asteriscos en lugar de números
            txtPinDisplay.text = "*".repeat(currentPin.length)
        }
    }

    // --- 1. FUNCIÓN PARA GUARDAR (ENROLAR) ---
    private fun guardarUsuarioLocal(pin: String, descriptor: FloatArray) {
        val file = File(filesDir, "usuarios_reloj.json")
        val gson = Gson()

        try {
            // Leer usuarios existentes si el archivo existe
            val listaUsuarios: MutableList<UsuarioFichaje> = if (file.exists()) {
                val json = file.readText()
                val type = object : TypeToken<MutableList<UsuarioFichaje>>() {}.type
                gson.fromJson(json, type) ?: mutableListOf()
            } else {
                mutableListOf()
            }

            // Eliminar si el PIN ya existía para sobrescribirlo
            listaUsuarios.removeAll { it.pin == pin }

            // Agregar el nuevo usuario (Convertimos FloatArray a List para GSON)
            listaUsuarios.add(UsuarioFichaje(pin, descriptor.toList()))

            // Guardar de nuevo en el archivo
            file.writeText(gson.toJson(listaUsuarios))

            runOnUiThread {
                Toast.makeText(this, "Rostro enrolado con éxito para el PIN $pin", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("ERROR_JSON", "Error al guardar usuario: ${e.message}")
        }
    }

    // --- 2. FUNCIÓN PARA BUSCAR USUARIO ---
    private fun buscarUsuarioPorPin(pin: String): UsuarioFichaje? {
        val file = File(filesDir, "usuarios_reloj.json")
        if (!file.exists()) return null

        return try {
            val json = file.readText()
            val type = object : TypeToken<List<UsuarioFichaje>>() {}.type
            val usuarios: List<UsuarioFichaje> = Gson().fromJson(json, type)
            usuarios.find { it.pin == pin }
        } catch (e: Exception) {
            null
        }
    }

    // --- 3. FUNCIÓN PARA CALCULAR DISTANCIA (RECONOCIMIENTO) ---
    private fun calcularDistancia(face1: FloatArray, face2: FloatArray): Float {
        var suma = 0.0f
        for (i in face1.indices) {
            val diff = face1[i] - face2[i]
            suma += diff * diff
        }
        // Devolvemos la raíz cuadrada de la suma de diferencias al cuadrado (Distancia Euclidiana)
        return Math.sqrt(suma.toDouble()).toFloat()
    }

    // --- 4. FUNCIÓN PARA REGISTRAR LA FICHADA (LOG DE ASISTENCIA) ---
    private fun registrarFichadaExitosa(pin: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val logEntry = "FECHA: $timestamp | PIN: $pin | STATUS: EXITOSO\n"

        try {
            val fileAsistencia = File(filesDir, "log_asistencia.txt")
            fileAsistencia.appendText(logEntry) // Agrega una línea al final del archivo
            Log.d("ASISTENCIA", "Marcaje grabado: $logEntry")
        } catch (e: Exception) {
            Log.e("ERROR_LOG", "No se pudo grabar la fichada")
        }
    }

    private fun buscarUsuarioPorRostro(nuevoDescriptor: FloatArray): UsuarioFichaje? {
        val file = File(filesDir, "usuarios_reloj.json")
        if (!file.exists()) return null

        try {
            val json = file.readText()
            val type = object : TypeToken<List<UsuarioFichaje>>() {}.type
            val usuarios: List<UsuarioFichaje> = Gson().fromJson(json, type) ?: return null

            var mejorCoincidencia: UsuarioFichaje? = null
            var menorDistancia = 0.75f // Nuestro umbral límite

            for (u in usuarios) {
                val dist = calcularDistancia(nuevoDescriptor, u.descriptor.toFloatArray())

                // 🟢 LOG IDEAL: Se ejecuta para CADA usuario en la base de datos
                Log.d("FacialBuild", "Comparando con PIN: ${u.pin} | Distancia calculada: $dist (Umbral: $menorDistancia)")

                if (dist < menorDistancia) {
                    menorDistancia = dist
                    mejorCoincidencia = u

                    // Opcional: Log que te avisa cuándo se rompe el récord de mejor coincidencia
                    Log.d("FacialBuild", "-> ¡Nueva mejor coincidencia temporal! PIN: ${u.pin} con dist: $dist")
                }
            }

            // Log final para saber si encontramos a alguien al terminar el bucle
            if (mejorCoincidencia != null) {
                Log.d("FacialBuild", "✅ Rostro IDENTIFICADO: PIN ${mejorCoincidencia.pin} con distancia final de $menorDistancia")
            } else {
                Log.w("FacialBuild", "❌ Rostro NO RECONOCIDO. Ningún usuario estuvo por debajo del umbral 0.55f")
            }

            return mejorCoincidencia
        } catch (e: Exception) {
            Log.e("FacialBuild", "Error en buscarUsuarioPorRostro: ${e.message}")
            return null
        }
    }

} //Class MainActivity

data class UsuarioFichaje(val pin: String, val descriptor: List<Float>)