package cz.rybky

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.sqrt

/** Část obrazu uprostřed, ve které má být samolepka (zaměřovací čtverec v náhledu). */
const val AIM_FRACTION = 0.65f

/**
 * Výřezy ze středu snímku, ze kterých se počítají otisky. Víc velikostí najednou → samolepka se pozná
 * zblízka i z dálky, nemusí přesně vyplnit zaměřovací čtverec.
 */
val CROP_FRACTIONS = listOf(0.45f, AIM_FRACTION, 0.9f)

/** Udělá z obrázku „otisk“ – vektor, který je podobný pro podobné obrázky. */
class Embedder(context: Context) : AutoCloseable {
    private val embedder = ImageEmbedder.createFromOptions(
        context,
        ImageEmbedder.ImageEmbedderOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("mobilenet_v3_small.tflite").build())
            .setRunningMode(RunningMode.IMAGE)
            .setL2Normalize(true)
            .build(),
    )

    @Synchronized // kamera i galerie můžou chtít otisk zároveň
    fun embed(bitmap: Bitmap): FloatArray =
        embedder.embed(BitmapImageBuilder(bitmap).build())
            .embeddingResult().embeddings()[0].floatEmbedding()

    /** Otisky všech výřezů [CROP_FRACTIONS] čtvercového snímku. */
    fun embedCrops(square: Bitmap): List<FloatArray> = CROP_FRACTIONS.map { embed(centerCrop(square, it)) }

    override fun close() = embedder.close()
}

fun cosine(a: FloatArray, b: FloatArray): Float {
    var dot = 0f; var na = 0f; var nb = 0f
    for (i in a.indices) {
        dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]
    }
    return if (na > 0 && nb > 0) dot / (sqrt(na) * sqrt(nb)) else 0f
}

/** Nejlepší shoda mezi otisky výřezů snímku a otisky samolepky: (shoda, otisk výřezu, který ji dal). */
fun bestMatch(crops: List<FloatArray>, shots: List<FloatArray>): Pair<Float, FloatArray> =
    crops.map { c -> shots.maxOf { cosine(c, it) } to c }.maxBy { it.first }

/** Čtverec ze středu obrázku o velikosti [fraction] kratší strany. */
fun centerCrop(bitmap: Bitmap, fraction: Float = 1f): Bitmap {
    val side = (minOf(bitmap.width, bitmap.height) * fraction).toInt()
    return Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
}

/** Fotka pro server: čtverec ze středu, nejvýš [maxSide] px, JPEG v base64. */
fun Bitmap.jpegBase64(maxSide: Int = 720): String {
    val square = centerCrop(this)
    val scaled = if (square.width > maxSide) Bitmap.createScaledBitmap(square, maxSide, maxSide, true) else square
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}

/** Umožní zvenku vzít snímek toho, co je právě v náhledu (v plném rozlišení displeje). Jen z UI vlákna. */
class CameraSnapshot {
    internal var view: PreviewView? = null
    fun take(): Bitmap? = view?.bitmap
}

/**
 * Živý náhled zadní kamery. [onFrame] dostává zhruba 4× za vteřinu čtverec ze středu snímku
 * (už otočený správně) – volá se na pozadí, ne v UI vlákně.
 */
@Composable
fun CameraView(modifier: Modifier = Modifier, snapshot: CameraSnapshot? = null, onFrame: (Bitmap) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val frameCallback = rememberUpdatedState(onFrame)
    val previewView = remember { PreviewView(context) }
    snapshot?.view = previewView
    val executor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var lastFrame = 0L
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(executor) { image ->
                image.use {
                    val now = System.currentTimeMillis()
                    if (now - lastFrame < 250) return@use
                    lastFrame = now
                    frameCallback.value(squareFrame(it.toBitmap(), it.imageInfo.rotationDegrees))
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            if (providerFuture.isDone) providerFuture.get().unbindAll()
        }
    }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/** Čtverec ze středu snímku, otočený tak, jak ho vidí uživatel. */
private fun squareFrame(frame: Bitmap, rotation: Int): Bitmap {
    val side = minOf(frame.width, frame.height)
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    return Bitmap.createBitmap(
        frame, (frame.width - side) / 2, (frame.height - side) / 2, side, side, matrix, true,
    )
}

/** Fotka z galerie jako čtverec ze středu, zmenšená – stejný tvar, jaký dává kamera. */
fun loadPhoto(context: Context, uri: Uri): Bitmap {
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // MediaPipe potřebuje čitelné pixely
        val longest = maxOf(info.size.width, info.size.height)
        if (longest > 1600) decoder.setTargetSampleSize(longest / 1600)
    }
    val side = minOf(bitmap.width, bitmap.height)
    return Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
}

/**
 * Výchozí vzory rybky pro galerii na serveru: obrázek ryby v různých barvách (samolepka na papíře,
 * na zdi, tetování na kůži), velikostech a natočeních.
 */
fun referenceImages(context: Context): List<Bitmap> {
    val fish = BitmapFactory.decodeResource(context.resources, R.drawable.ryba)
    val looks = listOf( // pozadí to barva ryby
        0xFFFFFFFF to 0xFF111111, 0xFFFFFFFF to 0xFF6B7280, 0xFF18450B to 0xFFEBEBEB,
        0xFFE0B48C to 0xFF1F2937, 0xFF8D5524 to 0xFF0B0B0B, 0xFFD1D5DB to 0xFF111111,
    )
    val size = 256
    return looks.flatMap { (background, ink) ->
        listOf(0.55f, 0.85f).flatMap { scale ->
            listOf(-20f, 0f, 20f).map { angle ->
                Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { out ->
                    val canvas = Canvas(out)
                    canvas.drawColor(background.toInt())
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                        colorFilter = PorterDuffColorFilter(ink.toInt(), PorterDuff.Mode.SRC_IN)
                    }
                    val w = size * scale
                    val h = w * fish.height / fish.width
                    canvas.rotate(angle, size / 2f, size / 2f)
                    canvas.drawBitmap(fish, null, RectF((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2), paint)
                }
            }
        }
    }
}

/** „50.0755, 14.4378“ i „50.0755N 14.4378E“ (formát z Mapy.com). */
fun parseCoords(text: String): Fix? {
    val m = Regex("""(-?\d+(?:[.]\d+)?)\s*[NS]?\s*[,; ]\s*(-?\d+(?:[.]\d+)?)\s*[EW]?""").find(text.trim())
        ?: return null
    val lat = m.groupValues[1].toDouble()
    val lon = m.groupValues[2].toDouble()
    return if (lat in -90.0..90.0 && lon in -180.0..180.0) Fix(lat, lon, 0f) else null
}

@SuppressLint("MissingPermission") // oprávnění se žádá při startu aplikace
suspend fun currentFix(context: Context): Fix {
    val location = LocationServices.getFusedLocationProviderClient(context)
        .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
        ?: throw ApiException("Nepodařilo se zjistit polohu. Je zapnutá GPS?")
    return Fix(location.latitude, location.longitude, location.accuracy)
}
