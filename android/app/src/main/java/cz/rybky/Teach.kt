package cz.rybky

import android.graphics.Bitmap
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.abs
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.Canvas
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.RotateRight
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
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
/** [embeddings] = otisky výřezů různé velikosti; [rybka] = výsledek kontroly „je to rybka“ (skóre, prošlo). */
private class Shot(
    val image: Bitmap, val embeddings: List<FloatArray>, val fromGallery: Boolean, val rybka: Pair<Float, Boolean>?,
)

/**
 * Úprava fotky z galerie: fotka jde posunout, přiblížit a otočit, výběrový čtverec posunout a zvětšit.
 * Souřadnice jsou v pixelech plochy editoru ([area]); fotka je v ní vycentrovaná jako ContentScale.Fit.
 */
private class PhotoEdit(val photo: Bitmap) {
    var scale by mutableFloatStateOf(1f)
    var rotation by mutableFloatStateOf(0f)
    var offset by mutableStateOf(Offset.Zero)
    var area by mutableStateOf(IntSize.Zero)
    var center by mutableStateOf(Offset.Zero) // střed výběru
    var side by mutableFloatStateOf(0f)       // strana výběru

    fun place(size: IntSize) {
        if (size == area && side > 0f) return
        area = size
        side = minOf(size.width, size.height) * 0.8f
        center = Offset(size.width / 2f, size.height / 2f)
    }

    fun reset() { scale = 1f; rotation = 0f; offset = Offset.Zero; side = 0f; place(area) }

    /** Dvěma prsty: přiblížení a otočení kolem bodu mezi prsty, k tomu posun. */
    fun transform(centroid: Offset, pan: Offset, zoom: Float, degrees: Float) {
        // Při zvednutí prstů střed gesta neexistuje (NaN) – s ním by fotka „zmizela“ do černa.
        if (!centroid.isSpecified || zoom.isNaN() || degrees.isNaN()) return
        val newScale = (scale * zoom).coerceIn(0.3f, 10f)
        val mid = Offset(area.width / 2f, area.height / 2f)
        val rad = Math.toRadians(degrees.toDouble())
        val v = (mid + offset - centroid) * (newScale / scale)
        val turned = Offset(
            (v.x * cos(rad) - v.y * sin(rad)).toFloat(),
            (v.x * sin(rad) + v.y * cos(rad)).toFloat(),
        )
        scale = newScale
        rotation += degrees
        offset = centroid + turned - mid + pan
    }

    fun inSquare(p: Offset) = abs(p.x - center.x) <= side / 2 && abs(p.y - center.y) <= side / 2

    /** Roh výběru pod prstem jako znaménka (±1, ±1), nebo null. */
    fun cornerAt(p: Offset, reach: Float): Offset? =
        listOf(Offset(-1f, -1f), Offset(1f, -1f), Offset(-1f, 1f), Offset(1f, 1f))
            .firstOrNull { (center + it * (side / 2) - p).getDistance() <= reach }

    fun moveSquare(pan: Offset) { center = clamp(center + pan, side) }

    /** Tažení rohu: protější roh zůstane na místě. */
    fun resize(corner: Offset, p: Offset, minSide: Float) {
        val fixed = center - Offset(corner.x * side / 2, corner.y * side / 2)
        val limit = minOf(area.width, area.height).toFloat()
        val newSide = maxOf((p.x - fixed.x) * corner.x, (p.y - fixed.y) * corner.y).coerceIn(minSide, limit)
        side = newSide
        center = clamp(fixed + Offset(corner.x * newSide / 2, corner.y * newSide / 2), newSide)
    }

    private fun clamp(c: Offset, s: Float) = Offset(
        c.x.coerceIn(s / 2, area.width - s / 2), c.y.coerceIn(s / 2, area.height - s / 2),
    )

    /** Čtverec přesně tak, jak je vidět ve výběru (výsledek má [out] px). */
    fun render(out: Int = 1024): Bitmap {
        val base = minOf(area.width.toFloat() / photo.width, area.height.toFloat() / photo.height)
        val k = out / side
        val matrix = Matrix().apply {
            postTranslate(-photo.width / 2f, -photo.height / 2f)
            postScale(base * scale, base * scale)
            postRotate(rotation)
            postTranslate(area.width / 2f + offset.x, area.height / 2f + offset.y)
            postTranslate(-(center.x - side / 2), -(center.y - side / 2))
            postScale(k, k)
        }
        return Bitmap.createBitmap(out, out, Bitmap.Config.ARGB_8888).also {
            Canvas(it).apply {
                drawColor(android.graphics.Color.BLACK)
                drawBitmap(photo, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            }
        }
    }
}

/**
 * Nalepení: kamera přes celou obrazovku, po vyfocení vyjede panel s názvem, prostředím a polohou.
 * Místo focení jde vybrat fotku z galerie a souřadnice napsat ručně.
 */
@Composable
fun TeachScreen(api: Api, embedder: Embedder) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot = remember { CameraSnapshot() }
    val latest = remember { AtomicReference<List<FloatArray>?>(null) }
    var shot by remember { mutableStateOf<Shot?>(null) }
    var edit by remember { mutableStateOf<PhotoEdit?>(null) }
    var editing by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val form = remember { StickerForm() }
    var rybka by remember { mutableStateOf<Rybka?>(null) }
    var score by remember { mutableStateOf<Pair<Float, Boolean>?>(null) }
    LaunchedEffect(Unit) { runCatching { loadRybka(api) }.onSuccess { rybka = it } }

