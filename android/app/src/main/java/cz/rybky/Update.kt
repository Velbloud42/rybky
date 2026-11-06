package cz.rybky

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** „0.13“ je novější než „0.12“ (porovnává se číslo po čísle, ne jako text). */
fun isNewer(latest: String, current: String): Boolean {
    val a = latest.split('.').map { it.toIntOrNull() ?: 0 }
    val b = current.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(a.size, b.size)) {
        val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
        if (d != 0) return d > 0
    }
    return false
}

/** Stáhne APK do cache; [progress] dostává 0..1 (nebo -1, když server neřekne velikost). */
private fun download(url: String, target: File, progress: (Float) -> Unit) {
    target.parentFile?.mkdirs()
    // GitHub přesměruje na svoje úložiště; HttpURLConnection přesměrování v rámci https sleduje sám.
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        if (conn.responseCode != 200) throw ApiException("Aktualizaci se nepodařilo stáhnout (${conn.responseCode})")
        val total = conn.contentLengthLong
        val partial = File(target.path + ".part")
        conn.inputStream.use { input ->
            partial.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    done += n
                    progress(if (total > 0) done.toFloat() / total else -1f)
                }
            }
        }
        partial.renameTo(target)
    } catch (e: java.io.IOException) {
        throw ApiException("Aktualizaci se nepodařilo stáhnout. Zkontroluj připojení.")
    } finally {
        conn.disconnect()
    }
}

/**
 * Otevře instalaci staženého APK. Poprvé musí uživatel Rybkám povolit instalaci aplikací –
 * pak se otevře nastavení a vrátí false (po povolení stačí klepnout znovu).
 */
private fun install(context: Context, apk: File): Boolean {
    if (!context.packageManager.canRequestPackageInstalls()) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return false
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
    context.startActivity(
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    return true
}

/**
 * Po spuštění se zeptá serveru na nejnovější verzi (stejné vydání na GitHubu jako na rybky.pravn.uk)
 * a když je novější, nabídne ji stáhnout a nainstalovat přímo v aplikaci.
 * Vývojová verze se neaktualizuje – je podepsaná jiným klíčem, takže by vydání z GitHubu nešlo nainstalovat.
 */
@Composable
fun UpdatePrompt(api: Api) {
    if (BuildConfig.DEBUG) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<JSONObject?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var apk by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { api.version() }
            .onSuccess { if (isNewer(it.getString("version"), BuildConfig.VERSION_NAME)) info = it }
    }
    val update = info ?: return
    val version = update.getString("version")

    fun start() {
        val ready = apk
        if (ready != null) {
            install(context, ready)
            return
        }
        error = null
        progress = 0f
        scope.launch {
            val target = File(context.cacheDir, "updates/rybky-$version.apk")
            runCatching {
                withContext(Dispatchers.IO) { download(update.getString("url"), target) { p -> progress = p } }
            }.onSuccess {
                apk = target
                install(context, target)
            }.onFailure { error = it.message }
            progress = null
        }
    }

    AlertDialog(
        onDismissRequest = { if (progress == null) info = null },
        title = { Text("Nová verze $version") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Máš ${BuildConfig.VERSION_NAME}. Aktualizace se stáhne a nainstaluje přes tuhle verzi, účet i rybky zůstanou.")
                val notes = update.optJSONArray("notes")
                if (notes != null && notes.length() > 0) {
                    Text("Co je nového", fontWeight = FontWeight.Bold)
                    for (i in 0 until notes.length()) Text("• ${notes.getString(i)}", fontSize = 14.sp)
                }
                progress?.let { p ->
                    if (p >= 0f) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Stahuju…", fontSize = 13.sp)
                }
                if (apk != null && progress == null) {
                    Text(
                        "Staženo. Když se instalace neotevřela, povol Rybkám instalaci aplikací a klepni na Nainstalovat.",
                        fontSize = 13.sp,
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = ::start, enabled = progress == null) {
                Text(if (apk != null) "Nainstalovat" else "Aktualizovat")
            }
        },
        dismissButton = {
            TextButton(onClick = { info = null }, enabled = progress == null) { Text("Později") }
        },
    )
}
