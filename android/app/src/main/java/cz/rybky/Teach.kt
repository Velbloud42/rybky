package cz.rybky

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.SetMeal
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/** Vyfocená (nebo z galerie vybraná) samolepka. U fotky z galerie se souřadnice zadávají ručně. */
private class Shot(val image: Bitmap, val embedding: FloatArray, val fromGallery: Boolean, val rybka: Float?)

/**
 * Nalepení: kamera přes celou obrazovku, po vyfocení vyjede panel s názvem, prostředím a polohou.
 * Místo focení jde vybrat fotku z galerie a souřadnice napsat ručně.
 */
@Composable
fun TeachScreen(api: Api, embedder: Embedder) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot = remember { CameraSnapshot() }
    val latest = remember { AtomicReference<FloatArray?>(null) }
    var shot by remember { mutableStateOf<Shot?>(null) }
    var done by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val form = remember { StickerForm() }
    var rybka by remember { mutableStateOf<Rybka?>(null) }
    var score by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) { runCatching { loadRybka(api) }.onSuccess { rybka = it } }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.Default) {
                    val photo = loadPhoto(context, uri)
                    val emb = embedder.embed(photo)
                    Shot(photo, emb, true, rybka?.similarity(emb))
                }
            }.onSuccess { shot = it }.onFailure { error = "Fotku se nepodařilo načíst" }
        }
    }

    val sticker = done
    if (sticker != null) {
        Centered {
            Icon(Icons.Outlined.SetMeal, null, Modifier.size(72.dp), tint = Amber)
            Text("Nalepeno!", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(
                "„${sticker.getString("name")}“ skrývá rybu ${sticker.getString("fish")} (${sticker.getString("rarity")}).",
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = { form.clear(); shot = null; done = null }) { Text("Nalepit další") }
        }
        return
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val s = shot
        if (s == null) {
            CameraView(Modifier.fillMaxSize(), snapshot) { frame ->
                val emb = embedder.embed(frame)
                latest.set(emb)
                score = rybka?.similarity(emb)
            }
            val recognized = rybka.recognizes(score)
            val r = rybka
            val sc = score
            AimSquare(if (recognized) Amber else Color.White)
            Text(
                error ?: when {
                    r == null || sc == null -> "Zaměř rybku do čtverce a vyfoť ji"
                    r.learning -> "Učím se rybky ${r.learned}/${r.learningTarget} · shoda ${(sc * 100).toInt()} %"
                    else -> "Rybka ${(sc * 100).toInt()} % · " + if (recognized) "rozpoznána" else "zatím ji nevidím"
                },
                color = Color.White,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 28.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.size(52.dp).background(Color.Black.copy(alpha = 0.4f), CircleShape),
                ) { Icon(Icons.Outlined.PhotoLibrary, "Nahrát z galerie", tint = Color.White) }
                // Spoušť ve stylu foťáku: bílý kruh s okrajem.
                Box(
                    Modifier.size(80.dp).border(4.dp, Color.White, CircleShape).padding(8.dp)
                        .clip(CircleShape).background(Color.White)
                        .clickable {
                            val emb = latest.get()
                            val image = snapshot.take()
                            if (emb != null && image != null) {
                                error = null
                                shot = Shot(image, emb, false, score)
                            }
                        },
                )
                Spacer(Modifier.size(52.dp))
            }
        } else {
            Image(s.image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            StickerSheet(api, s, form, rybka, onRetake = { shot = null }, onDone = { done = it })
        }
    }
}

/** Co uživatel vyplnil – drží se i při přefocení. */
private class StickerForm {
    var name by mutableStateOf("")
    var env by mutableStateOf<String?>(null)
    var hint by mutableStateOf("")
    var coords by mutableStateOf("")
    fun clear() { name = ""; env = null; hint = ""; coords = "" }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun StickerSheet(
    api: Api, shot: Shot, form: StickerForm, rybka: Rybka?, onRetake: () -> Unit, onDone: (JSONObject) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var envs by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var fix by remember { mutableStateOf<Fix?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val manualFix = parseCoords(form.coords)
    val tattoo = form.env == TATTOO
    val recognized = rybka.recognizes(shot.rybka)

    LaunchedEffect(Unit) {
        runCatching { api.environments().objects() }.onSuccess { envs = it }.onFailure { error = it.message }
        if (!shot.fromGallery) runCatching { currentFix(context) }.onSuccess { fix = it }
    }

    ModalBottomSheet(
        onDismissRequest = onRetake,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (tattoo) "Nové tetování" else "Nová rybka", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            if (!recognized) Text(
                "Na fotce nevidím rybku z Rybek" + (shot.rybka?.let { " (shoda ${(it * 100).toInt()} %)" } ?: "") +
                    ". Vyfoť samolepku nebo tetování zblízka.",
                color = MaterialTheme.colorScheme.error,
            )
            Text("Kde samolepka visí?", fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                envs.forEach { e ->
                    val id = e.getString("id")
                    FilterChip(
                        selected = form.env == id, onClick = { form.env = id },
                        label = { Text(e.getString("name")) },
                        leadingIcon = { ENV_ICONS[id]?.let { Icon(it, null, Modifier.size(18.dp)) } },
                    )
                }
            }
            OutlinedTextField(
                form.name, { form.name = it.take(40) },
                label = { Text(if (tattoo) "Jméno osoby" else "Název") },
                placeholder = { Text(if (tattoo) "Kdo tetování nosí" else "Např. U lávky") },
                singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                form.hint, { form.hint = it.take(120) },
                label = { Text(if (tattoo) "Kde ho potkáš (nepovinné)" else "Nápověda pro hledače (nepovinná)") },
                singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
            )
            if (tattoo) Text(
                "Tetování nemá polohu ani není na mapě – ulovit ho jde kdekoli, kde člověka potkáš.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            ) else if (shot.fromGallery) OutlinedTextField(
                form.coords, { form.coords = it },
                label = { Text("Souřadnice") },
                placeholder = { Text("50.0755, 14.4378") },
                isError = form.coords.isNotBlank() && manualFix == null,
                supportingText = { Text("Zkopíruješ je třeba z Mapy.com") },
                trailingIcon = {
                    IconButton(onClick = {
                        scope.launch {
                            runCatching { currentFix(context) }
                                .onSuccess { form.coords = "%.6f, %.6f".format(Locale.US, it.lat, it.lon) }
                                .onFailure { error = it.message }
                        }
                    }) { Icon(Icons.Outlined.MyLocation, "Moje poloha") }
                },
                singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
            ) else Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Place, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Text(
                    fix?.let { "Poloha zjištěna, přesnost ±${it.accuracy.toInt()} m" } ?: "Zjišťuju polohu…",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onRetake, Modifier.weight(1f).height(52.dp)) {
                    Text(if (shot.fromGallery) "Jiná fotka" else "Přefotit")
                }
                Button(
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            runCatching {
                                val f = when {
                                    tattoo -> null
                                    shot.fromGallery -> manualFix!!
                                    else -> currentFix(context).also { fix = it }
                                }
                                api.createSticker(form.name.trim(), form.env!!, form.hint, f, listOf(shot.embedding))
                            }.onSuccess(onDone).onFailure { error = it.message }
                            busy = false
                        }
                    },
                    enabled = !busy && recognized && form.name.trim().length >= 2 && form.env != null &&
                        (tattoo || !shot.fromGallery || manualFix != null),
                    modifier = Modifier.weight(2f).height(52.dp),
                ) {
                    Icon(Icons.Outlined.AddLocationAlt, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (busy) "Ukládám…" else "Nalepit")
                }
            }
        }
    }
}
