package com.engabd.sendpin.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.engabd.sendpin.ui.theme.DefaultAccent
import com.engabd.sendpin.ui.theme.FallbackPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Pixmap
import org.jetbrains.skia.Surface
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The desktop twin of :app's `ui/design/AlbumPalette.kt` — the palette a cover
 * melts into: a bright [accent] plus ranked companion [swatches] for glows,
 * gradients and tinted controls.
 *
 * The colour science (CIELAB k-means extraction, OKLab lift, all constants and
 * thresholds) is copied **verbatim** from the original so the panel cannot drift
 * from the phone's look — only the bitmap loader differs (Skiko's own decoder in
 * place of Android's, since Coil 2's Android loader and `android.graphics.Bitmap`
 * do not exist here). The extraction is deterministic per cover, so a given
 * album always lights the room the same way.
 */
@Immutable
data class AlbumPalette(
    val accent: Color = DefaultAccent,
    val swatches: List<Color> = FallbackPalette,
) {
    /** Companion swatch [i], wrapping around. */
    fun swatch(i: Int): Color = swatches[((i % swatches.size) + swatches.size) % swatches.size]
}

val LocalPalette = compositionLocalOf { AlbumPalette() }
val LocalAccent = compositionLocalOf { DefaultAccent }

/** The accent, eased — the :app token the seek bar and tinted controls read. */
@Composable
fun rememberAccent(): Color {
    val target = LocalAccent.current
    val eased by animateColorAsState(target, tween(180), label = "accent")
    return eased
}

private const val SAMPLE = 64      // sample artwork to 64×64 before clustering (matches :app)
private const val CLUSTERS = 8
private const val KMEANS_ITERS = 16
private const val ACHROMATIC_C = 6f
private const val MIN_ACCENT_C = 12f
private const val ACCENT_C_FRACTION = 0.42f
private const val ACCENT_L_MIN = 0.55f
private const val ACCENT_L_MAX = 0.88f
private const val COMPANION_L_HEADROOM = 0.06f
private const val COMPANION_L_MIN = 0.38f
private const val MIN_ACCENT_C_FRACTION = 0.40f
private const val ACHROMATIC_OKLCH_C = 0.025f
private const val NEUTRAL_MAX_C = 0.02f
private const val VALUE_FLOOR = 0.15f
private const val HUE_MIN_SEP_DEG = 22f
private const val MAX_ACCENTS = 4
private const val MAX_NEUTRAL_SWATCHES = 1

private val WARM_WHITE = floatArrayOf(1.0f, 0.93f, 0.86f)
private val COOL_WHITE = floatArrayOf(0.86f, 0.92f, 1.0f)
private val PLAIN_WHITE = floatArrayOf(0.96f, 0.96f, 0.96f)

// ─── Colour space conversions (verbatim from :app) ──────────────────────────

private fun rgbToLab(r: Float, g: Float, b: Float): FloatArray {
    fun lin(c: Float) = if (c > 0.04045f) ((c + 0.055f) / 1.055f).let { it * it * it } else c / 12.92f
    val lr = lin(r); val lg = lin(g); val lb = lin(b)
    val x = (lr * 0.4124f + lg * 0.3576f + lb * 0.1805f) / 0.95047f
    val y = (lr * 0.2126f + lg * 0.7152f + lb * 0.0722f)
    val z = (lr * 0.0193f + lg * 0.1192f + lb * 0.9505f) / 1.08883f
    fun f(t: Float) = if (t > 0.008856f) cbrt(t) else 7.787f * t + 16f / 116f
    val fx = f(x); val fy = f(y); val fz = f(z)
    return floatArrayOf(116f * fy - 16f, 500f * (fx - fy), 200f * (fy - fz))
}

