package cz.rybky

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File

/** Turistická mapa z Mapy.com (podklady ve 2× rozlišení kvůli ostrosti). */
private val MAPY_TILES = object : OnlineTileSourceBase(
    "MapyCom", 0, 19, 256, "", arrayOf("https://api.mapy.com/v1/maptiles/outdoor/256@2x/"),
) {
    override fun getTileURLString(index: Long) =
        "$baseUrl${MapTileIndex.getZoom(index)}/${MapTileIndex.getX(index)}/${MapTileIndex.getY(index)}" +
            "?apikey=${BuildConfig.MAPY_API_KEY}"
}

/** Mapa s kruhy, ve kterých jsou samolepky (server posílá jen posunutý střed kruhu, ne přesné místo). */
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
    val circles = remember { mutableListOf<Polygon>() }

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
                mapView.controller.setZoom(16.0)
                mapView.controller.setCenter(GeoPoint(f.lat, f.lon))
            }
            fix = f
            api.map(f.lat, f.lon).objects()
        }.onSuccess { items = it }.onFailure { error = it.message }
    }
    LaunchedEffect(items) {
        mapView.overlays.removeAll(circles)
        circles.clear()
        items.orEmpty().forEach { s ->
            val color = when {
                s.getBoolean("own") -> ownColor
                s.getBoolean("caught") -> Color.Gray
                else -> Amber
            }
            circles += Polygon(mapView).apply {
                points = Polygon.pointsAsCircle(GeoPoint(s.getDouble("lat"), s.getDouble("lon")), s.getDouble("radius"))
                fillPaint.color = color.copy(alpha = 0.25f).toArgb()
                outlinePaint.color = color.toArgb()
                outlinePaint.strokeWidth = 5f
                setOnClickListener { _, _, _ -> selected = s; true }
            }
        }
        // Kruhy pod modrou tečkou polohy.
        mapView.overlays.addAll(0, circles)
        mapView.invalidate()
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
                fix?.let { mapView.controller.animateTo(GeoPoint(it.lat, it.lon)) }
            }) { Icon(Icons.Outlined.MyLocation, "Moje poloha") }
            SmallFloatingActionButton(onClick = { zoomToAll(mapView, items.orEmpty(), fix) }) {
                Icon(Icons.Outlined.ZoomOutMap, "Všechny rybky")
            }
            SmallFloatingActionButton(onClick = { reload++ }) { Icon(Icons.Outlined.Refresh, "Obnovit") }
        }

        Column(Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth()) {
            when {
                BuildConfig.MAPY_API_KEY.isEmpty() -> Panel {
                    Text("Chybí API klíč Mapy.com (mapy.apikey v local.properties).")
                }
                error != null -> Panel { Text(error!!, color = MaterialTheme.colorScheme.error) }
                items == null -> Panel {
                    Text("Hledám rybky v okolí…")
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                selected != null -> StickerCard(selected!!) { selected = null }
                items!!.isEmpty() -> Panel { Text("Zatím nikde žádná rybka. Nalep první!") }
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
        when {
            s.getBoolean("own") -> Text("Tvoje samolepka", color = MaterialTheme.colorScheme.primary)
            s.getBoolean("caught") -> Text("Už ji máš v akváriu", color = MaterialTheme.colorScheme.primary)
            else -> Text("Samolepka je někde v kruhu", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
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

/** Oddálí mapu tak, aby byly vidět všechny samolepky (a moje poloha). */
private fun zoomToAll(mapView: MapView, items: List<JSONObject>, fix: Fix?) {
    val points = items.map { GeoPoint(it.getDouble("lat"), it.getDouble("lon")) } +
        listOfNotNull(fix?.let { GeoPoint(it.lat, it.lon) })
    when (points.size) {
        0 -> return
        1 -> mapView.controller.animateTo(points[0], 15.0, 600L)
        else -> mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(points).increaseByScale(1.4f), true, 80)
    }
}

private fun formatDistance(meters: Int) = when {
    meters < 1000 -> "$meters m"
    meters < 10_000 -> String.format(java.util.Locale.forLanguageTag("cs"), "%.1f km", meters / 1000.0)
    else -> "%,d km".format(java.util.Locale.forLanguageTag("cs"), meters / 1000)
}

fun compass(bearing: Int) =
    listOf("sever", "severovýchod", "východ", "jihovýchod", "jih", "jihozápad", "západ", "severozápad")[
        ((bearing + 22) % 360) / 45
    ]
