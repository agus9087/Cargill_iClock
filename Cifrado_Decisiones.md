# Bitácora de Decisiones — Cifrado (iClock)

> **Documento vivo.** Registra, en orden, cada decisión de arquitectura relacionada con el
> cifrado de los datos del reloj, el **porqué** de cada una, y un registro de los cambios
> aplicados al código. Se agrega una entrada nueva cada vez que tomamos o cambiamos una decisión.

**Última actualización:** 2026-06-10

---

## Contexto del sistema

iClock es un reloj de fichaje que corre como **kiosko** en las localizaciones, con cientos de
usuarios fichando por PIN o reconocimiento facial. Maneja dos archivos con datos sensibles, hoy
en el almacenamiento interno privado de la app (`filesDir`):

- **`usuarios_reloj.json`** — PIN + descriptor facial de 128 dimensiones (**dato biométrico**).
- **`log_asistencia.txt`** — registro de fichadas (timestamp, PIN, status).

El proyecto era **offline** (el `AndroidManifest` solo pedía cámara). Está decidido que las fichadas
**se sincronizarán a un backend existente**: el servidor pasa a ser la **fuente de verdad** y el
equipo, un **buzón temporal (outbox)**.

## Modelo de amenaza

| El cifrado at-rest SÍ protege contra | El cifrado NO protege contra |
| --- | --- |
| Equipo rooteado | Pérdida/robo/reseteo del equipo (durabilidad) |
| Extracción por backup / ADB | → eso lo cubre la sincronización al backend |
| Extracción física del dispositivo | |
| Requisito de compliance por dato biométrico | |

---

## Decisiones de arquitectura

### D1 — Ciframos los dos archivos en reposo (at-rest)
**Decisión:** ambos archivos se guardan cifrados en el dispositivo.
**Por qué:** el almacenamiento interno ya está aislado por app en equipos no rooteados, pero los
datos son sensibles (biométricos + registro de presencia). El cifrado cubre los casos de root,
backup/ADB, extracción física y el requisito de compliance del dato biométrico.

### D2 — Camino 1: Android Keystore directo + AES-GCM (no Tink, no Jetpack EncryptedFile)
**Decisión:** ciframos con `javax.crypto.Cipher` (AES/GCM) usando una clave del Android Keystore,
sin librerías externas.
**Por qué:**
- **Jetpack Security (`androidx.security:security-crypto`) está deprecado** (1.1.0-alpha07, abr-2025);
  Google recomienda usar el Keystore directamente. No queremos código nuevo sobre una API muerta.
- **Tink** aporta rotación de claves, keysets y streaming — beneficios que **no aplican** a dos
  archivos chicos y locales. A cambio suma una dependencia y una capa más que mantener en cientos
  de kioskos en producción.
- En seguridad, **la simplicidad es una propiedad**: menos piezas = menos bugs de cripto silenciosos
  y más fácil de debuggear lejos. (Principio YAGNI.)
**Cuándo reconsiderar:** si aparece rotación de claves comprometidas o multiplataforma → Tink.

### D3 — Algoritmo AES-256-GCM (cifrado autenticado)
**Decisión:** `AES/GCM/NoPadding`, clave de 256 bits, **IV de 12 bytes nuevo por operación**,
**tag de autenticación de 128 bits**.
**Por qué:** GCM hace dos trabajos a la vez: **confidencialidad** (ciphertext) e **integridad/autenticidad**
(el tag). Si alguien altera un byte del archivo, el descifrado **falla** en vez de devolver datos
manipulados. El IV aleatorio por operación evita que el mismo texto cifrado dos veces produzca el
mismo resultado (si no, un atacante detectaría repeticiones sin descifrar nada). El IV no es secreto;
se guarda junto al dato. El único secreto es la clave.

### D4 — Clave única en el Keystore, respaldada por hardware, sin autenticación de usuario
**Decisión:** una sola clave (`alias iclock_master_key`), generada **una vez** dentro del chip seguro
(TEE/StrongBox), reutilizada siempre. **No** se usa `setUserAuthenticationRequired`.
**Por qué:** la clave nunca sale del hardware ni vive en el código. Se descartó atar la clave a la
autenticación del usuario porque el kiosko es **desatendido** y lee `usuarios_reloj.json` en cada
fichada: exigir el desbloqueo del sistema en cada lectura rompería el caso de uso, y esas claves
pueden **invalidarse solas** si se cambia el PIN de bloqueo o las huellas del equipo (riesgo de
pérdida de datos). `setUserAuthenticationRequired` solo tendría sentido para una pantalla de
administración, no para el matching automático.

