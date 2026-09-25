package androidx.activity.compose

import androidx.compose.runtime.Composable

/**
 * Desktop shim: the panel owns its own back handling (the shell's tab state), so
 * the shared files' BackHandler calls are deliberately inert here. Kept as a
 * no-op rather than unwired so a shared file may call it without edits.
 */
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    // Intentionally empty — see class doc.
}