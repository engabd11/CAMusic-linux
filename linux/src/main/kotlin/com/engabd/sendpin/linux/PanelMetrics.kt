package com.engabd.sendpin.linux

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The panel's touch scale, codified from the now-playing screen (PortraitShell) — the
 * user's ruling: the cover's scale "should be the standard of the entire app". A phone's
 * 12–14sp rows and 40dp chips vanish at arm's length on a wall panel; every list, chip,
 * field and nav target below reads against that instead.
 *
 * Reference points it was measured from: cover title 32sp / artist 17sp, play circle
 * 92dp, skip icons 46dp. The panes don't need to be the *cover*, but they are the same
 * room viewed from the same distance — so rows, chips and labels scale from the same
 * hand, not from a phone's.
 */
internal object PanelMetrics {
    // ---- Touch targets ----
    /** List rows (tracks, queue) — was 48dp, the old phone-scale floor. */
    val RowMinHeight: Dp = 64.dp

    /** Chips and category pills — the old ones sat at 40dp or, worse, unset (~34dp). */
    val ChipMinHeight: Dp = 48.dp

    /** Action buttons (Play album, Apply, Quit, Start/Stop) — M3's 40dp default is phone-scale. */
    val ButtonMinHeight: Dp = 52.dp

    /** Mini player bar height — was 64dp. */
    val MiniBarHeight: Dp = 72.dp

    /** Bottom nav bar height — was 64dp. */
    val NavHeight: Dp = 72.dp

    // ---- Type ----
    /** Page headers ("Queue", "Settings", "Hue Ghost") — was 22sp. */
    val PageTitle: TextUnit = 26.sp

    /** Shelf / section headings ("Albums", "MPD") — was 16sp. */
    val SectionTitle: TextUnit = 18.sp

    /** Field labels ("Mode", "Offset (s)", "Layout mode") — was 15sp. */
    val FieldLabel: TextUnit = 16.sp

    /** List-row primary text — was 14sp. Pairs with the cover's 32/17 rhythm. */
    val RowTitle: TextUnit = 18.sp

    /** List-row secondary text (artist under a title) — was 11sp. */
    val RowSecondary: TextUnit = 14.sp

    /** Album-tile captions on the grid/shelves — title was 13sp. */
    val TileTitle: TextUnit = 16.sp

    /** Album-tile captions — subtitle was 11sp. */
    val TileSubtitle: TextUnit = 13.sp

    /** Chip / pill text — was 12–13sp. */
    val ChipLabel: TextUnit = 15.sp

    /** Standalone explanatory text ("Nothing queued", errors) — was 13–14sp. */
    val Body: TextUnit = 16.sp

    /** Muted helper text (settings hints) — was 11sp. */
    val Hint: TextUnit = 13.sp

    /** Tertiary readouts: durations, track numbers, timestamps — was 10–12sp. */
    val Meta: TextUnit = 13.sp

    /** Bottom-nav / tab-strip labels — was 10sp. */
    val NavLabel: TextUnit = 12.sp

    /** The connection-status line under the nav / in the tab strip — was 9sp. */
    val NavStatus: TextUnit = 11.sp

    // ---- Chip shape ----
    /** Horizontal padding inside a chip, matched to [ChipMinHeight] + [ChipLabel]. */
    val ChipPadH: Dp = 16.dp

    /** Vertical padding inside a chip. */
    val ChipPadV: Dp = 10.dp

    /** Gap between neighbouring chips — was 6dp, finger-tight. */
    val ChipGap: Dp = 8.dp
}
