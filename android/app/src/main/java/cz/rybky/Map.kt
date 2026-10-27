package cz.rybky

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.compose.foundation.background
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

/** Turistická mapa z Mapy.com (podklady ve 2× rozlišení kvůli ostrosti). */
private val MAPY_TILES = object : OnlineTileSourceBase(
    "MapyCom", 0, 19, 256, "", arrayOf("https://api.mapy.com/v1/maptiles/outdoor/256@2x/"),
) {
    override fun getTileURLString(index: Long) =
        "$baseUrl${MapTileIndex.getZoom(index)}/${MapTileIndex.getX(index)}/${MapTileIndex.getY(index)}" +
            "?apikey=${BuildConfig.MAPY_API_KEY}"
}

/**
 * Mapa samolepek z celého světa. Každá má špendlík (vidět při jakémkoli přiblížení) a kruh, ve kterém
 * je schovaná – server posílá jen posunutý střed kruhu, ne přesné místo.
 */
@Composable
fun MapScreen(api: Api) {
    val context = LocalContext.current
    val ownColor = MaterialTheme.colorScheme.primary
    var items by remember { mutableStateOf<List<JSONObject>?>(null) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var fix by remember { mutableStateOf<Fix?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    val mapView = remember {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        }
        MapView(context).apply {
            setTileSource(MAPY_TILES)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 2.0
            maxZoomLevel = 19.0
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
        items.orEmpty().forEach { s ->
            val color = when {
                s.getBoolean("own") -> ownColor
                s.getBoolean("caught") -> Color.Gray
                else -> Amber
            }
            val center = GeoPoint(s.getDouble("lat"), s.getDouble("lon"))
            stickerOverlays += Polygon(mapView).apply {
                points = Polygon.pointsAsCircle(center, s.getDouble("radius"))
                fillPaint.color = color.copy(alpha = 0.22f).toArgb()
                outlinePaint.color = color.toArgb()
                outlinePaint.strokeWidth = 5f
                setOnClickListener { _, _, _ -> selected = s; true }
            }
            stickerOverlays += Marker(mapView).apply {
                position = center
                icon = BitmapDrawable(context.resources, stickerPin(context, color.toArgb()))
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setInfoWindow(null)
                setOnMarkerClickListener { _, _ -> selected = s; true }
            }
        }
        // Samolepky pod modrou tečkou polohy.
        mapView.overlays.addAll(0, stickerOverlays)
        mapView.invalidate()
    }

    fun flyTo(s: JSONObject) {
        selected = s
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
                    (if (s.isNull("fish")) "Neznámá ryba" else s.getString("fish")) +
                        " · ${s.optString("rarity")} · ${s.optString("envName")}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Zavřít") }
        }
        Text("~${formatDistance(s.getInt("distance"))} na ${compass(s.getInt("bearing"))} · nalepil(a) ${s.getString("author")}")
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
            else -> Text("Samolepka je někde v kruhu", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val zoom = minOf(zoomX, zoomY).coerceIn(2.0, 16.0)
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

/** Špendlík samolepky: barevná kapka s bílým okrajem, špičkou dolů. */
private fun stickerPin(context: Context, color: Int): Bitmap {
    val d = context.resources.displayMetrics.density
    val w = (28 * d).toInt()
    val h = (38 * d).toInt()
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        val r = w / 2f
        fun drop(inset: Float) = Path().apply {
            addCircle(r, r, r - inset, Path.Direction.CW)
            moveTo(r - (r - inset) * 0.7f, r + (r - inset) * 0.7f)
            lineTo(r, h - inset * 1.5f)
            lineTo(r + (r - inset) * 0.7f, r + (r - inset) * 0.7f)
            close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0x44000000
        canvas.drawPath(drop(0f), paint)
        paint.color = android.graphics.Color.WHITE
        canvas.drawPath(drop(1.5f * d), paint)
        paint.color = color
        canvas.drawPath(drop(4f * d), paint)
        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(r, r, r * 0.28f, paint)
    }
}

fun compass(bearing: Int) =
    listOf("sever", "severovýchod", "východ", "jihovýchod", "jih", "jihozápad", "západ", "severozápad")[
        ((bearing + 22) % 360) / 45
    ]