### D5 — Cada archivo se cifra según su patrón de acceso
**Decisión:** los dos archivos NO se tratan igual.

- **D5a · `usuarios_reloj.json` → archivo completo + caché en memoria.**
  **Por qué:** se **escribe** pocas veces (solo al enrolar), así que reescribirlo entero está bien.
  Se **lee** en cada fichada, pero no hace falta descifrarlo cada vez: se descifra una vez al arrancar,
  queda en memoria, y se recarga solo al enrolar. Sin penalización por fichada.

- **D5b · `log_asistencia.txt` → cifrado por línea (append).**
  **Por qué:** cifrar el archivo completo obligaría a **descifrar todo + agregar + recifrar todo** en
  cada fichada → costo O(n) que crece con el log, riesgo de **perder todo el log** si se corta la luz
  a mitad de la reescritura, y problemas de concurrencia. Por línea: append O(1), una línea dañada no
  tumba el resto, y un corte solo afecta a la última línea.

### D6 — Formato por línea: `base64( [IV 12 bytes][ciphertext + tag] )` + `\n`
**Decisión:** cada fichada es un registro cifrado independiente, codificado en base64, una línea por registro.
**Por qué base64:** el resultado del cifrado son **bytes binarios crudos** que pueden contener el byte
de salto de línea, lo que rompería un archivo "un registro por línea". Base64 convierte esos bytes en
texto seguro sin saltos internos. **Base64 NO es seguridad** (no cifra ni hashea, es reversible): es solo
un sobre de formato. La seguridad viene 100% del AES-GCM de abajo. Orden: **cifrar primero, base64 después**.

### D7 — Registro estructurado (`Fichada` en JSON) en vez de texto suelto
**Decisión:** la fichada pasa de `"FECHA: ... | PIN: ... | STATUS: ..."` a un objeto JSON
(`id`, `ts`, `pin`, `status`, `kioskId`, `v`) serializado con GSON; ese JSON es lo que se cifra por línea.
**Por qué:** necesitamos un `id` para idempotencia y campos que la sync y la auditoría van a consumir;
JSON es extensible y parseable por máquina. El JSON nunca se escribe en claro: vive dentro de la línea cifrada.

### D8 — Idempotencia: `id` (UUID) por registro, generado una sola vez al fichar
**Decisión:** cada `Fichada` lleva un `id = UUID.randomUUID()` generado **en el momento del fichaje**
y persistido dentro de la línea cifrada. El backend deduplica por ese `id`.
**Por qué:** la sync hace "enviar" y "marcar enviado" en pasos no atómicos; si el equipo se apaga o la
red corta entre ambos, al reintentar se reenvía. Además, ante un timeout, el cliente no sabe si el
servidor recibió → debe reenviar por las dudas. Con un `id` estable + dedupe en el servidor, **reenviar
es seguro** (queda un solo registro). **Regla de oro:** el `id` se genera una vez y **nunca** se regenera
en reintentos (si no, cada reintento duplicaría).
**Requisito externo:** el backend debe tener índice único / upsert por `id` (ver Pendientes).

### D9 — Patrón outbox: el fichaje nunca espera a la red
**Decisión:** el marcaje escribe local (cifrado) y confirma al instante; un proceso aparte vacía el
buzón hacia el servidor cuando hay conexión.
**Por qué:** un empleado no puede esperar un round-trip HTTP frente al kiosko, y la red puede estar
caída. Desacoplar la velocidad del fichaje de la red es lo que hace usable y robusto al kiosko.

### D10 — Dos capas de cifrado independientes: at-rest + in-transit
**Decisión:** **at-rest** = Keystore + AES-GCM por línea (protege las fichadas mientras esperan en el
equipo). **In-transit** = HTTPS/TLS al backend (protege el viaje). Al sincronizar, la fichada se descifra
local y se envía por TLS.
**Por qué:** son problemas distintos (buffer local vs transporte) y se resuelven con mecanismos distintos.

### D11 — Cursor de sync; avanza solo tras confirmación 2xx
**Decisión:** un archivito-cursor recuerda hasta qué registro ya se envió; se avanza **solo** cuando el
backend confirma (2xx).
**Por qué:** permite enviar solo lo pendiente sin reescribir el log. Avanzar solo con confirmación, sumado
a la idempotencia (D8), hace el sistema seguro ante cortes: en el peor caso se reenvía y el server deduplica.

