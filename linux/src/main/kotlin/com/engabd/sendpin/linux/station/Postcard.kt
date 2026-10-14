package com.engabd.sendpin.linux.station

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.linux.screensaver.FittedTitle
import com.engabd.sendpin.linux.screensaver.faceFor
import com.engabd.sendpin.ui.design.AlbumPalette
import com.engabd.sendpin.ui.design.paletteOfImage
import com.engabd.sendpin.ui.theme.AccentChoice
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.MonoFont
import com.engabd.sendpin.ui.theme.SendspinTheme
import com.engabd.sendpin.ui.theme.ThemeChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * A listening postcard: the cover, the title in its own face, the signal line and the
 * date, set on a portrait card and saved under ~/Pictures/CAMusic — a memento of a good
 * evening. Rendered off screen with the same scene the preview renderer uses, so it is the
 * panel's own typography and not a screenshot.
 */

/** Everything a postcard says. */
internal data class PostcardData(
    val title: String,
    val artist: String?,
    val album: String?,
    val genre: String?,
    /** "FLAC 24/96 → Topping D90SE", or empty. */
    val signal: String,
    val coverUrl: String?,
    val id: String,
)

internal const val POSTCARD_WIDTH = 800
internal const val POSTCARD_HEIGHT = 1280

/** `camusic-2026-10-14-2130-aurora-vale-glass-tides.png` — sortable, and safe on any filesystem. */
internal fun postcardFileName(now: LocalDateTime, artist: String?, title: String): String {
    val stamp = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm"))
    val slug = listOfNotNull(artist, title).joinToString(" ")
        .lowercase(Locale.ROOT)
        .map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
        .replace(Regex("-+"), "-").trim('-').take(48).trim('-')
    return if (slug.isEmpty()) "camusic-$stamp.png" else "camusic-$stamp-$slug.png"
}

/** Where postcards are kept: `~/Pictures/CAMusic`. */
internal fun postcardDirectory(home: String = System.getProperty("user.home")): Path =
    Path.of(home, "Pictures", "CAMusic")

/**
 * Make the postcard for [data] and write it; the path it was written to. Blocking work (the
 * cover download, the render, the file) runs off the main thread.
 */
internal suspend fun savePostcard(data: PostcardData, now: LocalDateTime = LocalDateTime.now()): Path =
    withContext(Dispatchers.Default) {
        val bytes = data.coverUrl?.takeIf { it.isNotBlank() }?.let { runCatching { fetch(it) }.getOrNull() }
        val skia = bytes?.let { runCatching { org.jetbrains.skia.Image.makeFromEncoded(it) }.getOrNull() }
        val palette = skia?.let { paletteOfImage(it) } ?: AlbumPalette()
        val cover = skia?.toComposeImageBitmap()
        val png = renderPostcard(data, cover, palette, now)
        skia?.close()
        val dir = postcardDirectory()
        Files.createDirectories(dir)
        val file = dir.resolve(postcardFileName(now, data.artist, data.title))
        Files.write(file, png)
        file
    }

private fun fetch(url: String): ByteArray {
    val c = URL(url).openConnection().apply { connectTimeout = 5_000; readTimeout = 10_000 }
    return c.getInputStream().use { it.readBytes() }
}

/** The card as PNG bytes. */
internal fun renderPostcard(data: PostcardData, cover: ImageBitmap?, palette: AlbumPalette, now: LocalDateTime): ByteArray {
    val scene = ImageComposeScene(POSTCARD_WIDTH, POSTCARD_HEIGHT, Density(1f)) {
        SendspinTheme(theme = ThemeChoice.DARK, accentChoice = AccentChoice.ALBUM, glassBlur = false) {
            PostcardArt(data, cover, palette, now)
        }
    }
    try {
        // A few frames, so text is measured and laid out before the one that is kept.
        var t = 0L
        repeat(4) { scene.render(t); t += 16_000_000L }
        val image = scene.render(t)
        return image.encodeToData(EncodedImageFormat.PNG)!!.bytes
    } finally {
        scene.close()
    }
}

@Composable
private fun PostcardArt(data: PostcardData, cover: ImageBitmap?, palette: AlbumPalette, now: LocalDateTime) {
    val accent = palette.accent
    Box(Modifier.fillMaxSize().background(Color(0xFF08080A))) {
        if (cover != null) {
            Image(
                cover, null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(70.dp, BlurredEdgeTreatment.Unbounded),
            )
        } else {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(palette.swatch(0), palette.swatch(1)))))
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.30f), 0.5f to Color.Black.copy(alpha = 0.50f), 1f to Color.Black.copy(alpha = 0.90f)),
            ),
        )
        Column(
            Modifier.fillMaxSize().padding(horizontal = 72.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(80.dp))
            Box(
                Modifier
                    .size(590.dp)
                    .shadow(48.dp, RoundedCornerShape(10.dp), ambientColor = accent, spotColor = accent)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Brush.linearGradient(listOf(palette.swatch(0), palette.swatch(1)))),
            ) {
                if (cover != null) Image(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(56.dp))
            data.artist?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it.uppercase(), color = Color.White.copy(alpha = 0.78f), fontFamily = AppFont,
                    fontWeight = FontWeight.Medium, fontSize = 20.sp, letterSpacing = 0.32.em,
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(18.dp))
            }
            val face = faceFor(listOfNotNull(data.genre), null, data.title, data.id)
            FittedTitle(data.title, face, 78f, TextAlign.Center, Color.White, Modifier)
            data.album?.takeIf { it.isNotBlank() && it != data.title }?.let {
                Spacer(Modifier.height(16.dp))
                Text(
                    it, color = Color.White.copy(alpha = 0.62f), fontFamily = AppFont, fontStyle = FontStyle.Italic,
                    fontSize = 22.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(width = 56.dp, height = 2.dp).background(accent))
            Spacer(Modifier.height(24.dp))
            if (data.signal.isNotBlank()) {
                Text(
                    data.signal, color = Color.White.copy(alpha = 0.72f), fontFamily = MonoFont, fontSize = 17.sp,
                    letterSpacing = 0.14.em, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
            }
            Text(
                now.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault())).uppercase(),
                color = Color.White.copy(alpha = 0.50f), fontFamily = AppFont, fontSize = 15.sp, letterSpacing = 0.3.em,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(40.dp))
            Text(
                "CAMUSIC", color = Color.White.copy(alpha = 0.30f), fontFamily = AppFont, fontWeight = FontWeight.Bold,
                fontSize = 13.sp, letterSpacing = 0.5.em,
            )
            Spacer(Modifier.height(48.dp))
        }
    }
}