    // Fotka z galerie se nejdřív otevře v editoru, kde jde přiblížit, otočit a posunout.
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.Default) { loadFullPhoto(context, uri) } }
                .onSuccess { edit = PhotoEdit(it); editing = true; shot = null }
                .onFailure { error = "Fotku se nepodařilo načíst" }
        }
    }

    val sticker = done
    if (sticker != null) {
        Centered {
            val name = sticker.getString("name")
            val catchPoints = sticker.optString("catchPointsText").ifEmpty { "?" }
            val authorPoints = sticker.optInt("authorPoints")
            Text(
                when (sticker.optString("env")) {
                    TATTOO -> "Tetování „$name“ je ve hře!"
                    OBJECT -> "Předmět „$name“ je ve hře!"
                    else -> "„$name“ nalepeno!"
                },
                fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            FishReveal(
                name = sticker.str("fishName"),
                fish = sticker.getString("fish"),
                rarity = sticker.getString("rarity"),
                envName = sticker.optString("envName").ifEmpty { null },
                points = "$catchPoints b. za úlovek",
                details = "Kdo ji uloví, dostane $catchPoints b." +
                    if (authorPoints > 0) " a ty pokaždé $authorPoints b." else " Za předmět autor body nedostává.",
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = { form.clear(); shot = null; done = null }) { Text("Nalepit další") }
        }
        return
    }

    val e = edit
    if (editing && e != null) {
        PhotoEditor(
            e,
            onCancel = { editing = false; if (shot == null) edit = null },
            onDone = {
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.Default) {
                            val photo = e.render()
                            val crops = embedder.embedCrops(photo)
                            Shot(photo, crops, true, rybka?.check(crops))
                        }
                    }.onSuccess { shot = it }.onFailure { error = "Fotku se nepodařilo upravit" }
                    editing = false
                }
            },
        )
        return
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val s = shot
        if (s == null) {
            CameraView(Modifier.fillMaxSize(), snapshot) { frame ->
                val crops = embedder.embedCrops(frame)
                latest.set(crops)
                score = rybka?.check(crops)
            }
            val recognized = rybka.recognizes(score)
            val r = rybka
            val sc = score?.first
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
                            val crops = latest.get()
                            val image = snapshot.take()
                            if (crops != null && image != null) {
                                error = null
                                shot = Shot(image, crops, false, score)
                            }
                        },
                )
                Spacer(Modifier.size(52.dp))
            }
        } else {
            Image(s.image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            StickerSheet(
                api, s, form, rybka,
                onRetake = { shot = null; edit = null },
                onEdit = if (s.fromGallery && e != null) ({ editing = true }) else null,
                onDone = { done = it; edit = null },
            )
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
    api: Api, shot: Shot, form: StickerForm, rybka: Rybka?, onRetake: () -> Unit, onEdit: (() -> Unit)?,
    onDone: (JSONObject) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var envs by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var fix by remember { mutableStateOf<Fix?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val manualFix = parseCoords(form.coords)
    val tattoo = form.env == TATTOO
    val portable = isPortable(form.env)
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
            Text(
                when (form.env) { TATTOO -> "Nové tetování"; OBJECT -> "Nový předmět s rybkou"; else -> "Nová rybka" },
                fontSize = 24.sp, fontWeight = FontWeight.Bold,
            )
            if (!recognized) Text(
                "Na fotce nevidím rybku z Rybek" + (shot.rybka?.let { " (shoda ${(it.first * 100).toInt()} %)" } ?: "") +
                    if (onEdit != null) ". Přibliž ji ve výřezu." else ". Vyfoť ji zblízka.",
                color = MaterialTheme.colorScheme.error,
            )
            if (onEdit != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    shot.image.asImageBitmap(), null,
                    Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = onEdit) {
                    Icon(Icons.Outlined.Crop, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Upravit výřez")
                }
            }
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
                label = { Text(when (form.env) { TATTOO -> "Jméno osoby"; OBJECT -> "Co to je"; else -> "Název" }) },
                placeholder = {
                    Text(when (form.env) { TATTOO -> "Kdo tetování nosí"; OBJECT -> "Např. hrnek u Edy"; else -> "Např. U lávky" })
                },
                singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                form.hint, { form.hint = it.take(120) },
                label = { Text(if (portable) "Kde ho potkáš (nepovinné)" else "Nápověda pro hledače (nepovinná)") },
                singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
            )
            if (portable) Text(
                if (tattoo) "Tetování nemá polohu ani není na mapě – ulovit ho jde kdekoli, kde člověka potkáš."
                else "Předmět nemá polohu ani není na mapě. Úlovek je za půl bodu.",
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
                                    portable -> null
                                    shot.fromGallery -> manualFix!!
                                    else -> currentFix(context).also { fix = it }
                                }
                                val photo = withContext(Dispatchers.Default) { shot.image.jpegBase64() }
                                api.createSticker(form.name.trim(), form.env!!, form.hint, f, shot.embeddings, photo)
                            }.onSuccess(onDone).onFailure { error = it.message }
                            busy = false
                        }
                    },
                    enabled = !busy && recognized && form.name.trim().length >= 2 && form.env != null &&
                        (portable || !shot.fromGallery || manualFix != null),
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

