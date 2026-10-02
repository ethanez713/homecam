package com.homecam.app.viewer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.homecam.app.ui.kit.Toast
import androidx.compose.ui.unit.dp
import com.homecam.app.R
import com.homecam.app.ui.kit.Badge
import com.homecam.app.ui.kit.BottomTabs
import com.homecam.app.ui.kit.TabItem
import com.homecam.app.ui.kit.Tone
import com.homecam.app.ui.kit.TopBar

enum class CameraTab { LIVE, CLIPS, SETTINGS }

private val tabs = listOf(
    TabItem(CameraTab.LIVE, "Live", R.drawable.lucide_video),
    TabItem(CameraTab.CLIPS, "Clips", R.drawable.lucide_film),
    TabItem(CameraTab.SETTINGS, "Settings", R.drawable.lucide_settings),
)

/**
 * The frame around one camera: its name and whether it is armed above, the three places to
 * go below. [chrome] is off when the picture has the whole screen, as it does in landscape.
 */
@Composable
fun CameraShell(
    alias: String,
    /** Where the connection stands, in a few words. */
    status: String,
    armed: Boolean?,
    tab: CameraTab,
    onTab: (CameraTab) -> Unit,
    onBack: () -> Unit,
    tabsEnabled: Boolean = true,
    chrome: Boolean = true,
    toast: String? = null,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        if (chrome) {
            TopBar(alias, onBack = onBack, subtitle = status, subtitleIsData = false) {
                if (armed != null) {
                    Badge(if (armed) "Armed" else "Disarmed", if (armed) Tone.SUCCESS else Tone.WARNING, dot = true)
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
        Box(Modifier.weight(1f)) {
            content()
            if (toast != null) Toast(toast, Modifier.align(Alignment.BottomStart))
        }
        if (chrome && tabsEnabled) BottomTabs(tabs, tab, onTab)
    }
}