private fun rgbToHsv(r: Float, g: Float, b: Float): FloatArray {
    val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
    val d = mx - mn
    val v = mx
    val s = if (mx > 1e-6f) d / mx else 0f
    var h = 0f
    if (d != 0f) {
        h = when {
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
    }
    if (h < 0f) h += 360f
    return floatArrayOf(h, s, v)
}

private fun hsvToRgb(h: Float, s: Float, v: Float): FloatArray {
    val c = v * s
    val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
    val m = v - c
    val (r, g, b) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return floatArrayOf(r + m, g + m, b + m)
}

private fun hueDistance(a: Float, b: Float): Float {
    val d = kotlin.math.abs(a - b) % 360f
    return kotlin.math.min(d, 360f - d)
}

private fun srgbToLinear(c: Float): Float =
    if (c > 0.04045f) ((c + 0.055f) / 1.055f).pow(2.4f) else c / 12.92f

private fun linearToSrgb(c: Float): Float =
    if (c > 0.0031308f) 1.055f * c.pow(1f / 2.4f) - 0.055f else 12.92f * c

/** sRGB (0..1) → OKLab `[L, a, b]`. */
internal fun rgbToOklab(r: Float, g: Float, b: Float): FloatArray {
    val lr = srgbToLinear(r); val lg = srgbToLinear(g); val lb = srgbToLinear(b)
    val l = cbrt(0.4122214708f * lr + 0.5363325363f * lg + 0.0514459929f * lb)
    val m = cbrt(0.2119034982f * lr + 0.6806995451f * lg + 0.1073969566f * lb)
    val s = cbrt(0.0883024619f * lr + 0.2817188376f * lg + 0.6299787005f * lb)
    return floatArrayOf(
        0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
        1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
        0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s,
    )
}

/** OKLab → sRGB (0..1), **unclamped** — out-of-gamut input returns out-of-range channels. */
internal fun oklabToRgbRaw(lab: FloatArray): FloatArray {
    val lp = lab[0] + 0.3963377774f * lab[1] + 0.2158037573f * lab[2]
    val mp = lab[0] - 0.1055613458f * lab[1] - 0.0638541728f * lab[2]
    val sp = lab[0] - 0.0894841775f * lab[1] - 1.2914855480f * lab[2]
    val l = lp * lp * lp; val m = mp * mp * mp; val s = sp * sp * sp
    return floatArrayOf(
        linearToSrgb(4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s),
        linearToSrgb(-1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s),
        linearToSrgb(-0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s),
    )
}

/** OKLab `[L, a, b]` → OKLCh `[L, C, h°]`. */
internal fun oklabToOklch(lab: FloatArray): FloatArray {
    val c = sqrt(lab[1] * lab[1] + lab[2] * lab[2])
    var h = Math.toDegrees(atan2(lab[2].toDouble(), lab[1].toDouble())).toFloat()
    if (h < 0f) h += 360f
    return floatArrayOf(lab[0], c, h)
}

private fun oklchToOklab(l: Float, c: Float, hDeg: Float): FloatArray {
    val rad = Math.toRadians(hDeg.toDouble())
    return floatArrayOf(l, (c * cos(rad)).toFloat(), (c * sin(rad)).toFloat())
}

/** OKLCh → the closest sRGB colour **of the same lightness and hue** (chroma gives). */
internal fun oklchToRgbInGamut(l: Float, c: Float, hDeg: Float): FloatArray {
    fun inGamut(rgb: FloatArray) = rgb.all { it >= -1e-4f && it <= 1f + 1e-4f }
    val direct = oklabToRgbRaw(oklchToOklab(l, c, hDeg))
    if (inGamut(direct)) return direct.map { it.coerceIn(0f, 1f) }.toFloatArray()
    var lo = 0f
    var hi = c
    repeat(20) {
        val mid = (lo + hi) / 2f
        if (inGamut(oklabToRgbRaw(oklchToOklab(l, mid, hDeg)))) lo = mid else hi = mid
    }
    return oklabToRgbRaw(oklchToOklab(l, lo, hDeg)).map { it.coerceIn(0f, 1f) }.toFloatArray()
}

/** The most chroma sRGB can hold at this lightness and hue — the gamut boundary. */
internal fun maxChromaFor(l: Float, hDeg: Float): Float {
    var lo = 0f
    var hi = 0.4f
    repeat(20) {
        val mid = (lo + hi) / 2f
        val rgb = oklabToRgbRaw(oklchToOklab(l, mid, hDeg))
        if (rgb.all { it >= -1e-4f && it <= 1f + 1e-4f }) lo = mid else hi = mid
    }
    return lo
}

private fun oklchColor(l: Float, c: Float, hDeg: Float): Color {
    val rgb = oklchToRgbInGamut(l, c, hDeg)
    return Color(rgb[0], rgb[1], rgb[2])
}

// ─── K-means in CIELAB (verbatim from :app) ────────────────────────────────

private data class Cluster(
    val rgb: FloatArray,
    val hsv: FloatArray,
    val chroma: Float,
    val population: Float,
)

private fun chromaOf(lab: FloatArray): Float = hypot(lab[1], lab[2])

/**
 * The colour that stands for a cluster: the mean of its most chromatic quarter,
 * not the mean of all of it — averaging a highlight with its shadow arrives grey.
 */
private fun representative(members: List<FloatArray>, labs: List<FloatArray>): FloatArray {
    if (members.size <= 3) {
        return floatArrayOf(
            members.map { it[0] }.average().toFloat(),
            members.map { it[1] }.average().toFloat(),
            members.map { it[2] }.average().toFloat(),
        )
    }
    val cutoff = labs.map { chromaOf(it) }.sorted()[(labs.size * 3) / 4]
    val top = members.indices.filter { chromaOf(labs[it]) >= cutoff }
    val pick = if (top.isEmpty()) members.indices.toList() else top
    var r = 0f; var g = 0f; var b = 0f
    for (i in pick) { r += members[i][0]; g += members[i][1]; b += members[i][2] }
    val n = pick.size.toFloat()
    return floatArrayOf(r / n, g / n, b / n)
}

/** Deterministic k-means in CIELAB, seeded with k-means++ — distinct hues survive. */
private fun kmeansClusters(pixels: List<FloatArray>, k: Int, rng: kotlin.random.Random): List<Cluster> {
    val n = pixels.size
    if (n == 0) return emptyList()
    val kk = kotlin.math.min(k, n)

    val lab = pixels.map { rgbToLab(it[0], it[1], it[2]) }

    val centroids = ArrayList<FloatArray>(kk)
    centroids.add(lab[rng.nextInt(n)].copyOf())
    val best = FloatArray(n) { Float.MAX_VALUE }
    while (centroids.size < kk) {
        var total = 0.0
        val last = centroids.last()
        for (i in 0 until n) {
            val d = sqDist(lab[i], last)
            if (d < best[i]) best[i] = d
            total += best[i].toDouble()
        }
        if (total <= 0.0) break
        var target = rng.nextDouble() * total
        var chosen = n - 1
        for (i in 0 until n) {
            target -= best[i].toDouble()
            if (target <= 0.0) { chosen = i; break }
        }
        centroids.add(lab[chosen].copyOf())
    }

    val kFinal = centroids.size
    val labels = IntArray(n)
    for (iter in 0 until KMEANS_ITERS) {
        var moved = false
        for (i in 0 until n) {
            var bestJ = 0; var bestD = Float.MAX_VALUE
            for (j in 0 until kFinal) {
                val d = sqDist(lab[i], centroids[j])
                if (d < bestD) { bestD = d; bestJ = j }
            }
            if (labels[i] != bestJ) { labels[i] = bestJ; moved = true }
        }
        if (!moved && iter > 0) break
        for (j in 0 until kFinal) {
            var cnt = 0; var sumL = 0f; var sumA = 0f; var sumB = 0f
            for (i in 0 until n) {
                if (labels[i] == j) { cnt++; sumL += lab[i][0]; sumA += lab[i][1]; sumB += lab[i][2] }
            }
            if (cnt > 0) centroids[j] = floatArrayOf(sumL / cnt, sumA / cnt, sumB / cnt)
        }
    }

    val buckets = Array(kFinal) { mutableListOf<Int>() }
    for (i in 0 until n) buckets[labels[i]].add(i)
    val total = n.toFloat()
    return (0 until kFinal).mapNotNull { j ->
        val idx = buckets[j]
        if (idx.isEmpty()) return@mapNotNull null
        val rgb = representative(idx.map { pixels[it] }, idx.map { lab[it] })
        Cluster(
            rgb = rgb,
            hsv = rgbToHsv(rgb[0], rgb[1], rgb[2]),
            chroma = chromaOf(rgbToLab(rgb[0], rgb[1], rgb[2])),
            population = idx.size / total,
        )
    }
}

private fun sqDist(a: FloatArray, b: FloatArray): Float {
    val dl = a[0] - b[0]; val da = a[1] - b[1]; val db = a[2] - b[2]
    return dl * dl + da * da + db * db
}

/** A genuinely colourless cover, rendered as a white carrying its own faint cast. */
private fun tintedWhite(rgb: FloatArray, v: Float): FloatArray {
    val r = rgb[0]; val b = rgb[2]
    val tint = if (r - b > 0.02f) WARM_WHITE
        else if (b - r > 0.02f) COOL_WHITE
        else PLAIN_WHITE
    val vv = max(VALUE_FLOOR, kotlin.math.min(1f, v))
    return floatArrayOf(tint[0] * vv, tint[1] * vv, tint[2] * vv)
}

internal class Extraction(
    val lead: FloatArray,
    val swatches: List<FloatArray>,
    val populations: List<Float> = emptyList(),
    val achromatic: Boolean = false,
)

private fun lowColourFallback(pixels: List<FloatArray>): Extraction {
    val mean = floatArrayOf(
        pixels.map { it[0] }.average().toFloat(),
        pixels.map { it[1] }.average().toFloat(),
        pixels.map { it[2] }.average().toFloat(),
    )
    val v = rgbToHsv(mean[0], mean[1], mean[2])[2]
    val only = tintedWhite(mean, max(0.55f, v))
    return Extraction(only, listOf(only), achromatic = true)
}

/**
 * Extract up to [k] theme-faithful colours from RGB pixels using CIELAB k-means.
 * Verbatim from :app (which keeps this `internal` so a JVM unit test can drive it).
 */
internal fun kmeansPalette(pixels: List<FloatArray>, k: Int = 5): Extraction? {
    if (pixels.isEmpty()) return null

    val body = pixels.filter {
        val luma = 0.299f * it[0] + 0.587f * it[1] + 0.114f * it[2]
        luma >= 0.04f && luma <= 0.98f
    }
    if (body.size < 6) return lowColourFallback(pixels)

    val seed = body.fold(17L) { acc, p ->
        acc * 31 + ((p[0] * 255).toInt() shl 16 or ((p[1] * 255).toInt() shl 8) or (p[2] * 255).toInt())
    }
    val clusters = kmeansClusters(body, kotlin.math.min(CLUSTERS, body.size), kotlin.random.Random(seed))
    if (clusters.isEmpty()) return lowColourFallback(pixels)

    val peakChroma = clusters.maxOf { it.chroma }
    if (peakChroma < ACHROMATIC_C) return lowColourFallback(body)

    val accentFloor = max(MIN_ACCENT_C, peakChroma * ACCENT_C_FRACTION)

    val ranked = clusters
        .filter { it.chroma >= accentFloor }
        .sortedByDescending { it.chroma * kotlin.math.sqrt(it.population) }

    val accents = mutableListOf<Pair<FloatArray, Float>>()
    val pickedHues = mutableListOf<Float>()
    for (c in ranked) {
        if (accents.size >= MAX_ACCENTS) break
        val h = c.hsv[0]
        if (pickedHues.all { hueDistance(h, it) >= HUE_MIN_SEP_DEG }) {
            pickedHues.add(h)
            accents.add(hsvToRgb(h, c.hsv[1], max(VALUE_FLOOR, c.hsv[2])) to c.population)
        }
    }

    val nonAccent = clusters.filter { it.chroma < accentFloor }
    val remaining = (k - accents.size).coerceAtLeast(0)
    val tinted = nonAccent.filter { it.chroma >= ACHROMATIC_C }.sortedByDescending { it.population }
    val neutral = nonAccent.filter { it.chroma < ACHROMATIC_C }.sortedByDescending { it.population }
    val bases = (tinted + neutral.take(MAX_NEUTRAL_SWATCHES))
        .take(remaining)
        .map { c ->
            val rgb = if (c.chroma < ACHROMATIC_C) tintedWhite(c.rgb, c.hsv[2])
            else hsvToRgb(c.hsv[0], c.hsv[1], max(VALUE_FLOOR, c.hsv[2]))
            rgb to c.population
        }

    val out = accents + bases
    if (out.isEmpty()) return lowColourFallback(body)

    val lead = accents.firstOrNull()?.first
        ?: clusters.maxByOrNull { it.chroma }!!.let { hsvToRgb(it.hsv[0], it.hsv[1], max(VALUE_FLOOR, it.hsv[2])) }

    val ordered = out.sortedBy { rgbToHsv(it.first[0], it.first[1], it.first[2])[0] }
    return Extraction(
        lead = lead,
        swatches = ordered.map { it.first },
        populations = ordered.map { it.second },
        achromatic = accents.isEmpty(),
    )
}

/** Turn an [Extraction] into the palette — legible against true black, no invented colour. */
internal fun liftedPalette(extracted: Extraction): AlbumPalette {
    val accent = liftOne(extracted.lead, ACCENT_L_MIN, ACCENT_L_MAX, extracted.achromatic)
    val accentL = oklabToOklch(rgbToOklab(accent.red, accent.green, accent.blue))[0]

    val companionCeiling = (accentL - COMPANION_L_HEADROOM).coerceAtLeast(COMPANION_L_MIN)
    val companions = extracted.swatches
        .filter { it !== extracted.lead }
        .map { rgb ->
            val chroma = oklabToOklch(rgbToOklab(rgb[0], rgb[1], rgb[2]))[1]
            liftOne(rgb, COMPANION_L_MIN, companionCeiling, achromatic = chroma < ACHROMATIC_OKLCH_C)
        }

    return AlbumPalette(accent = accent, swatches = listOf(accent) + companions)
}

/** One extracted colour → one the app can paint with, in OKLCh. Hue kept exactly. */
private fun liftOne(rgb: FloatArray, minL: Float, maxL: Float, achromatic: Boolean): Color {
    val lch = oklabToOklch(rgbToOklab(rgb[0], rgb[1], rgb[2]))
    val hue = lch[2]
    val targetL = lch[0].coerceIn(minL, maxL.coerceAtLeast(minL))
    if (achromatic) return oklchColor(targetL, lch[1].coerceAtMost(NEUTRAL_MAX_C), hue)
    val ceiling = maxChromaFor(targetL, hue)
    return oklchColor(targetL, lch[1].coerceIn(ceiling * MIN_ACCENT_C_FRACTION, ceiling), hue)
}

/** Palette from raw RGBA pixels (row-major, 4 bytes/px) — the JVM-testable half. */
internal fun paletteOfPixels(rgba: ByteArray, w: Int, h: Int): AlbumPalette? {
    val n = w * h
    val pixels = ArrayList<FloatArray>(n)
    var i = 0
    while (i < n) {
        val o = i * 4
        pixels.add(
            floatArrayOf(
                (rgba[o].toInt() and 0xFF) / 255f,
                (rgba[o + 1].toInt() and 0xFF) / 255f,
                (rgba[o + 2].toInt() and 0xFF) / 255f,
            )
        )
        i++
    }
    val extracted = kmeansPalette(pixels, k = 5) ?: return null
    return liftedPalette(extracted)
}

/**
 * Load [url] and derive the album's [AlbumPalette], falling back to the default.
 *
 * Desktop loader: OkHttp fetch → Skiko's own decoder → a raster Surface scaled to
 * 64×64 (`Surface.makeRaster` + `drawImageRect`, the honest equivalent of :app's
 * createScaledBitmap) → per-pixel sampling via Pixmap.getColor — no byte-order
 * assumptions, unlike the first cut of this loader. Extraction runs on
 * Dispatchers.IO; the previous palette is held while a new one is pending, so a
 * slow fetch never flashes the default between two albums — the same hold :app's
 * rememberAlbumPalette keeps.
 */
@Composable
fun rememberAlbumPalette(url: String?): AlbumPalette {
    var published by remember { mutableStateOf(AlbumPalette()) }

    LaunchedEffect(url) {
        if (url.isNullOrBlank()) { published = AlbumPalette(); return@LaunchedEffect }
        val palette = withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val bytes = paletteHttp.newCall(
                    Request.Builder().url(url).build()
                ).execute().use { resp ->
                    if (!resp.isSuccessful) null else resp.body?.bytes()
                } ?: return@withContext null
                val img = Image.makeFromEncoded(bytes)
                try {
                    paletteOfImage(img)
                } finally {
                    img.close()
                }
            } catch (_: Exception) { null }
        }
        if (palette != null) published = palette
    }
    // Graded here, so every room the palette lights takes Settings' colour mood.
    val mood = LocalColourMood.current
    return remember(published, mood) { published.graded(mood) }
}

