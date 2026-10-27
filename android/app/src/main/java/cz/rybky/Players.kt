package cz.rybky

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Diamond
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Handshake
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.MilitaryTech
import androidx.compose.material.icons.outlined.Phishing
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Profilovka se posílá jako čtverec o této straně. */
private const val AVATAR_SIZE = 320
private const val MOTTO_MAX = 60

private val METRIC_ICONS = mapOf(
    "catches" to Icons.Outlined.Phishing,
    "species" to Icons.Outlined.MenuBook,
    "rare" to Icons.Outlined.Diamond,
    "legendary" to Icons.Outlined.AutoAwesome,
    "envs" to Icons.Outlined.Explore,
    "far" to Icons.Outlined.Public,
    "stickers" to Icons.Outlined.AddLocationAlt,
    "best_sticker" to Icons.Outlined.Favorite,
    "fans" to Icons.Outlined.Groups,
    "tattoos" to Icons.Outlined.Brush,
    "objects" to Icons.Outlined.LocalCafe,
    "first_finds" to Icons.Outlined.Flag,
    "authors" to Icons.Outlined.Handshake,
    "night" to Icons.Outlined.Bedtime,
    "morning" to Icons.Outlined.WbTwilight,
    "days" to Icons.Outlined.CalendarMonth,
    "best_day" to Icons.Outlined.Bolt,
    "points" to Icons.Outlined.MilitaryTech,
    "avatar" to Icons.Outlined.Face,
)

fun achievementIcon(metric: String) = METRIC_ICONS[metric] ?: Icons.Outlined.EmojiEvents

private val SILVER = Color(0xFFC0C7D1)
private val BRONZE = Color(0xFFCD7F32)

/** Barvy koleček bez fotky – podle přezdívky, ať se hráči na první pohled liší. */
private val AVATAR_COLORS = listOf(
    Color(0xFF0F766E), Color(0xFF4338CA), Color(0xFFBE123C), Color(0xFFB45309), Color(0xFF7E22CE), Color(0xFF0369A1),
)

