package com.engabd.sendpin.linux.station

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether times and the clock are set large, for reading from across the room (Settings →
 * Big readouts). Provided by the shell from the setting; the panes read it where they show
 * a time, and a pane drawn on its own sees the ordinary size.
 */
internal val LocalBigReadouts = staticCompositionLocalOf { false }
