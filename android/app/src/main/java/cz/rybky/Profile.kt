package cz.rybky

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.launch
import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Diamond
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Phishing
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.SetMeal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

fun rarityColor(rarity: String) = when (rarity) {
    "nevšední" -> Color(0xFF22C55E)
    "vzácná" -> Color(0xFF3B82F6)
    "legendární" -> Amber
    else -> Color(0xFF94A3B8)
}

private val METRIC_ICONS = mapOf(
    "catches" to Icons.Outlined.Phishing,
    "species" to Icons.Outlined.MenuBook,
    "rare" to Icons.Outlined.Diamond,
    "legendary" to Icons.Outlined.AutoAwesome,
    "envs" to Icons.Outlined.Explore,
    "stickers" to Icons.Outlined.AddLocationAlt,
    "best_sticker" to Icons.Outlined.Favorite,
    "tattoos" to Icons.Outlined.Brush,
    "objects" to Icons.Outlined.LocalCafe,
)

/** Profil: úroveň a záložky Akvárium / Atlas ryb / Úspěchy / Moje samolepky. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(api: Api, onLogout: () -> Unit) {
    var me by remember { mutableStateOf<JSONObject?>(null) }
    var atlas by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        runCatching { api.me() to api.fish().objects() }
            .onSuccess { (m, f) -> me = m; atlas = f }
            .onFailure { error = it.message }
    }
    val m = me
    if (m == null) {
        Centered { Text(error ?: "Načítám…", color = if (error != null) MaterialTheme.colorScheme.error else Color.Unspecified) }
        return
    }

    Column(Modifier.fillMaxSize()) {
        LevelHeader(m, onLogout)
        if (!m.optBoolean("hasPassword", true)) SetPasswordCard(api) { me = JSONObject(m.toString()).put("hasPassword", true) }
        val achievements = m.getJSONArray("achievements").objects()
        PrimaryTabRow(selectedTabIndex = tab) {
            listOf(
                "Akvárium",
                "Atlas",
                "Úspěchy ${achievements.count { it.getBoolean("unlocked") }}/${achievements.size}",
                "Samolepky",
            ).forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label, maxLines = 1, fontSize = 13.sp) })
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            when (tab) {
                0 -> {
                    val fish = m.getJSONArray("aquarium").objects()
                    if (fish.isEmpty()) item { Text("Akvárium je zatím prázdné. Jdi na lov!") }
                    items(fish) { f ->
                        ProfileItem(
                            icon = {
                                if (f.optBoolean("catchImage")) {
                                    RemoteImage(api, "/api/images/catch/${f.getInt("catchId")}", Modifier.size(56.dp))
                                } else Icon(Icons.Outlined.SetMeal, null, tint = rarityColor(f.getString("rarity")))
                            },
                            title = f.getString("fish"),
                            subtitle = "${f.getString("rarity")} · „${f.optString("name")}“ od ${f.getString("author")}",
                        )
                    }
                }
                1 -> {
                    item {
                        Text(
                            "Uloveno ${atlas.count { it.getInt("caught") > 0 }} z ${atlas.size} druhů",
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    items(atlas) { f ->
                        val caught = f.getInt("caught")
                        ProfileItem(
                            icon = {
                                Icon(
                                    Icons.Outlined.SetMeal, null,
                                    tint = if (caught > 0) rarityColor(f.getString("rarity")) else MaterialTheme.colorScheme.outlineVariant,
                                )
                            },
                            title = f.getString("name"),
                            subtitle = "${f.getString("rarity")} · ${f.getString("envName")} · ${formatPoints(f.getDouble("points"))} b." +
                                " · ve hře ${f.getInt("inWorld")}×",
                            trailing = if (caught > 0) "${caught}×" else "—",
                            dimmed = caught == 0,
                        )
                    }
                }
                2 -> items(achievements) { a -> AchievementRow(a) }
                3 -> {
                    val stickers = m.getJSONArray("stickers").objects()
                    if (stickers.isEmpty()) item { Text("Ještě jsi nic nenalepil(a).") }
                    items(stickers) { s ->
                        ProfileItem(
                            icon = {
                                if (s.optBoolean("image")) {
                                    RemoteImage(api, "/api/images/sticker/${s.getString("id")}", Modifier.size(56.dp))
                                } else Icon(ENV_ICONS[s.optString("env")] ?: Icons.Outlined.Place, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            title = s.optString("name").ifEmpty { s.getString("fish") },
                            subtitle = "${s.getString("fish")} · ${s.optString("envName")}" +
                                if (!s.isNull("hint")) " · „${s.getString("hint")}“" else "",
                            trailing = "${s.getInt("finds")}× ulovena",
                        )
                    }
                }
            }
        }
    }
}

/** Fotky ze serveru si pamatuje, ať se při přepínání záložek nestahují znovu. */
private val imageCache = LruCache<String, Bitmap>(40)

