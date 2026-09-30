package com.homecam.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.homecam.app.Role

@Composable
fun RolePickerScreen(onPick: (Role) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("What is this phone?", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "You can switch later from the home screen.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(32.dp))

        RoleCard(
            title = "The camera",
            body = "Stays plugged in and watches. Also runs the signaling server, so " +
                "there is no third box and no cloud account.",
            onClick = { onPick(Role.CAMERA) },
        )
        Spacer(Modifier.height(16.dp))
        RoleCard(
            title = "The viewer",
            body = "Your daily phone. Pairs with a camera once, then watches it.",
            onClick = { onPick(Role.VIEWER) },
        )
    }
}

@Composable
private fun RoleCard(title: String, body: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
