package cz.rybky

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.material.icons.outlined.LocationCity
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Park
import androidx.compose.material.icons.outlined.Phishing
import androidx.compose.material.icons.outlined.SetMeal
import androidx.compose.material.icons.outlined.Water
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Stejná hranice jako MIN_SIMILARITY na serveru. */
const val MIN_SIMILARITY = 0.75f

/** Kolik snímků po sobě musí sedět, než se pošle úlovek (aby nestačil jeden náhodný snímek). */
const val MATCH_STREAK = 3

/** Pozadí ikony aplikace. */
private val FishGreen = Color(0xFF18450B)

val ENV_ICONS = mapOf(
    "voda" to Icons.Outlined.Water,
    "park" to Icons.Outlined.Park,
    "mesto" to Icons.Outlined.LocationCity,
    "uvnitr" to Icons.Outlined.Home,
    "vrchol" to Icons.Outlined.Landscape,
    "tetovani" to Icons.Outlined.Brush,
    "predmet" to Icons.Outlined.LocalCafe,
)

/**
 * Galerie vzorů ze serveru: „takhle vypadá rybka“ (positives) a „tohle rybka není“ (negatives, označuje admin).
 * Prvních [learningTarget] samolepek se nekontroluje – server se z nich teprve učí.
 */
class Rybka(
    val positives: List<FloatArray>, val negatives: List<FloatArray>, val minSimilarity: Float,
    val learning: Boolean, val learned: Int, val learningTarget: Int,
) {
    /** Skóre nejlepšího výřezu a jestli prošel: dost podobný rybce a víc než čemukoli, co rybka není. */
    fun check(crops: List<FloatArray>): Pair<Float, Boolean>? {
        if (positives.isEmpty()) return null
        return crops.map { c ->
            val pos = positives.maxOf { cosine(c, it) }
            val neg = negatives.maxOfOrNull { cosine(c, it) } ?: -1f
            pos to (pos >= minSimilarity && pos > neg)
        }.maxWith(compareBy({ it.second }, { it.first }))
    }
}

/** Dokud galerie vzorů není (nebo se nenačetla), nic se neblokuje – rozhodne server. */
fun Rybka?.recognizes(check: Pair<Float, Boolean>?) = this == null || learning || check == null || check.second

const val TATTOO = "tetovani"
const val OBJECT = "predmet"

/** Tetování a předměty nemají polohu – nosí se s sebou a loví se kdekoli. */
fun isPortable(env: String?) = env == TATTOO || env == OBJECT

suspend fun loadRybka(api: Api): Rybka {
    val r = api.references()
    val list = r.getJSONArray("embeddings")
    return Rybka(
        list.toEmbeddings(),
        r.optJSONArray("negatives")?.toEmbeddings().orEmpty(),
        r.getDouble("minSimilarity").toFloat(),
        r.optBoolean("learning"), r.optInt("learned"), r.optInt("learningTarget"),
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RybkyTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { App() }
            }
        }
    }
}