/** Profilovka v kruhu; bez fotky první písmeno přezdívky. */
@Composable
fun Avatar(api: Api, path: String?, nick: String, size: Dp) {
    if (path != null) {
        RemoteImage(api, path, Modifier.size(size), CircleShape)
    } else {
        Box(
            Modifier.size(size).clip(CircleShape).background(AVATAR_COLORS[Math.floorMod(nick.lowercase().hashCode(), AVATAR_COLORS.size)]),
            contentAlignment = Alignment.Center,
        ) {
            Text(nick.take(1).uppercase(), fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

/** Odznak: ikona úspěchu v kolečku v barvě vzácnosti. */
@Composable
fun BadgeIcon(metric: String, rarity: String, size: Dp = 22.dp, active: Boolean = true) {
    val color = if (active) rarityColor(rarity) else MaterialTheme.colorScheme.outline
    Box(
        Modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.18f)).border(1.5.dp, color, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(achievementIcon(metric), null, Modifier.size(size * 0.6f), tint = color) }
}

/** Přezdívka a vedle ní vystavené odznaky. */
@Composable
fun NickWithBadges(nick: String, badges: List<JSONObject>, fontSize: Int = 16, badgeSize: Dp = 20.dp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            nick, fontSize = fontSize.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        badges.forEach { b ->
            Spacer(Modifier.width(4.dp))
            BadgeIcon(b.getString("metric"), b.getString("rarity"), badgeSize)
        }
    }
}

/** Žebříček nejlepších 10 hráčů; když v nich nejsem, je pod nimi i moje pořadí. */
@Composable
fun LeaderboardScreen(api: Api) {
    var board by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(Unit) {
        runCatching { api.leaderboard() }.onSuccess { board = it }.onFailure { error = it.message }
    }
    val b = board
    if (b == null) {
        Centered { Text(error ?: "Načítám…", color = if (error != null) MaterialTheme.colorScheme.error else Color.Unspecified) }
        return
    }
    val top = b.getJSONArray("top").objects()
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Text("Žebříček", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(
                "Nejlepší rybáři podle bodů · hráčů celkem ${b.getInt("players")}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(4.dp))
        }
        items(top) { p -> PlayerRow(api, p) { selected = p } }
        b.optJSONObject("me")?.let { me ->
            item { Text("⋯", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.outline) }
            item { PlayerRow(api, me) { selected = me } }
        }
    }
    selected?.let { PlayerSheet(api, it) { selected = null } }
}

@Composable
private fun PlayerRow(api: Api, p: JSONObject, onClick: () -> Unit) {
    val level = p.getJSONObject("level")
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (p.optBoolean("me")) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            RankMark(p.getInt("rank"))
            Spacer(Modifier.width(10.dp))
            Avatar(api, p.str("avatar"), p.getString("nick"), 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                NickWithBadges(p.getString("nick"), p.getJSONArray("badges").objects())
                Text(
                    "Úroveň ${level.getInt("level")} · ${level.getString("title")}",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("${p.getString("pointsText")} b.", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

/** Pořadí; první tři mají medailovou barvu. */
@Composable
private fun RankMark(rank: Int) {
    val medal = when (rank) {
        1 -> Amber
        2 -> SILVER
        3 -> BRONZE
        else -> null
    }
    Box(
        Modifier.size(30.dp).clip(CircleShape).background(medal ?: Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "$rank", fontWeight = FontWeight.Bold,
            color = if (medal != null) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Veřejný profil hráče ze žebříčku. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSheet(api: Api, p: JSONObject, onDismiss: () -> Unit) {
    val level = p.getJSONObject("level")
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Avatar(api, p.str("avatar"), p.getString("nick"), 104.dp)
            Text(p.getString("nick"), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                "${p.getInt("rank")}. místo · úroveň ${level.getInt("level")} · ${level.getString("title")}",
                color = MaterialTheme.colorScheme.primary,
            )
            p.str("motto")?.let { Text("„$it“", fontStyle = FontStyle.Italic, textAlign = TextAlign.Center) }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Stat(p.getString("pointsText"), "bodů")
                Stat("${p.getInt("catches")}", "úlovků")
                Stat("${p.getInt("species")}", "druhů")
                Stat("${p.getInt("stickers")}", "nalepeno")
            }
            val badges = p.getJSONArray("badges").objects()
            if (badges.isEmpty()) {
                Text("Zatím nevystavuje žádné odznaky.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("Vystavené odznaky (${badges.size} z ${p.getInt("badgeCount")})", fontWeight = FontWeight.Bold)
                badges.forEach { b ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        BadgeIcon(b.getString("metric"), b.getString("rarity"), 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(b.getString("name"), fontWeight = FontWeight.Bold)
                            Text(b.getString("description"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(b.getString("rarity"), color = rarityColor(b.getString("rarity")), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Stat(value: String, label: String) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Úprava profilu: profilovka, přezdívka, motto, heslo; dole odhlášení. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileSheet(api: Api, me: JSONObject, onSaved: (JSONObject) -> Unit, onLogout: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var nick by remember { mutableStateOf(me.getString("nick")) }
    var motto by remember { mutableStateOf(me.str("motto").orEmpty()) }
    var password by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var removePhoto by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.Default) { loadPhoto(context, uri) } }
                .onSuccess { photo = it; removePhoto = false }
                .onFailure { error = "Fotku se nepodařilo načíst" }
        }
    }
    val choosePhoto = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val hasPhoto = photo != null || (me.str("avatar") != null && !removePhoto)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Upravit profil", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Box(Modifier.clickable(onClick = choosePhoto)) {
                val p = photo
                when {
                    p != null -> Image(
                        p.asImageBitmap(), null, Modifier.size(112.dp).clip(CircleShape), contentScale = ContentScale.Crop,
                    )
                    removePhoto -> Avatar(api, null, nick, 112.dp)
                    else -> Avatar(api, me.str("avatar"), nick, 112.dp)
                }
                Box(
                    Modifier.align(Alignment.BottomEnd).size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.PhotoCamera, "Změnit fotku", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimary) }
            }
            Row {
                TextButton(onClick = choosePhoto) { Text(if (hasPhoto) "Změnit fotku" else "Přidat fotku") }
                if (hasPhoto) TextButton(onClick = { photo = null; removePhoto = true }) { Text("Odebrat") }
            }
            OutlinedTextField(
                nick, { nick = it }, label = { Text("Přezdívka") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                motto, { if (it.length <= MOTTO_MAX) motto = it }, label = { Text("Motto") }, singleLine = true,
                placeholder = { Text("Co tě na lovu žene?") },
                supportingText = { Text("${motto.length}/$MOTTO_MAX") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                password, { password = it }, label = { Text("Nové heslo (nepovinné)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Odznaky vedle jména si vybereš v záložce Úspěchy.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        runCatching {
                            val changes = JSONObject()
                            if (nick.trim() != me.getString("nick")) changes.put("nick", nick.trim())
                            if (motto.trim() != me.str("motto").orEmpty()) changes.put("motto", motto.trim())
                            if (password.isNotEmpty()) changes.put("password", password)
                            photo?.let { changes.put("avatar", withContext(Dispatchers.Default) { it.jpegBase64(AVATAR_SIZE) }) }
                            if (removePhoto) changes.put("avatar", "")
                            api.updateProfile(changes)
                        }.onSuccess(onSaved).onFailure { error = it.message }
                        busy = false
                    }
                },
                enabled = !busy && nick.trim().length in 3..20 && (password.isEmpty() || password.length >= 4),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Ukládám…" else "Uložit") }
            OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Outlined.Logout, null)
                Spacer(Modifier.width(8.dp))
                Text("Odhlásit se")
            }
        }
    }
}
