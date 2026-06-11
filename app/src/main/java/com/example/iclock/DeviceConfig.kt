package com.example.iclock

import android.content.Context
import java.util.UUID

/**
 * Configuración estable del equipo. Por ahora expone el [kioskId]: un identificador único del
 * kiosko, generado UNA sola vez en el primer arranque y persistido. Acompaña a cada [Fichada]
 * como metadato para que el backend sepa de qué localización vino (ver decisión D7).
 *
 * El kioskId NO es secreto (es un identificador, como un número de serie), así que vive en
 * SharedPreferences y no en el Keystore.
 */
object DeviceConfig {

    private const val PREFS = "iclock_device"
    private const val KEY_KIOSK_ID = "kiosk_id"

    private val lock = Any()

    /**
     * Devuelve el kioskId del equipo, generándolo y guardándolo la primera vez.
     * Sincronizado para que dos llamadas concurrentes en el primer arranque no generen
     * dos ids distintos.
     */
    fun kioskId(context: Context): String = synchronized(lock) {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_KIOSK_ID, null)?.let { return@synchronized it }

        val nuevo = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_KIOSK_ID, nuevo).apply()
        nuevo
    }
}
