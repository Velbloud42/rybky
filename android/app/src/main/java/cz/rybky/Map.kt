package cz.rybky

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material.icons.filled.Place
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.util.TileSystem
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.sinh
import kotlin.math.tan

fun setupOsmdroid(context: Context) {
    Configuration.getInstance().apply {
        userAgentValue = context.packageName
        osmdroidBasePath = File(context.cacheDir, "osmdroid")
        osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
    }
}

/**
 * Výběr přesného místa: mapa přes celou obrazovku a špendlík uprostřed. Mapou se posouvá tak,
 * aby špendlík seděl přesně tam, kde rybka visí.
 */
@Composable
fun PinPicker(start: GeoPoint, onCancel: () -> Unit, onPick: (GeoPoint) -> Unit) {
    val context = LocalContext.current
    val mapView = remember {
        setupOsmdroid(context)
        MapView(context).apply {
            setTileSource(MAPY_TILES)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            maxZoomLevel = 19.0
            isHorizontalMapRepetitionEnabled = false
            isVerticalMapRepetitionEnabled = false
            controller.setZoom(18.0)
            controller.setCenter(start)
        }
    }
    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose { mapView.onPause(); mapView.onDetach() }
    }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            // Špička špendlíku je přesně uprostřed mapy.
            Icon(
                Icons.Filled.Place, null, tint = Amber,
                modifier = Modifier.align(Alignment.Center).size(48.dp).offset(y = (-24).dp),
            )
            Text(
                "Posuň mapu, aby špendlík seděl přesně tam, kde rybka visí",
                color = Color.White, fontSize = 14.sp,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onCancel, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Black.copy(alpha = 0.6f), contentColor = Color.White),
                ) { Text("Zrušit") }
                Button(onClick = {
                    val c = mapView.mapCenter
                    onPick(GeoPoint(c.latitude, c.longitude))
                }, modifier = Modifier.weight(2f)) { Text("Tady visí") }
            }
            Text(
                "© Mapy.com", fontSize = 11.sp, color = Color(0xFF1B1B1B),
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(top = 80.dp, start = 12.dp)
                    .background(Color.White.copy(alpha = 0.8f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** Turistická mapa z Mapy.com (podklady ve 2× rozlišení kvůli ostrosti). */
val MAPY_TILES = object : OnlineTileSourceBase(
    "MapyCom", 0, 19, 256, "", arrayOf("https://api.mapy.com/v1/maptiles/outdoor/256@2x/"),
) {
    override fun getTileURLString(index: Long) =
        "$baseUrl${MapTileIndex.getZoom(index)}/${MapTileIndex.getX(index)}/${MapTileIndex.getY(index)}" +
            "?apikey=${BuildConfig.MAPY_API_KEY}"
}

/** Mapa samolepek z celého světa: špendlík na přesném místě, kde samolepka visí. */
@Composable
fun MapScreen(api: Api) {
    val context = LocalContext.current
    val ownColor = MaterialTheme.colorScheme.primary
    var items by remember { mutableStateOf<List<JSONObject>?>(null) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    // Víc rybek na jedné pietě v jednom bodě: po klepnutí jejich seznam.
    var group by remember { mutableStateOf<List<JSONObject>?>(null) }
    var fix by remember { mutableStateOf<Fix?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    val mapView = remember {
        setupOsmdroid(context)
        MapView(context).apply {
            setTileSource(MAPY_TILES)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            maxZoomLevel = 19.0
            // Jen jeden svět: neopakuje se do stran ani nahoru a dolů a dál než za jeho okraj posunout nejde.
            isHorizontalMapRepetitionEnabled = false
            isVerticalMapRepetitionEnabled = false
            setScrollableAreaLimitLatitude(MapView.getTileSystem().maxLatitude, MapView.getTileSystem().minLatitude, 0)
            setScrollableAreaLimitLongitude(MapView.getTileSystem().minLongitude, MapView.getTileSystem().maxLongitude, 0)
            // Nejmenší přiblížení: svět vyplní celou obrazovku (víc oddálit by ukázalo prázdno kolem).
            addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                val world = TileSystem.getTileSize() * context.resources.displayMetrics.density
                val min = log2(maxOf(view.width, view.height) / world.toDouble()).coerceAtLeast(0.0)
                if (min != minZoomLevel) {
                    minZoomLevel = min
                    if (zoomLevelDouble < min) controller.setZoom(min)
                }
            }
            // Než se zjistí poloha, ukazuje celou ČR.
            controller.setZoom(7.5)
            controller.setCenter(GeoPoint(49.8, 15.5))
        }
    }
    val dotColor = MaterialTheme.colorScheme.primary.toArgb()
    val myLocation = remember {
        MyLocationNewOverlay(GpsMyLocationProvider(context), mapView).apply {
            val dot = locationDot(context, dotColor)
            setPersonIcon(dot)
            setDirectionIcon(dot)
            setPersonAnchor(0.5f, 0.5f)
            setDirectionAnchor(0.5f, 0.5f)
        }
    }
    val stickerOverlays = remember { mutableListOf<Overlay>() }

    DisposableEffect(Unit) {
        mapView.overlays.add(myLocation)
        myLocation.enableMyLocation()
        mapView.onResume()
        onDispose {
            myLocation.disableMyLocation()
            mapView.onPause()
            mapView.onDetach()
        }
    }
    LaunchedEffect(reload) {
        error = null
        runCatching {
            val f = currentFix(context)
            if (fix == null) {
                mapView.controller.setZoom(15.0)
                mapView.controller.setCenter(GeoPoint(f.lat, f.lon))
            }
            fix = f
            api.map(f.lat, f.lon).objects()
        }.onSuccess { items = it }.onFailure { error = it.message }
    }
    LaunchedEffect(items) {
        mapView.overlays.removeAll(stickerOverlays)
        stickerOverlays.clear()
        // Rybky jedné piety („Boule 1.“, „Boule 2.“…) jsou jeden bod; ostatní každá svůj.
        val (pieta, single) = items.orEmpty().partition { it.optString("env") == PIETA }
        val spots = single.map { listOf(it) } + pieta.groupBy { pietaKey(it) }.values
        spots.forEach { members ->
            val s = members.first()
            val isPieta = s.optString("env") == PIETA
            val color = when {
                isPieta -> Gold
                s.getBoolean("own") -> ownColor
                s.getBoolean("caught") -> Color.Gray
                else -> Amber
            }
            val center = GeoPoint(s.getDouble("lat"), s.getDouble("lon"))
            val open = { if (members.size > 1) { group = members; selected = null } else { selected = s; group = null } }
            // Tolerance: okruh, ve kterém jde rybku ulovit.
            stickerOverlays += Polygon(mapView).apply {
                points = Polygon.pointsAsCircle(center, CATCH_RADIUS_M)
                fillPaint.color = color.copy(alpha = 0.14f).toArgb()
                outlinePaint.color = color.copy(alpha = 0.55f).toArgb()
                outlinePaint.strokeWidth = 2f
                setOnClickListener { _, _, _ -> open(); true }
            }
            stickerOverlays += Marker(mapView).apply {
                position = center
                icon = BitmapDrawable(context.resources, fishDot(context, color.toArgb(), if (isPieta) 24 else 14, members.size))
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setInfoWindow(null)
                setOnMarkerClickListener { _, _ -> open(); true }
            }
        }
        // Samolepky pod modrou tečkou polohy.
        mapView.overlays.addAll(0, stickerOverlays)
        mapView.invalidate()
    }

    fun flyTo(s: JSONObject) {
        selected = s
        group = null
        jumpTo(mapView, GeoPoint(s.getDouble("lat"), s.getDouble("lon")), 16.0)
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        // Mapy.com vyžadují viditelné logo/odkaz.
        Text(
            "© Mapy.com",
            fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1B1B1B),
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                .background(Color.White.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
                .clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://mapy.com/")))
                }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )

        Column(
            Modifier.align(Alignment.TopEnd).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SmallFloatingActionButton(onClick = {
                fix?.let { jumpTo(mapView, GeoPoint(it.lat, it.lon), 16.0) }
            }) { Icon(Icons.Outlined.MyLocation, "Moje poloha") }
            SmallFloatingActionButton(onClick = { zoomToAll(mapView, items.orEmpty(), fix) }) {
                Icon(Icons.Outlined.ZoomOutMap, "Všechny rybky")
            }
            SmallFloatingActionButton(onClick = { reload++ }) { Icon(Icons.Outlined.Refresh, "Obnovit") }
        }

        Column(Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth()) {
            val list = items
            when {
                BuildConfig.MAPY_API_KEY.isEmpty() -> Panel {
                    Text("Chybí API klíč Mapy.com (mapy.apikey v local.properties).")
                }
                error != null -> Panel { Text(error!!, color = MaterialTheme.colorScheme.error) }
                list == null -> Panel {
                    Text("Hledám rybky…")
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                selected != null -> StickerCard(selected!!) { selected = null }
                group != null -> PietaCard(group!!, onPick = { selected = it }) { group = null }
                list.isEmpty() -> Panel { Text("Zatím nikde žádná rybka. Nalep první!") }
                else -> NearestPanel(list, onPick = ::flyTo, onShowAll = { zoomToAll(mapView, list, fix) })
            }
        }
    }
}

/** Nejbližší rybky – klepnutím na mapě přeletí k samolepce. */
@Composable
private fun NearestPanel(items: List<JSONObject>, onPick: (JSONObject) -> Unit, onShowAll: () -> Unit) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Rybek na mapě: ${items.size}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onShowAll) { Text("Ukázat všechny") }
        }
        items.sortedBy { it.getInt("distance") }.take(3).forEach { s ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(s) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Place, null, Modifier.size(20.dp),
                    tint = when {
                        s.getBoolean("own") -> MaterialTheme.colorScheme.primary
                        s.getBoolean("caught") -> Color.Gray
                        else -> Amber
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    s.optString("name").ifEmpty { "Samolepka" },
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Text(formatDistance(s.getInt("distance")), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Víc rybek na jedné pietě: kolik jich tu je a jejich seznam. */
@Composable
private fun PietaCard(members: List<JSONObject>, onPick: (JSONObject) -> Unit, onClose: () -> Unit) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Pieta", color = Gold, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    "Na tomto místě ${if (members.size in 2..4) "jsou" else "je"} ${members.size} " +
                        if (members.size in 2..4) "ryby" else "ryb",
                    fontSize = 20.sp, fontWeight = FontWeight.Bold,
                )
            }
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Zavřít") }
        }
        members.sortedBy { it.optString("name") }.forEach { s ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(s) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(s.optString("name"), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    when {
                        s.getBoolean("own") -> "tvoje"
                        s.getBoolean("caught") -> "ulovená"
                        else -> "k ulovení"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                )
            }
        }
        Text("Každá je za $PIETA_POINTS bodů.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StickerCard(s: JSONObject, onClose: () -> Unit) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    s.optString("name").ifEmpty { "Samolepka" },
                    fontSize = 20.sp, fontWeight = FontWeight.Bold,
                )
                Text(
                    (if (s.isNull("fish")) "Neznámá ryba" else listOfNotNull(s.str("fishName"), s.getString("fish")).joinToString(" · ")) +
                        " · ${s.optString("rarity")} · ${s.optString("envName")}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Zavřít") }
        }
        Text("${formatDistance(s.getInt("distance"))} na ${compass(s.getInt("bearing"))} · nalepil(a) ${s.getString("author")}")
        if (!s.isNull("hint")) Text("„${s.getString("hint")}“", modifier = Modifier.padding(top = 4.dp))
        val finders = s.optJSONArray("finders")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        Text(
            if (finders.isEmpty()) "Zatím ji nikdo neulovil" else "Ulovili: ${finders.joinToString(", ")}",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        when {
            s.getBoolean("own") -> Text("Tvoje samolepka", color = MaterialTheme.colorScheme.primary)
            s.getBoolean("caught") -> Text("Už ji máš v akváriu", color = MaterialTheme.colorScheme.primary)
            else -> Text("Visí přesně u špendlíku – jdi tam a ulov ji", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Oddálí mapu tak, aby byly vidět všechny samolepky i moje poloha. Výřez a přiblížení se počítají
 * ručně v Mercatorově projekci (zoomToBoundingBox z osmdroidu skákal na nesmyslná místa).
 */
private fun zoomToAll(mapView: MapView, items: List<JSONObject>, fix: Fix?) {
    val points = items.map { GeoPoint(it.getDouble("lat"), it.getDouble("lon")) } +
        listOfNotNull(fix?.let { GeoPoint(it.lat, it.lon) })
    if (points.isEmpty()) return
    if (points.size == 1) {
        jumpTo(mapView, points[0], 15.0)
        return
    }
    fun mercX(lon: Double) = (lon + 180) / 360
    fun mercY(lat: Double) = (1 - ln(tan(Math.toRadians(lat)) + 1 / cos(Math.toRadians(lat))) / PI) / 2
    val xs = points.map { mercX(it.longitude) }
    val ys = points.map { mercY(it.latitude) }
    val (west, east) = xs.min() to xs.max()
    val (north, south) = ys.min() to ys.max()
    val tile = TileSystem.getTileSize().toDouble()
    // 70 % šířky a 55 % výšky – zbytek zabírají tlačítka a panel dole.
    val zoomX = log2(mapView.width * 0.7 / (tile * (east - west).coerceAtLeast(1e-9)))
    val zoomY = log2(mapView.height * 0.55 / (tile * (south - north).coerceAtLeast(1e-9)))
    val zoom = minOf(zoomX, zoomY).coerceIn(mapView.minZoomLevel, 16.0)
    val centerY = (north + south) / 2
    val center = GeoPoint(Math.toDegrees(atan(sinh(PI * (1 - 2 * centerY)))), (west + east) / 2 * 360 - 180)
    jumpTo(mapView, center, zoom)
}

/**
 * Přesun bez animace: animateTo se změnou přiblížení v osmdroidu dojede na špatné místo
 * (při velké změně třeba do jiného světadílu).
 */
private fun jumpTo(mapView: MapView, point: GeoPoint, zoom: Double) {
    mapView.controller.setZoom(zoom)
    mapView.controller.setCenter(point)
}

private fun formatDistance(meters: Int) = when {
    meters < 1000 -> "$meters m"
    meters < 10_000 -> String.format(java.util.Locale.forLanguageTag("cs"), "%.1f km", meters / 1000.0)
    else -> "%,d km".format(java.util.Locale.forLanguageTag("cs"), meters / 1000)
}

/** Puntík moje poloha: barevný kruh s bílým okrajem. */
private fun locationDot(context: Context, color: Int): Bitmap {
    val density = context.resources.displayMetrics.density
    val size = (22 * density).toInt()
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = size / 2f
        paint.color = 0x55000000
        canvas.drawCircle(r, r, r, paint)
        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(r, r, r - 1 * density, paint)
        paint.color = color
        canvas.drawCircle(r, r, r - 4 * density, paint)
    }
}

/** Tečka rybky: barevný kruh s bílým okrajem; u bodu s víc rybkami (pieta) jejich počet uprostřed. */
private fun fishDot(context: Context, color: Int, sizeDp: Int, count: Int): Bitmap {
    val d = context.resources.displayMetrics.density
    val size = (sizeDp * d).toInt()
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = size / 2f
        paint.color = 0x44000000
        canvas.drawCircle(r, r, r, paint)
        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(r, r, r - 1 * d, paint)
        paint.color = color
        canvas.drawCircle(r, r, r - 3 * d, paint)
        if (count > 1) {
            paint.color = android.graphics.Color.BLACK
            paint.textSize = size * 0.5f
            paint.textAlign = Paint.Align.CENTER
            paint.isFakeBoldText = true
            canvas.drawText("$count", r, r - (paint.descent() + paint.ascent()) / 2, paint)
        }
    }
}

/** Pietní rybky jedné piety mají stejný název až na pořadové číslo: „Boule 1.“, „Boule 2.“ → „boule“. */
private fun pietaKey(s: JSONObject) = s.optString("name").replace(Regex("""\s*\d+\.?\s*$"""), "").trim().lowercase()

/** Pietní místa jsou na mapě zlatá. */
private val Gold = Color(0xFFD4A72C)

/** Okruh, ve kterém jde rybku ulovit (MAX_DISTANCE_M na serveru). */
private const val CATCH_RADIUS_M = 50.0

fun compass(bearing: Int) =
    listOf("sever", "severovýchod", "východ", "jihovýchod", "jih", "jihozápad", "západ", "severozápad")[
        ((bearing + 22) % 360) / 45
    ]
