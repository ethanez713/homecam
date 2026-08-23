package com.homecam.app.viewer.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.homecam.core.pairing.CameraPairingSession
import com.homecam.proto.PairingInvite
import java.util.concurrent.Executors

@Composable
fun ScanScreen(onScanned: (PairingInvite) -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    if (!granted) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Scanning needs the camera", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { ask.launch(Manifest.permission.CAMERA) }) { Text("Allow") }
        }
        return
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        BarcodeCamera(onScanned)
        Text(
            "Point at the code on the camera phone",
            Modifier.padding(24.dp),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun BarcodeCamera(onScanned: (PairingInvite) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient() }
    // One invite is enough; a second callback while the caller is navigating away would
    // try to pair twice with a token that is already spent.
    var handled by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown(); scanner.close() }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { proxy ->
                    val image = proxy.image
                    if (image == null || handled) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    scanner.process(InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener { codes ->
                            codes.firstNotNullOfOrNull { it.decoded() }?.let {
                                if (!handled) { handled = true; onScanned(it) }
                            }
                        }
                        .addOnCompleteListener { proxy.close() }
                }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

private fun Barcode.decoded(): PairingInvite? =
    rawValue?.let { CameraPairingSession.decodeInvite(it) }