private val PERMISSIONS = arrayOf(
    Manifest.permission.CAMERA,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

private fun Context.hasPermissions() = PERMISSIONS.take(2).all {
    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
}

@Composable
fun App() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("rybky", Context.MODE_PRIVATE) }
    var token by remember { mutableStateOf(prefs.getString("token", null)) }
    var granted by remember { mutableStateOf(context.hasPermissions()) }
    val askPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted = context.hasPermissions() }
    LaunchedEffect(Unit) { if (!granted) askPermissions.launch(PERMISSIONS) }

    val api = remember { Api(token) }
    api.token = token

    when {
        token == null -> LoginScreen(api) { newToken ->
            prefs.edit().putString("token", newToken).apply()
            token = newToken
        }
        !granted -> Centered {
            Text("Rybky potřebují kameru a polohu.")
            Spacer(Modifier.height(12.dp))
            Button(onClick = { askPermissions.launch(PERMISSIONS) }) { Text("Povolit") }
        }
        else -> MainScreen(api) {
            prefs.edit().remove("token").apply()
            token = null
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector, val fullScreen: Boolean) {
    Hunt("Lov", Icons.Outlined.Phishing, true),
    Map("Mapa", Icons.Outlined.Map, false),
    Aquarium("Akvárium", Icons.Outlined.SetMeal, false),
    Teach("Nalepit", Icons.Outlined.AddLocationAlt, true),
}

@Composable
fun MainScreen(api: Api, onLogout: () -> Unit) {
    val context = LocalContext.current
    val embedder = remember { Embedder(context) }
    DisposableEffect(Unit) { onDispose { embedder.close() } }
    var tab by remember { mutableStateOf(Tab.Hunt) }
    LaunchedEffect(Unit) {
        // Jednou za instalaci pošle výchozí vzory rybky (server je přijme jen do prázdné galerie).
        val prefs = context.getSharedPreferences("rybky", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("seeded", false)) runCatching {
            val shots = withContext(Dispatchers.Default) { referenceImages(context).map { embedder.embed(it) } }
            api.seedReferences(shots)
        }.onSuccess { prefs.edit().putBoolean("seeded", true).apply() }
    }

    Scaffold(bottomBar = {
        NavigationBar {
            Tab.entries.forEach { t ->
                NavigationBarItem(
                    selected = tab == t, onClick = { tab = t },
                    icon = { Icon(t.icon, null) }, label = { Text(t.label) },
                )
            }
        }
    }) { padding ->
        // Kamera jde až pod stavový řádek, ostatní obrazovky ne.
        val used = if (tab.fullScreen) PaddingValues(bottom = padding.calculateBottomPadding()) else padding
        Box(Modifier.padding(used).consumeWindowInsets(used).fillMaxSize()) {
            when (tab) {
                Tab.Hunt -> HuntScreen(api, embedder)
                Tab.Map -> MapScreen(api)
                Tab.Aquarium -> ProfileScreen(api, onLogout)
                Tab.Teach -> TeachScreen(api, embedder)
            }
        }
    }
}

// --- registrace a přihlášení ---------------------------------------------

@Composable
fun LoginScreen(api: Api, onLoggedIn: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var existing by remember { mutableStateOf(false) }
    var nick by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Centered {
        Box(Modifier.size(120.dp).clip(CircleShape).background(FishGreen), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ryba), null, Modifier.fillMaxWidth(0.75f))
        }
        Spacer(Modifier.height(12.dp))
        Text("Rybky", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text("Lov samolepek po městě", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        SingleChoiceSegmentedButtonRow {
            listOf("Nový hráč", "Mám účet").forEachIndexed { i, label ->
                SegmentedButton(
                    selected = existing == (i == 1), onClick = { existing = i == 1; error = null },
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                ) { Text(label) }
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(nick, { nick = it }, label = { Text("Přezdívka") }, singleLine = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            password, { password = it }, label = { Text("Heslo") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = if (existing) {
                { Text("Účet bez hesla si nastaví heslo prvním přihlášením.") }
            } else null,
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                busy = true
                error = null
                scope.launch {
                    runCatching { if (existing) api.login(nick.trim(), password) else api.register(nick.trim(), password) }
                        .onSuccess { onLoggedIn(it.getString("token")) }
                        .onFailure { error = it.message }
                    busy = false
                }
            },
            enabled = !busy && nick.isNotBlank() && password.isNotEmpty(),
        ) { Text(if (existing) "Přihlásit se" else "Začít lovit") }
    }
}

// --- lov: živá kamera rozpoznává samolepky v okolí -----------------------

/** Rybka, kterou kamera porovnává. Vlastní a už ulovené se jen poznají, ulovit nejdou. */
private class Candidate(
    val id: String, val embeddings: List<FloatArray>, val kind: String, val name: String?, val minSimilarity: Float,
    val own: Boolean, val caught: Boolean,
) {
    val portable get() = isPortable(kind)
    val catchable get() = !own && !caught
    val label get() = when (kind) {
        TATTOO -> "Tetování · $name"
        OBJECT -> "Předmět · $name"
        else -> "Samolepka"
    } + when {
        own -> " (tvoje)"
        caught -> " (už ulovená)"
        else -> ""
    }
}

@Composable
fun HuntScreen(api: Api, embedder: Embedder) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var candidates by remember { mutableStateOf<List<Candidate>?>(null) }
    var fix by remember { mutableStateOf<Fix?>(null) }
    var similarity by remember { mutableStateOf(0f) }
    var bestMatch by remember { mutableStateOf<Candidate?>(null) }
    var streak by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val snapshot = remember { CameraSnapshot() }

    LaunchedEffect(reload) {
        error = null
        candidates = null
        runCatching {
            val f = currentFix(context)
            fix = f
            api.nearby(f.lat, f.lon).objects()
                .map { o ->
                    val shots = o.getJSONArray("embeddings")
                    Candidate(
                        o.getString("id"),
                        shots.toEmbeddings(),
                        o.optString("kind").ifEmpty { if (o.optBoolean("tattoo")) TATTOO else "sticker" },
                        o.optString("name").ifEmpty { null },
                        o.optDouble("minSimilarity", MIN_SIMILARITY.toDouble()).toFloat(),
                        o.getBoolean("own"), o.getBoolean("caught"),
                    )
                }
        }.onSuccess { candidates = it }.onFailure { error = it.message }
    }

    Box(Modifier.fillMaxSize()) {
        CameraView(Modifier.fillMaxSize(), snapshot) { frame ->
            val list = candidates ?: return@CameraView
            if (busy || result != null) return@CameraView
            val crops = embedder.embedCrops(frame)
            // Každá samolepka má vlastní hranici (tetování a předměty mírnější), vybírá se ta, která ji
            // přesahuje nejvíc; u každé se bere nejlepší z výřezů snímku.
            val best = list.map { c -> c to bestMatch(crops, c.embeddings) }
                .maxByOrNull { (c, match) -> match.first - c.minSimilarity }
            similarity = best?.second?.first ?: 0f
            bestMatch = best?.first
            val passes = best != null && best.second.first >= best.first.minSimilarity
            // Vlastní nebo už ulovenou rybku jen ukáže, na server ji neposílá.
            streak = if (passes && best!!.first.catchable) streak + 1 else 0
            val f = fix
            if (best != null && f != null && streak >= MATCH_STREAK) {
                busy = true
                streak = 0
                scope.launch {
                    runCatching {
                        val photo = snapshot.take()?.let { withContext(Dispatchers.Default) { it.jpegBase64() } }
                        api.scan(best.first.id, f, best.second.second, photo)
                    }
                        .onSuccess { result = it }
                        .onFailure { error = it.message }
                    busy = false
                }
            }
        }
        val passing = bestMatch?.let { similarity >= it.minSimilarity } == true
        AimSquare(if (passing) Amber else Color.White)

        Column(Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth()) {
            val res = result
            when {
                res != null -> Panel { CatchResult(res) { result = null; reload++ } }
                error != null -> Panel {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { reload++ }) { Text("Zkusit znovu") }
                }
                candidates == null -> Panel {
                    Text("Zjišťuju polohu a rybky v okolí…")
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                candidates!!.none { it.catchable } && bestMatch == null -> Panel {
                    Text("Do 300 m tu není žádná rybka ani tetování k ulovení.")
                    TextButton(onClick = { reload++ }) { Text("Obnovit") }
                }
                else -> Panel {
                    Text("Zaměř samolepku do čtverce", fontWeight = FontWeight.Bold)
                    val catchable = candidates!!.filter { it.catchable }
                    val portable = catchable.count { it.portable }
                    Text(
                        "K ulovení: ${catchable.size - portable} v okolí" +
                            if (portable > 0) " · tetování a předmětů: $portable" else "",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    val match = bestMatch
                    if (match != null && !match.catchable && similarity >= match.minSimilarity) {
                        Text(
                            if (match.own) "Tohle je tvoje rybka (${match.label.substringBefore(" (")}) – vlastní ulovit nejde."
                            else "Tuhle rybku už máš v akváriu.",
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    match?.let { Text("Nejpodobnější: ${it.label}", fontSize = 13.sp) }
                    SimilarityBar(similarity, bestMatch?.minSimilarity ?: MIN_SIMILARITY)
                }
            }
        }
    }
}

@Composable
private fun CatchResult(res: JSONObject, onContinue: () -> Unit) {
    Text(res.getString("message"), fontSize = 18.sp, fontWeight = FontWeight.Bold)
    if (res.optString("result") == "caught") {
        Text(
            "+${res.optString("pointsText").ifEmpty { formatPoints(res.getDouble("points")) }} b.",
            color = MaterialTheme.colorScheme.primary, fontSize = 18.sp,
        )
        val level = res.getJSONObject("level")
        if (res.optBoolean("levelUp")) {
            Text(
                "Nová úroveň ${level.getInt("level")}: ${level.getString("title")}!",
                color = Amber, fontWeight = FontWeight.Bold, fontSize = 18.sp,
            )
        }
        res.getJSONArray("newAchievements").objects().forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Icon(Icons.Outlined.EmojiEvents, null, tint = Amber)
                Spacer(Modifier.width(8.dp))
                Text("Achievement: ${a.getString("name")}", fontWeight = FontWeight.Bold)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Button(onClick = onContinue, Modifier.fillMaxWidth()) { Text("Lovit dál") }
}

@Composable
fun AimSquare(color: Color) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxWidth(AIM_FRACTION).aspectRatio(1f)
                .border(3.dp, color, RoundedCornerShape(24.dp)),
        )
    }
}

@Composable
private fun SimilarityBar(value: Float, threshold: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(
            progress = { value.coerceIn(0f, 1f) },
            modifier = Modifier.weight(1f).height(8.dp).clip(CircleShape),
            color = if (value >= threshold) Amber else MaterialTheme.colorScheme.primary,
        )
        Text("Shoda ${(value * 100).toInt()} %", fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
    }
}

// --- společné ------------------------------------------------------------

/** Zaoblený panel přes kameru nebo mapu. */
@Composable
fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) { Column(Modifier.padding(16.dp), content = content) }
}

@Composable
fun Centered(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content,
    )
}
