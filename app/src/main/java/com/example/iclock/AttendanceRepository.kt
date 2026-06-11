package com.example.iclock

import android.content.Context
import com.google.gson.Gson
import java.io.File

/**
 * Capa de datos del log de asistencias (decisión D14): ÚNICO punto de acceso a
 * `log_asistencia.txt`. Traduce entre objetos [Fichada] y las líneas cifradas en disco, y
 * esconde el cifrado, la serialización y la concurrencia del resto de la app.
 *
 * Rol en la arquitectura:
 *   - [record]      → camino de escritura del fichaje (actúa como Facade sobre `Fichada` +
 *                     `SecureStorage`: UUID + JSON + AES-GCM + base64 + fsync + writeLock).
 *   - [pendientes]  → lo que la sync lee desde el cursor para enviar al backend (Outbox, D9).
 */
class AttendanceRepository(context: Context) {

    private val logFile = File(context.filesDir, LOG_FILENAME)
    private val kioskId = DeviceConfig.kioskId(context)
    private val gson = Gson()

    /**
     * Registra una fichada: genera la [Fichada] (id y ts una sola vez, vía [Fichada.nueva]),
     * la serializa a JSON y la guarda cifrada por línea. El append ya es atómico respecto de
     * otros marcajes y durable (writeLock + flush + fsync dentro de [SecureStorage]).
     *
     * Devuelve la fichada creada por si el llamador la necesita (p. ej. para mostrar el id).
     */
    fun record(pin: String, status: String = STATUS_OK): Fichada {
        val fichada = Fichada.nueva(pin, status, kioskId)
        SecureStorage.appendEncryptedLine(logFile, gson.toJson(fichada))
        return fichada
    }

    /**
     * Devuelve las fichadas a partir del índice de línea [desde] (el cursor de sync),
     * descifradas y parseadas a [Fichada]. Omite líneas que [SecureStorage] no pudo descifrar
     * (corruptas/manipuladas) o cuyo JSON sea inválido, sin cortar la lectura del resto.
     */
    fun pendientes(desde: Int): List<Fichada> =
        SecureStorage.readEncryptedLinesFrom(logFile, desde).mapNotNull { json ->
            try {
                gson.fromJson(json, Fichada::class.java)
            } catch (e: Exception) {
                null
            }
        }

    /** Cantidad total de registros en el log. Sirve para comparar contra el cursor de sync. */
    fun total(): Int = SecureStorage.countLines(logFile)

    companion object {
        private const val LOG_FILENAME = "log_asistencia.txt"
        private const val STATUS_OK = "EXITOSO"
    }
}
