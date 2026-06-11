package com.example.iclock

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cifrado en reposo para los archivos sensibles del reloj:
 *   - usuarios_reloj.json  (PIN + descriptores faciales = dato biométrico)
 *   - log_asistencia.txt   (registro de fichadas)
 *
 * Diseño:
 *  - Clave AES-256 generada y custodiada por el Android Keystore (respaldo por hardware
 *    cuando el dispositivo lo soporta). La clave NUNCA sale del Keystore ni vive en el código.
 *  - Cada archivo se cifra con AES/GCM/NoPadding: confidencialidad + autenticidad (si alguien
 *    edita el archivo, el descifrado falla en vez de devolver datos corruptos).
 *  - Formato en disco: [IV 12 bytes][ciphertext + tag GCM].
 *
 * Requiere minSdk 23+ (el proyecto usa 26). Sin dependencias externas.
 */
object SecureStorage {

    private const val KEY_ALIAS = "iclock_master_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12     // recomendado para GCM
    private const val TAG_BITS = 128   // tag de autenticación GCM

    // Único punto de escritura del log: serializa los append para que el hilo de UI (PIN)
    // y el de cámara (rostro) no se pisen al grabar dos fichadas casi simultáneas.
    private val writeLock = Any()

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // .setUnlockedDeviceRequired(true)  // opcional: solo con pantalla desbloqueada
                // .setIsStrongBoxBacked(true)        // opcional: usa StrongBox si el equipo lo tiene
                .build()
        )
        return gen.generateKey()
    }

    /** Cifra [content] y lo escribe (sobrescribiendo) en [file]. */
    fun writeEncrypted(file: File, content: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(content.toByteArray(Charsets.UTF_8))
        file.outputStream().use { it.write(iv); it.write(ciphertext) }
    }

    /** Devuelve el texto descifrado de [file], o null si no existe o falla la verificación. */
    fun readDecrypted(file: File): String? {
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            if (bytes.size <= IV_SIZE) return null
            val iv = bytes.copyOfRange(0, IV_SIZE)
            val ciphertext = bytes.copyOfRange(IV_SIZE, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    // ----------------------------------------------------------------------------------
    //  CIFRADO POR LÍNEA (para el log de fichadas: append barato, un registro por línea)
    //
    //  Cada registro se cifra de forma independiente con su propio IV y se guarda como
    //  UNA línea de base64:  base64( [IV 12 bytes][ciphertext + tag GCM] )\n
    //
    //  Ventajas frente a cifrar el archivo completo:
    //   - Append de costo constante: no se reescribe lo anterior, sin importar el tamaño.
    //   - Una línea dañada se omite sin tumbar el resto del log.
    //   - Un corte de luz a mitad de escritura solo afecta a la última línea.
    // ----------------------------------------------------------------------------------

    /**
     * Cifra [plaintext] como un registro independiente y lo agrega como una nueva línea
     * al final de [file]. La operación es atómica respecto de otros append (writeLock) y
     * se fuerza a disco (flush + fsync) antes de retornar, para no confirmar una fichada
     * que todavía podría perderse en un corte de energía.
     */
    fun appendEncryptedLine(file: File, plaintext: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        // cipher.iv es el IV aleatorio que generó el Keystore para ESTE registro.
        val blob = cipher.iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)) // [IV][ct+tag]
        val line = Base64.getEncoder().encodeToString(blob)  // texto seguro, sin saltos de línea adentro
        synchronized(writeLock) {
            FileOutputStream(file, /* append = */ true).use { fos ->
                fos.write((line + "\n").toByteArray(Charsets.US_ASCII))
                fos.flush()        // del buffer de la app al sistema operativo
                fos.fd.sync()      // del sistema operativo a la flash física
            }
        }
    }

    /**
     * Descifra y devuelve todos los registros de [file], en orden.
     * Una línea corrupta o manipulada se omite (no rompe la lectura del resto).
     */
    fun readEncryptedLines(file: File): List<String> = readEncryptedLinesFrom(file, 0)

    /**
     * Descifra los registros de [file] a partir del índice de línea [fromIndex] (0-based).
     * Pensado para la sync: leer solo lo pendiente desde el cursor, sin descifrar lo ya enviado.
     */
    fun readEncryptedLinesFrom(file: File, fromIndex: Int): List<String> {
        if (!file.exists() || fromIndex < 0) return emptyList()
        val key = getOrCreateKey()
        val out = ArrayList<String>()
        file.useLines { seq ->
            seq.drop(fromIndex).forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEach
                try {
                    val blob = Base64.getDecoder().decode(line)
                    if (blob.size <= IV_SIZE) return@forEach
                    val iv = blob.copyOfRange(0, IV_SIZE)
                    val ciphertext = blob.copyOfRange(IV_SIZE, blob.size)
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                    out.add(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
                } catch (e: Exception) {
                    // Línea corrupta/manipulada/parcial: se descarta sin afectar al resto.
                }
            }
        }
        return out
    }

    /**
     * Cantidad de líneas (registros) en [file]. Sirve para que el cursor de sync sepa
     * cuántos registros existen y cuántos faltan enviar. Cuenta posiciones de línea
     * (una por append), consistente con el [fromIndex] de readEncryptedLinesFrom.
     */
    fun countLines(file: File): Int {
        if (!file.exists()) return 0
        return file.useLines { seq -> seq.count() }
    }
}