private enum class Drag { Photo, Square, Corner }

/**
 * Editor fotky z galerie. Jedním prstem se posouvá výběrový čtverec (za rohy se zvětšuje) nebo fotka,
 * dvěma prsty se fotka přibližuje a otáčí. Použije se, co je ve čtverci; slabší rámeček uvnitř ukazuje,
 * kam patří samotná rybka – stejně jako zaměřovací čtverec u focení.
 */
@Composable
private fun PhotoEditor(edit: PhotoEdit, onCancel: () -> Unit, onDone: () -> Unit) {
    var busy by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            Modifier.fillMaxSize().clipToBounds()
                .onSizeChanged { edit.place(it) }
                .pointerInput(edit) {
                    val reach = 36.dp.toPx()
                    val minSide = 96.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val corner = edit.cornerAt(down.position, reach)
                        var drag = when {
                            corner != null -> Drag.Corner
                            edit.inSquare(down.position) -> Drag.Square
                            else -> Drag.Photo
                        }
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } >= 2) drag = Drag.Photo // dvěma prsty vždy fotka
                            when (drag) {
                                Drag.Photo -> edit.transform(
                                    event.calculateCentroid(useCurrent = false), event.calculatePan(),
                                    event.calculateZoom(), event.calculateRotation(),
                                )
                                Drag.Square -> edit.moveSquare(event.calculatePan())
                                Drag.Corner -> event.changes.firstOrNull { it.id == down.id }
                                    ?.let { edit.resize(corner!!, it.position, minSide) }
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                },
        ) {
            Image(
                edit.photo.asImageBitmap(), null,
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = edit.scale; scaleY = edit.scale; rotationZ = edit.rotation
                    translationX = edit.offset.x; translationY = edit.offset.y
                },
                contentScale = ContentScale.Fit,
            )
            SelectionOverlay(edit)
        }
        Text(
            "Čtverec posuň prstem a za rohy zvětši. Fotku dvěma prsty přibliž a otoč. " +
                "Rybka patří do vnitřního rámečku, kolem ní kousek okolí.",
            color = Color.White, textAlign = TextAlign.Center, fontSize = 14.sp,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(16.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val round = Modifier.size(52.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape)
            IconButton(onClick = onCancel, modifier = round) { Icon(Icons.Outlined.Close, "Zpět", tint = Color.White) }
            IconButton(onClick = { edit.rotation += 90f }, modifier = round) {
                Icon(Icons.Outlined.RotateRight, "Otočit o 90°", tint = Color.White)
            }
            IconButton(onClick = { edit.reset() }, modifier = round) {
                Icon(Icons.Outlined.RestartAlt, "Vrátit na začátek", tint = Color.White)
            }
            Button(
                onClick = { busy = true; onDone() },
                enabled = edit.side > 0f && !busy,
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text(if (busy) "Chvilku…" else "Hotovo") }
        }
    }
}

/** Ztmavení mimo výběr, rámeček výběru s úchyty v rozích a vodítko pro rybku uprostřed. */
@Composable
private fun SelectionOverlay(edit: PhotoEdit) {
    Canvas(Modifier.fillMaxSize()) {
        val half = edit.side / 2
        val tl = edit.center - Offset(half, half)
        val shade = Color.Black.copy(alpha = 0.6f)
        drawRect(shade, Offset.Zero, Size(size.width, tl.y))
        drawRect(shade, Offset(0f, tl.y + edit.side), Size(size.width, size.height - tl.y - edit.side))
        drawRect(shade, Offset(0f, tl.y), Size(tl.x, edit.side))
        drawRect(shade, Offset(tl.x + edit.side, tl.y), Size(size.width - tl.x - edit.side, edit.side))
        drawRect(Amber, tl, Size(edit.side, edit.side), style = Stroke(3.dp.toPx()))
        val aim = edit.side * AIM_FRACTION
        drawRoundRect(
            Color.White.copy(alpha = 0.7f), edit.center - Offset(aim / 2, aim / 2), Size(aim, aim),
            CornerRadius(16.dp.toPx()),
            style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))),
        )
        listOf(Offset(-1f, -1f), Offset(1f, -1f), Offset(-1f, 1f), Offset(1f, 1f)).forEach {
            val c = edit.center + Offset(it.x * half, it.y * half)
            drawCircle(Color.White, 10.dp.toPx(), c)
            drawCircle(Amber, 10.dp.toPx(), c, style = Stroke(3.dp.toPx()))
        }
    }
}
