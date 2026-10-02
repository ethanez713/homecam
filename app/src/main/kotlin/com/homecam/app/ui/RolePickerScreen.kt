package com.homecam.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.homecam.app.R
import com.homecam.app.Role
import com.homecam.app.ui.kit.Chevron
import com.homecam.app.ui.kit.Gutter
import com.homecam.app.ui.kit.LucideIcon
import com.homecam.app.ui.kit.PaperCard
import com.homecam.app.ui.theme.Ethan

@Composable
fun RolePickerScreen(onPick: (Role) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = Gutter, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("What is this phone?", style = Ethan.type.display, color = Ethan.colors.textHeading)
        Spacer(Modifier.height(6.dp))
        Text("You can switch later from the home screen.", style = Ethan.type.body, color = Ethan.colors.textMuted)
        Spacer(Modifier.height(24.dp))

        RoleCard(
            icon = R.drawable.lucide_cctv,
            title = "The camera",
            body = "Stays plugged in and watches. It is also the server, so there is no third box and no cloud account.",
            onClick = { onPick(Role.CAMERA) },
        )
        Spacer(Modifier.height(8.dp))
        RoleCard(
            icon = R.drawable.lucide_smartphone,
            title = "The viewer",
            body = "Your daily phone. Pairs with a camera once, then watches it from anywhere.",
            onClick = { onPick(Role.VIEWER) },
        )
    }
}

@Composable
private fun RoleCard(icon: Int, title: String, body: String, onClick: () -> Unit) {
    PaperCard(padding = 14.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LucideIcon(icon, null, size = 24.dp, tint = Ethan.colors.textAccent)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = Ethan.type.title2, color = Ethan.colors.textHeading)
                Text(body, style = Ethan.type.bodySmall, color = Ethan.colors.textMuted)
            }
            Spacer(Modifier.width(8.dp))
            Chevron()
        }
    }
}