/**
 * The palette of an already-decoded [img] — the half of [rememberAlbumPalette] after the
 * fetch, shared with the screensaver's preloader, which decodes each cover once for both
 * the picture and its colours. Does not close [img].
 */
internal fun paletteOfImage(img: Image): AlbumPalette? {
    if (img.width <= 0 || img.height <= 0) return null
    return Surface.makeRaster(
        ImageInfo.makeN32(SAMPLE, SAMPLE, ColorAlphaType.UNPREMUL)
    ).use { surface ->
        surface.canvas.drawImageRect(
            img,
            org.jetbrains.skia.Rect.makeXYWH(0f, 0f, img.width.toFloat(), img.height.toFloat()),
            org.jetbrains.skia.Rect.makeXYWH(0f, 0f, SAMPLE.toFloat(), SAMPLE.toFloat()),
        )
        val px = Pixmap()
        try {
            if (!surface.peekPixels(px)) return@use null
            val pixels = ArrayList<FloatArray>(SAMPLE * SAMPLE)
            for (y in 0 until SAMPLE) for (x in 0 until SAMPLE) {
                val c = px.getColor(x, y)
                pixels.add(
                    floatArrayOf(
                        ((c shr 16) and 0xFF) / 255f,
                        ((c shr 8) and 0xFF) / 255f,
                        (c and 0xFF) / 255f,
                    )
                )
            }
            kmeansPalette(pixels, k = 5)?.let { liftedPalette(it) }
        } finally {
            px.close()
        }
    }
}

/** OkHttp client for the one artwork fetch per album change — small and patient. */
private val paletteHttp by lazy {
    OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
}