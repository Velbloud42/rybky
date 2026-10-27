package cz.rybky

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ApiException(message: String) : Exception(message)

/** Tenký klient k backendu (backend/server.py). Všechno je JSON. */
class Api(var token: String?) {

    suspend fun register(nick: String, password: String): JSONObject =
        call("POST", "/api/register", JSONObject().put("nick", nick).put("password", password)) as JSONObject

    suspend fun login(nick: String, password: String): JSONObject =
        call("POST", "/api/login", JSONObject().put("nick", nick).put("password", password)) as JSONObject

    suspend fun setPassword(password: String): JSONObject =
        call("POST", "/api/password", JSONObject().put("password", password)) as JSONObject

    suspend fun me(): JSONObject = call("GET", "/api/me") as JSONObject

    /** Mění jen poslané klíče: nick, motto, avatar (JPEG v base64, "" = smazat), password, showcase. Vrací nové me(). */
    suspend fun updateProfile(changes: JSONObject): JSONObject = call("POST", "/api/profile", changes) as JSONObject

    suspend fun leaderboard(): JSONObject = call("GET", "/api/leaderboard") as JSONObject

    suspend fun environments(): JSONArray = call("GET", "/api/environments") as JSONArray

    suspend fun fish(): JSONArray = call("GET", "/api/fish") as JSONArray

    suspend fun references(): JSONObject = call("GET", "/api/references") as JSONObject

    suspend fun seedReferences(embeddings: List<FloatArray>): JSONObject = call(
        "POST", "/api/references/seed", JSONObject().put("embeddings", JSONArray(embeddings.map { it.toJson() })),
    ) as JSONObject

    /** Rybky, které jde ulovit odsud, s otisky jejich okolí. */
    suspend fun nearby(lat: Double, lon: Double): JSONArray =
        call("GET", "/api/nearby?lat=$lat&lon=$lon&context=1") as JSONArray

    suspend fun map(lat: Double, lon: Double): JSONArray =
        call("GET", "/api/map?lat=$lat&lon=$lon") as JSONArray

    suspend fun createSticker(
        name: String, env: String, hint: String, fix: Fix?, embeddings: List<FloatArray>, image: String?,
    ): JSONObject = call(
        "POST", "/api/stickers",
        (fix?.json() ?: JSONObject()).put("name", name).put("env", env).put("hint", hint).put("image", image)
            .put("embeddings", JSONArray(embeddings.map { it.toJson() })),
    ) as JSONObject

    /** Úlovek: otisky všech výřezů snímku (server z nich pozná rybku i okolí) a fotka do akvária. */
    suspend fun scan(id: String, fix: Fix, crops: List<FloatArray>, image: String?): JSONObject = call(
        "POST", "/api/scan",
        fix.json().put("id", id).put("embeddings", JSONArray(crops.map { it.toJson() })).put("image", image),
    ) as JSONObject

    /** Fotka úlovku nebo vlastní samolepky (JPEG). */
    suspend fun image(path: String): ByteArray = withContext(Dispatchers.IO) {
        val conn = URL(BuildConfig.API_URL + path).openConnection() as HttpURLConnection
        try {
            token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            if (conn.responseCode != 200) throw ApiException("Fotka není")
            conn.inputStream.use { it.readBytes() }
        } catch (e: java.io.IOException) {
            throw ApiException("Fotku se nepodařilo stáhnout")
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun call(method: String, path: String, body: JSONObject? = null): Any =
        withContext(Dispatchers.IO) {
            val conn = URL(BuildConfig.API_URL + path).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 8000
                conn.readTimeout = 20000
                token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val ok = conn.responseCode in 200..299
                val text = (if (ok) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (!ok) {
                    val msg = runCatching { JSONObject(text).getString("error") }.getOrNull()
                    throw ApiException(msg ?: "Chyba serveru (${conn.responseCode})")
                }
                if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text)
            } catch (e: java.io.IOException) {
                throw ApiException("Server neodpovídá. Běží backend a adb reverse?")
            } finally {
                conn.disconnect()
            }
        }
}

/** Poloha z GPS. */
data class Fix(val lat: Double, val lon: Double, val accuracy: Float) {
    fun json(): JSONObject = JSONObject().put("lat", lat).put("lon", lon).put("accuracy", accuracy.toDouble())
}

private fun FloatArray.toJson() = JSONArray(map { Math.round(it * 10000) / 10000.0 })

/** Řetězec, nebo null, když chybí nebo je JSON null (optString by vrátil "null"). */
fun JSONObject.str(key: String): String? = if (isNull(key)) null else getString(key)

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

fun JSONArray.toFloatArray() = FloatArray(length()) { getDouble(it).toFloat() }

fun JSONArray.toEmbeddings() = (0 until length()).map { getJSONArray(it).toFloatArray() }

/** Body můžou být i půlky (rybka na předmětu): 10 → „10“, 10.5 → „10,5“. */
fun formatPoints(points: Double): String =
    if (points % 1.0 == 0.0) points.toLong().toString() else String.format(java.util.Locale.forLanguageTag("cs"), "%.1f", points)
