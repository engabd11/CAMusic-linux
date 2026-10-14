package com.engabd.sendpin.linux.station

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.ErrorRed
import com.engabd.sendpin.ui.theme.Hairline
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary

/*
 * The menu a held row opens: the song's name, and what can be done with it — a short list
 * of full-height targets over a dimmed panel. One host at the top of the shell draws it for
 * every pane, so a track row in the library and a row in the queue share it; a tap anywhere
 * outside it closes it.
 */

/** One thing a menu can do. [danger] sets it in the error colour. */
internal data class MenuAction(
    val label: String,
    val icon: ImageVector? = null,
    val danger: Boolean = false,
    val run: () -> Unit,
)

internal data class MenuRequest(val title: String, val subtitle: String?, val actions: List<MenuAction>)

/** The menu on show, if any. Hoisted to the shell; panes reach it through [LocalActionMenu]. */
internal class ActionMenuState {
    var request by mutableStateOf<MenuRequest?>(null)
        private set

    fun show(title: String, subtitle: String?, actions: List<MenuAction>) {
        if (actions.isNotEmpty()) request = MenuRequest(title, subtitle, actions)
    }

    fun hide() { request = null }
}

/** Null where no host is drawing menus (the previews, a pane on its own): rows then do nothing on hold. */
internal val LocalActionMenu = staticCompositionLocalOf<ActionMenuState?> { null }

@Composable
internal fun ActionMenuHost(state: ActionMenuState) {
    val shown = state.request
    AnimatedVisibility(visible = shown != null, enter = fadeIn(tween(160)), exit = fadeOut(tween(120))) {
        // Kept while it fades out, so the card does not blank before it goes.
        val req = shown ?: return@AnimatedVisibility
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.58f))
                .pointerInput(Unit) { detectTapGestures { state.hide() } },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(visible = true, enter = scaleIn(tween(180), initialScale = 0.94f) + fadeIn(tween(180)), exit = scaleOut() + fadeOut()) {
                Column(
                    Modifier
                        .padding(horizontal = 28.dp)
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        // A tap on the card itself is not a tap outside it.
                        .pointerInput(Unit) { detectTapGestures { } }
                        .clip(RoundedCornerShape(26.dp))
                        .background(Color(0xF2191A1F))
                        .border(1.dp, Hairline, RoundedCornerShape(26.dp))
                        .padding(vertical = 14.dp),
                ) {
                    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                        Text(
                            req.title, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold,
                            fontSize = 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        req.subtitle?.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = TextMuted, fontFamily = AppFont, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    req.actions.forEach { action ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 68.dp)
                                .clickable {
                                    state.hide()
                                    action.run()
                                }
                                .padding(horizontal = 24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            action.icon?.let {
                                Icon(it, null, tint = if (action.danger) ErrorRed else TextPrimary, modifier = Modifier.size(26.dp))
                            }
                            Text(
                                action.label,
                                color = if (action.danger) ErrorRed else TextPrimary,
                                fontFamily = AppFont, fontWeight = FontWeight.Medium, fontSize = 20.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}
