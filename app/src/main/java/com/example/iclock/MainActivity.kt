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

    // Capa de datos cifrada (tarea #6): toda la E/S de archivos pasa por estos repositorios.
    private lateinit var userRepo: UserRepository
    private lateinit var attendanceRepo: AttendanceRepository

    private var isRegistering = false
    private var esProcesandoMarcaje = false // <-- AGREGA ESTA LÍNEA AQUÍ ARRIBA
    private var pinEnValidacion = "" // PIN cuyo rostro se está validando con la cámara

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

        // Repositorios cifrados (capa de datos). Deben crearse antes de fichar o enrolar.
        userRepo = UserRepository(this)
        attendanceRepo = AttendanceRepository(this)

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

        // Cada vez que se abre la cámara arrancamos una sesión "limpia": liberamos la bandera
        // para que el primer rostro pueda procesarse. Tras un fichaje la dejamos en true y la
        // cámara se apaga, así que es acá donde se reinicia para el próximo usuario.
        esProcesandoMarcaje = false

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
                    // 1. Si ya estamos procesando un marcaje, ignoramos olímpicamente CUALQUIER frame nuevo
                    if (faces.isNotEmpty() && !esProcesandoMarcaje) {
                        esProcesandoMarcaje = true // Bloqueo inmediato

                        val face = faces[0]
                        iaStatusText.text = "Procesando rostro..."

                        val descriptor = obtenerDescriptorFacial(imageProxy, face)

                        if (descriptor != null) {
                            if (isRegistering) {
                                // MODO ENROLAR: el rostro detectado se guarda para el PIN ingresado.
                                val pinEnrolado = currentPin
                                guardarUsuarioLocal(pinEnrolado, descriptor)
                                isRegistering = false

                                // Limpiamos el PIN y volvemos al estado de espera con la cámara apagada.
                                currentPin = ""
                                txtPinDisplay.text = getString(R.string.pin_hint)
                                iaStatusText.text = "Rostro enrolado (PIN $pinEnrolado)"
                                iaStatusText.setTextColor(ContextCompat.getColor(this, R.color.button_blue))

                                Handler(Looper.getMainLooper()).postDelayed({
                                    iaStatusText.text = "Esperando rostro..."
                                    iaStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.black))
                                    stopCamera()
                                    previewView.alpha = 0f
                                    esProcesandoMarcaje = false
                                }, 2000)

                            } else {
                                // MODO VALIDAR: comparamos el rostro SOLO contra el descriptor
                                // asociado al PIN ingresado (no contra todos los usuarios).
                                val usuarioValidado = userRepo.matchByPin(pinEnValidacion, descriptor)

                                if (usuarioValidado != null) {
                                    // COINCIDE: registramos UN ÚNICO fichaje y apagamos la cámara.
                                    registrarFichadaExitosa(usuarioValidado.pin)
                                    iaStatusText.text = "ACCESO CORRECTO: ID ${usuarioValidado.pin}"
                                    iaStatusText.setTextColor(ContextCompat.getColor(this, R.color.button_blue))

                                    // Apagamos la cámara de inmediato: sin más frames no hay fichaje doble.
                                    // 'esProcesandoMarcaje' queda en true a propósito para bloquear cualquier
                                    // frame en vuelo; se reinicia al volver a abrir la cámara (startCamera).
                                    stopCamera()
                                    previewView.alpha = 0f
                                    currentPin = ""
                                    pinEnValidacion = ""
                                    txtPinDisplay.text = getString(R.string.pin_hint)

                                    Handler(Looper.getMainLooper()).postDelayed({
                                        iaStatusText.text = "Esperando rostro..."
                                        iaStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.black))
                                    }, 3000) // solo refresca el texto; la cámara ya está apagada

                                } else {
                                    // NO COINCIDE con el PIN: NO se registra ningún fichaje.
                                    iaStatusText.text = "El rostro no coincide con el PIN $pinEnValidacion"
                                    iaStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))

                                    // Apagamos la cámara y limpiamos el PIN (cualquier intento borra el PIN ingresado).
                                    stopCamera()
                                    previewView.alpha = 0f
                                    currentPin = ""
                                    pinEnValidacion = ""
                                    txtPinDisplay.text = getString(R.string.pin_hint)

                                    Handler(Looper.getMainLooper()).postDelayed({
                                        iaStatusText.text = "Esperando rostro..."
                                        iaStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.black))
                                    }, 3000)
                                }
                            }
                        } else {
                            // Si el descriptor falló, liberamos la bandera de inmediato
                            esProcesandoMarcaje = false
                        }
                    }
                    // ... (aquí cierra el bloque de addOnSuccessListener)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Face detection failed", e)
                }
                // 📍 AQUÍ ES DONDE VA EL COMPLETELISTENER:
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    } // Aquí cierra processImageProxy


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
                // Abre la cámara al conceder el permiso. Respeta isRegistering si venías a enrolar.
                startCamera()
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

            // El fichaje exige PIN + cara: primero hay que ingresar el PIN.
            if (currentPin.isEmpty()) {
                Toast.makeText(this, "Ingrese su PIN primero", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // El PIN debe existir y tener un rostro enrolado en usuarios_reloj.json.
            val usuario = buscarUsuarioPorPin(currentPin)
            if (usuario == null) {
                Toast.makeText(this, "El PIN no existe o no tiene rostro enrolado", Toast.LENGTH_SHORT).show()
                // PIN inexistente: limpiamos el PIN ingresado.
                currentPin = ""
                txtPinDisplay.text = getString(R.string.pin_hint)
                return@setOnClickListener
            }

            // Modo VALIDAR: la cámara comparará el rostro SOLO contra el de este PIN.
            isRegistering = false
            pinEnValidacion = currentPin

            if (allPermissionsGranted()) {
                startCamera()
            } else {
                // Sin permiso aún: lo pedimos; al concederse, el callback abre la cámara.
                ActivityCompat.requestPermissions(
                    this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
                )
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
                    // PIN inexistente: limpiamos el PIN ingresado.
                    currentPin = ""
                    txtPinDisplay.text = getString(R.string.pin_hint)
                }
            }
        }

        // Botón Enrolar Rostro (PIN +4 y abre la cámara)
        findViewById<Button>(R.id.btnRostro).setOnClickListener {
            if (currentPin.length >= 4) { // Validamos que el PIN tenga al menos 4 dígitos
                // No permitir re-enrolar: si el PIN ya tiene un rostro guardado, bloqueamos el cambio.
                if (buscarUsuarioPorPin(currentPin) != null) {
                    Toast.makeText(this, "El PIN $currentPin ya tiene un rostro asignado", Toast.LENGTH_LONG).show()
                    currentPin = ""
                    txtPinDisplay.text = getString(R.string.pin_hint)
                    return@setOnClickListener
                }
                Toast.makeText(this, "Coloque su rostro frente a la cámara", Toast.LENGTH_LONG).show()
                // Aquí indicamos que el próximo rostro detectado será para GUARDAR (Enrolar)
                isRegistering = true
                if (allPermissionsGranted()) {
                    startCamera()
                } else {
                    // Sin permiso aún: lo pedimos; al concederse, el callback abre la cámara y enrola.
                    ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
                }
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
        try {
            // Enrola y reescribe usuarios_reloj.json CIFRADO (archivo completo) + refresca caché.
            userRepo.enroll(pin, descriptor)
            runOnUiThread {
                Toast.makeText(this, "Rostro enrolado con éxito para el PIN $pin", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("ERROR_JSON", "Error al guardar usuario: ${e.message}")
        }
    }

    // --- 2. FUNCIÓN PARA BUSCAR USUARIO (desde la caché cifrada en memoria) ---
    private fun buscarUsuarioPorPin(pin: String): UsuarioFichaje? = userRepo.findByPin(pin)

    // (La distancia euclidiana y el matching por rostro se movieron a UserRepository, tarea #5.)

    // --- 4. FUNCIÓN PARA REGISTRAR LA FICHADA (LOG DE ASISTENCIA, CIFRADO POR LÍNEA) ---
    private fun registrarFichadaExitosa(pin: String) {
        try {
            // Genera la Fichada (id único + ts), la cifra por línea y la agrega al log.
            val fichada = attendanceRepo.record(pin)
            Log.d("ASISTENCIA", "Marcaje grabado: id=${fichada.id} | PIN: ${fichada.pin}")
        } catch (e: Exception) {
            Log.e("ERROR_LOG", "No se pudo grabar la fichada", e)
        }
    }

    private fun buscarUsuarioPorRostro(nuevoDescriptor: FloatArray): UsuarioFichaje? {
        // El matching contra la caché cifrada vive en el repositorio; acá solo logueamos el resultado.
        val match = userRepo.findByFace(nuevoDescriptor)
        if (match != null) {
            Log.d("FacialBuild", "✅ Rostro IDENTIFICADO: PIN ${match.pin}")
        } else {
            Log.w("FacialBuild", "❌ Rostro NO RECONOCIDO (ningún usuario bajo el umbral)")
        }
        return match
    }

} //Class MainActivity

data class UsuarioFichaje(val pin: String, val descriptor: List<Float>)
