package com.homecam.app.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.homecam.app.AppGraph
import com.homecam.app.camera.ui.AuditScreen
import com.homecam.app.viewer.ui.ScanScreen
import com.homecam.app.viewer.ui.ViewerHomeScreen
import com.homecam.core.audit.AuditChain
import kotlinx.coroutines.launch

private enum class ViewerScreen { HOME, SCAN, LOG }

@Composable
fun ViewerRoot() {
    val context = LocalContext.current
    val graph = remember { AppGraph.get(context) }
    val node = remember { graph.viewerNode }
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf(ViewerScreen.HOME) }
    val cameras by node.paired.collectAsState()

    when (screen) {
        ViewerScreen.HOME -> ViewerHomeScreen(
            cameras = cameras,
            onAdd = { screen = ViewerScreen.SCAN },
            onOpen = { },
            onForget = { scope.launch { node.forget(it) } },
            onLog = { screen = ViewerScreen.LOG },
        )

        ViewerScreen.SCAN -> ScanScreen { invite ->
            scope.launch { node.pin(invite); screen = ViewerScreen.HOME }
        }

        ViewerScreen.LOG -> {
            val events by graph.audit.observeRecent().collectAsState(emptyList())
            val verification by produceState<AuditChain.Verification?>(null, events.size) {
                value = graph.audit.verify()
            }
            AuditScreen(events, verification)
        }
    }
}