### D12 — Durabilidad de escritura: `flush()` + `fd.sync()` por append
**Decisión:** después de cada append se hace flush + fsync antes de confirmar "exitoso".
**Por qué:** el SO deja la escritura en un buffer en RAM y la baja a la flash "después"; un corte en esa
ventana perdería un registro ya dado por guardado. fsync fuerza el dato a la flash física. El costo es
despreciable a la frecuencia de un fichaje.

### D13 — Concurrencia: un único punto de escritura
**Decisión:** todos los append pasan por `synchronized(writeLock)` (un solo portón).
**Por qué:** el fichaje manual corre en el hilo de UI y el de rostro en el hilo de análisis de cámara;
dos fichadas casi simultáneas desde hilos distintos podrían pisarse y corromper una línea.

### D14 — Centralizar la E/S en una capa de datos (repositorios)
**Decisión:** sacar el `File(...)` disperso de `MainActivity` a `UserRepository` y `AttendanceRepository`.
**Por qué:** si toda lectura/escritura pasa por un solo lugar, el cifrado vive en un único punto y no se
escapa una ruta sin cifrar. Hoy, con la lógica repartida en 4 funciones, es fácil olvidarse de cifrar en una.

### D15 — Migración de archivos en texto plano
**Decisión:** un paso único al arrancar detecta archivos viejos sin cifrar y los recifra.
**Por qué:** activar el cifrado sin migrar dejaría ilegibles (o perdería) los datos ya grabados.

### D16 — Durabilidad vs cifrado: por qué el backend es imprescindible
**Decisión / advertencia:** con cifrado por Keystore, **si se pierde la clave, los datos son
irrecuperables, incluso con una copia del archivo**. La clave no se puede respaldar (no sale del hardware)
y se destruye al desinstalar la app, hacer factory reset o borrar datos.
**Por qué importa:** un backup del archivo cifrado, solo, no sirve. El respaldo **real** es **sacar los
registros del equipo** (descifrados, por TLS) hacia el servidor, donde viven independientes de la clave de
ese kiosko. Por eso la sync al backend no es un extra: es lo que permite sobrevivir a que muera un equipo.

---

## Registro de cambios en el código

| Fecha | Archivo | Cambio | Decisiones | Tarea |
| --- | --- | --- | --- | --- |
| 2026-06-10 | `SecureStorage.kt` | Creado: clave AES-256 en Keystore + `writeEncrypted`/`readDecrypted` (archivo completo) | D2, D3, D4, D5a | — |
| 2026-06-10 | `SecureStorage.kt` | `appendEncryptedLine` por línea (base64 + `flush`+`fd.sync()` + `writeLock`); `readEncryptedLines`/`readEncryptedLinesFrom`/`countLines` | D5b, D6, D12, D13 | #2 |
| 2026-06-10 | `AndroidManifest.xml` | Agregados permisos `INTERNET` y `ACCESS_NETWORK_STATE` (prerequisito de la sync) | D9, D10 | #1 |
| 2026-06-10 | `Fichada.kt` | Modelo de registro (`id`/`ts`/`pin`/`status`/`kioskId`/`v`) + `Fichada.nueva()` que genera el UUID una sola vez | D7, D8 | #3 |
| 2026-06-10 | `DeviceConfig.kt` | `kioskId` estable por equipo (UUID en SharedPreferences, generado en el 1er arranque, sincronizado) | D7 | #3 |
| 2026-06-10 | `AttendanceRepository.kt` | Capa de datos del log: `record()` (Fichada→JSON→append cifrado), `pendientes(desde)` y `total()` para la sync | D8, D9, D13, D14 | #4 |
| 2026-06-10 | `UserRepository.kt` | Capa de datos de usuarios: archivo completo cifrado + caché en memoria; `findByPin`/`findByFace`/`enroll`/`reload` | D5a, D14 | #5 |

---

## Pendientes / requisitos externos

- **Backend (idempotencia):** índice único / upsert por `id`. Sin esto, el `id` del cliente no protege
  contra reenvíos (D8).
- **Backend (contrato):** URL del endpoint, método de autenticación y formato del payload (tarea #10).
- **`allowBackup`:** hoy el `application` tiene `android:allowBackup="true"`. Para una app con biométricos
  conviene evaluar ponerlo en `false` (evita extraer los archivos cifrados vía `adb backup`). Decisión abierta.
- **Modelo `Fichada` + `kioskId`** (tarea #3) y `AttendanceRepository` (tarea #4): generan y usan el `id`.
- **`usuarios_reloj.json` administrado desde el servidor** (enrolar central → bajar a kioskos): extensión
  futura sobre la misma capa de datos.
