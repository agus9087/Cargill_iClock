package com.example.iclock

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Capa de datos de los usuarios enrolados (`usuarios_reloj.json`), con cifrado de ARCHIVO
 * COMPLETO (decisión D5a). Mantiene la lista descifrada en memoria (caché): se descifra una
 * sola vez y las búsquedas de cada fichada se resuelven contra RAM, sin tocar el disco ni
 * descifrar por marcaje. El archivo solo se reescribe (cifrado) al enrolar, que ocurre pocas
 * veces.
 *
 * Reemplaza a `guardarUsuarioLocal`, `buscarUsuarioPorPin` y `buscarUsuarioPorRostro`, que hoy
 * hacen `File(...)` directo en texto plano dentro de MainActivity (decisión D14).
 */
class UserRepository(context: Context) {

    private val file = File(context.filesDir, USERS_FILENAME)
    private val gson = Gson()
    private val lock = Any()

    // Caché en memoria; se carga perezosamente la primera vez que se necesita.
    private var cache: MutableList<UsuarioFichaje>? = null

    private fun usuarios(): MutableList<UsuarioFichaje> = synchronized(lock) {
        cache ?: cargar().also { cache = it }
    }

    private fun cargar(): MutableList<UsuarioFichaje> {
        val json = SecureStorage.readDecrypted(file) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<UsuarioFichaje>>() {}.type
            gson.fromJson<MutableList<UsuarioFichaje>>(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    /** Fuerza recargar la caché desde el archivo (p. ej. después de una migración). */
    fun reload() = synchronized(lock) { cache = cargar() }

    /** Busca un usuario por PIN en la caché. */
    fun findByPin(pin: String): UsuarioFichaje? = synchronized(lock) {
        usuarios().find { it.pin == pin }
    }

    /**
     * Devuelve el usuario cuyo rostro más se parece a [descriptor], siempre que la distancia
     * euclidiana quede por debajo de [umbral]. Null si ninguno cae bajo el umbral.
     */
    fun findByFace(descriptor: FloatArray, umbral: Float = UMBRAL_DEFAULT): UsuarioFichaje? =
        synchronized(lock) {
            var mejor: UsuarioFichaje? = null
            var menor = umbral
            for (u in usuarios()) {
                val d = distancia(descriptor, u.descriptor)
                if (d < menor) {
                    menor = d
                    mejor = u
                }
            }
            mejor
        }

    /**
     * Valida el rostro [descriptor] SOLO contra el usuario asociado a [pin].
     * Devuelve ese usuario si existe y la distancia euclidiana queda por debajo de [umbral];
     * null si el PIN no está enrolado o si el rostro no coincide con el de ese PIN.
     *
     * A diferencia de [findByFace], no recorre todos los registros: compara únicamente contra
     * el descriptor del PIN ingresado (decisión: fichaje por PIN + cara).
     */
    fun matchByPin(pin: String, descriptor: FloatArray, umbral: Float = UMBRAL_DEFAULT): UsuarioFichaje? =
        synchronized(lock) {
            val usuario = usuarios().find { it.pin == pin } ?: return null
            val d = distancia(descriptor, usuario.descriptor)
            if (d < umbral) usuario else null
        }

    /**
     * Enrola (o re-enrola) un usuario: reemplaza al existente con el mismo PIN, reescribe el
     * archivo COMPLETO cifrado y refresca la caché. Devuelve el usuario creado.
     */
    fun enroll(pin: String, descriptor: FloatArray): UsuarioFichaje = synchronized(lock) {
        val lista = usuarios()
        lista.removeAll { it.pin == pin }
        val nuevo = UsuarioFichaje(pin, descriptor.toList())
        lista.add(nuevo)
        SecureStorage.writeEncrypted(file, gson.toJson(lista))
        nuevo
    }

    /** Distancia euclidiana entre el descriptor entrante y uno almacenado. */
    private fun distancia(a: FloatArray, b: List<Float>): Float {
        if (a.size != b.size) return Float.MAX_VALUE
        var suma = 0f
        for (i in a.indices) {
            val diff = a[i] - b[i]
            suma += diff * diff
        }
        return Math.sqrt(suma.toDouble()).toFloat()
    }

    companion object {
        private const val USERS_FILENAME = "usuarios_reloj.json"
        private const val UMBRAL_DEFAULT = 0.75f
    }
}
