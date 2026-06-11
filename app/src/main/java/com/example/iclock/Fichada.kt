package com.example.iclock

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Registro de una fichada (marcaje de asistencia).
 *
 * Este objeto es el CONTENIDO de cada línea del log: se serializa a JSON (GSON) y ese JSON
 * se cifra por línea con [SecureStorage.appendEncryptedLine] (ver decisiones D7 y D8 en
 * Cifrado_Decisiones.md). En disco nunca aparece en claro: vive dentro de la línea cifrada.
 *
 * El campo [id] es la base de la IDEMPOTENCIA: se genera UNA sola vez al crear la fichada
 * (ver [nueva]) y NUNCA se regenera en reintentos. El backend deduplica por [id], así que
 * reenviar la misma fichada no crea duplicados.
 */
data class Fichada(
    val id: String,                 // UUID único e inmutable del registro (idempotencia)
    val ts: String,                 // timestamp local "yyyy-MM-dd HH:mm:ss"
    val pin: String,                // PIN del empleado que fichó
    val status: String,             // resultado del marcaje (p. ej. "EXITOSO")
    val kioskId: String,            // identificador del kiosko que registró la fichada
    val v: Int = FORMATO_VERSION    // versión del formato del registro
) {
    companion object {
        /** Versión actual del formato de [Fichada]. Subir si cambian los campos. */
        const val FORMATO_VERSION = 1

        // SimpleDateFormat NO es thread-safe y las fichadas llegan desde el hilo de UI (PIN)
        // y el de cámara (rostro); una instancia por hilo evita corrupción del formato.
        private val TS_FORMAT: ThreadLocal<SimpleDateFormat> =
            object : ThreadLocal<SimpleDateFormat>() {
                override fun initialValue(): SimpleDateFormat =
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            }

        /**
         * Crea una fichada NUEVA, generando aquí (una sola vez) el [id] y el timestamp.
         *
         * Llamar SOLO en el momento del fichaje. Para reintentos de envío se reutiliza el
         * mismo registro ya persistido; nunca se vuelve a llamar a [nueva] (eso regeneraría
         * el id y rompería la idempotencia).
         */
        fun nueva(pin: String, status: String, kioskId: String): Fichada = Fichada(
            id = UUID.randomUUID().toString(),
            ts = TS_FORMAT.get()!!.format(Date()),
            pin = pin,
            status = status,
            kioskId = kioskId
        )
    }
}