/** Fotka ze serveru (úlovek, vlastní samolepka) se zaoblenými rohy. */
@Composable
fun RemoteImage(api: Api, path: String, modifier: Modifier = Modifier) {
    val bitmap by produceState(imageCache.get(path), path) {
        if (value == null) runCatching {
            withContext(Dispatchers.Default) { api.image(path).let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
        }.getOrNull()?.let { imageCache.put(path, it); value = it }
    }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable
private fun LevelHeader(m: JSONObject, onLogout: () -> Unit) {
    val level = m.getJSONObject("level")
    val start = level.getInt("levelStart")
    val next = level.getInt("nextLevel")
    val points = level.getDouble("points")
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${level.getInt("level")}",
                fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(m.getString("nick"), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(level.getString("title"), color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { ((points - start) / (next - start)).toFloat() },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
            )
            Text(
                "${formatPoints(points)} b. · do další úrovně ${formatPoints(next - points)} b.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onLogout) { Icon(Icons.AutoMirrored.Outlined.Logout, "Odhlásit") }
    }
}

/** Staré účty heslo nemají – bez něj se po přeinstalaci nebo na jiném telefonu nepřihlásí. */
@Composable
private fun SetPasswordCard(api: Api, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Nastav si heslo", fontWeight = FontWeight.Bold)
            Text("Jinak se po přeinstalaci nebo na jiném telefonu ke svému účtu nedostaneš.", fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    password, { password = it }, label = { Text("Heslo") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    scope.launch {
                        runCatching { api.setPassword(password) }.onSuccess { onSaved() }.onFailure { error = it.message }
                    }
                }, enabled = password.length >= 4) { Text("Uložit") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun AchievementRow(a: JSONObject) {
    val unlocked = a.getBoolean("unlocked")
    ListItem(
        headlineContent = { Text(a.getString("name"), fontWeight = FontWeight.Bold) },
        supportingContent = {
            Column {
                Text(a.getString("description"))
                if (!unlocked) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { a.getInt("progress").toFloat() / a.getInt("target") },
                            modifier = Modifier.weight(1f).height(6.dp).clip(CircleShape),
                        )
                        Text("  ${a.getInt("progress")}/${a.getInt("target")}", fontSize = 12.sp)
                    }
                }
            }
        },
        leadingContent = {
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .background(if (unlocked) Amber.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    METRIC_ICONS[a.getString("metric")] ?: Icons.Outlined.EmojiEvents, null,
                    tint = if (unlocked) Amber else MaterialTheme.colorScheme.outline,
                )
            }
        },
        trailingContent = if (unlocked) {
            { Icon(Icons.Outlined.CheckCircle, "Splněno", tint = Amber) }
        } else null,
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.clip(RoundedCornerShape(16.dp)),
    )
}

@Composable
private fun ProfileItem(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    trailing: String? = null,
    dimmed: Boolean = false,
) {
    val alpha = if (dimmed) 0.55f else 1f
    ListItem(
        headlineContent = {
            Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
        },
        supportingContent = { Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)) },
        leadingContent = icon,
        trailingContent = trailing?.let { { Text(it, fontWeight = FontWeight.Bold) } },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.clip(RoundedCornerShape(16.dp)),
    )
}
