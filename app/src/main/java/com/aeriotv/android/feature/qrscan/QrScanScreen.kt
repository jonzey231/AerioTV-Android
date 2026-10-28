package com.aeriotv.android.feature.qrscan

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aeriotv.android.core.qr.CodeFileImport
import com.aeriotv.android.core.qr.CodeTextImport
import com.aeriotv.android.core.qr.ImportSourceCleanup
import com.aeriotv.android.core.qr.QrImageImport
import com.aeriotv.android.core.qr.QrLuminanceDecoder
import com.aeriotv.android.core.qr.ScannedCode
import com.aeriotv.android.ui.settings.rememberIsTvDevice
import com.google.zxing.PlanarYUVLuminanceSource
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val CAMERA_RATIONALE = "AerioTV uses the camera only to read QR codes."

/** Default rule: a Dispatcharr user code or an AerioTV setup code. Returns an error, or null to accept. */
fun acceptAnyKnownCode(r: ScannedCode.Result): String? =
    if (r is ScannedCode.Result.Invalid) ScannedCode.INVALID_MESSAGE else null

/** Hands a scanned `aeriotv://setup?...` link to MainActivity, which opens the prefilled Set up a TV flow. */
fun openSetupLink(context: Context, link: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(context.packageName)
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/**
 * "Scan QR code", "Choose from Photos", and "Import file" for a form (phones
 * and tablets; on TV it draws nothing, TVs have no camera). Import file opens
 * the system document picker for the same code saved as a file (the
 * `user_api_key` JSON, or a text file holding a setup link). [accept] returns
 * an error message for a result this form cannot use, or null; accepted
 * results go to [onResult].
 */
@Composable
fun QrScanActions(
    onResult: (ScannedCode.Result) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    prompt: String = "Point the camera at a Dispatcharr user QR code or an AerioTV setup code.",
    accept: (ScannedCode.Result) -> String? = ::acceptAnyKnownCode,
) {
    if (rememberIsTvDevice()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val currentAccept by rememberUpdatedState(accept)
    val currentOnResult by rememberUpdatedState(onResult)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; error = null
        scope.launch {
            val text = QrImageImport.decode(context, uri)
            busy = false
            if (text == null) { error = ScannedCode.NO_QR_IN_IMAGE; return@launch }
            val r = ScannedCode.parse(text)
            error = currentAccept(r)
            if (error == null) {
                ImportSourceCleanup.offer(ImportSourceCleanup.photoSource(context, uri))
                currentOnResult(r)
            }
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; error = null
        scope.launch {
            val r = CodeFileImport.read(context, uri)
            busy = false
            if (r is ScannedCode.Result.Invalid) { error = CodeTextImport.FILE_INVALID_MESSAGE; return@launch }
            error = currentAccept(r)
            if (error == null) {
                ImportSourceCleanup.offer(ImportSourceCleanup.documentSource(context, uri))
                currentOnResult(r)
            }
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { error = null; scanning = true },
                enabled = enabled && !busy,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Scan QR code")
            }
            OutlinedButton(
                onClick = { error = null; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = enabled && !busy,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Choose from Photos")
            }
        }
        OutlinedButton(
            onClick = { error = null; filePicker.launch(CodeTextImport.PICKER_MIME_TYPES) },
            enabled = enabled && !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Icon(Icons.Outlined.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("Import file")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    if (scanning) {
        QrScanDialog(
            prompt = prompt,
            validate = { currentAccept(ScannedCode.parse(it)) },
            onResult = { text -> scanning = false; currentOnResult(ScannedCode.parse(text)) },
            onCancel = { scanning = false },
        )
    }
}

/** Full-screen [QrScanScreen] in a dialog. */
@Composable
fun QrScanDialog(
    prompt: String,
    validate: (String) -> String?,
    onResult: (String) -> Unit,
    onCancel: () -> Unit,
) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        QrScanScreen(prompt = prompt, validate = validate, onResult = onResult, onCancel = onCancel)
    }
}

/**
 * Shared scanner: full-screen camera preview with a viewfinder, "Choose from
 * Photos", and Cancel. Returns the decoded text of the first code [validate]
 * accepts (null = accept); a rejected code shows its message and scanning
 * goes on. Without the CAMERA permission the screen still works through Photos.
 */
@Composable
fun QrScanScreen(
    prompt: String,
    validate: (String) -> String?,
    onResult: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentValidate by rememberUpdatedState(validate)
    val currentOnResult by rememberUpdatedState(onResult)
    val done = remember { AtomicBoolean(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    var noCamera by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

    // [source] is the picked image or file behind an import; it is offered
    // for deletion only when the code is accepted (camera scans pass null).
    fun deliver(text: String, source: ImportSourceCleanup.Source? = null) {
        if (done.get()) return
        val problem = currentValidate(text)
        if (problem == null) {
            if (done.compareAndSet(false, true)) {
                ImportSourceCleanup.offer(source)
                currentOnResult(text)
            }
        } else error = problem
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; error = null
        scope.launch {
            val text = QrImageImport.decode(context, uri)
            busy = false
            if (text == null) error = ScannedCode.NO_QR_IN_IMAGE
            else deliver(text, ImportSourceCleanup.photoSource(context, uri))
        }
    }

    // The same code saved as a file: parse it here and hand on the canonical
    // text (the JSON or the link) so [validate] and [onResult] read it as a scan.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; error = null
        scope.launch {
            val r = CodeFileImport.read(context, uri)
            busy = false
            val text = CodeTextImport.canonicalText(r)
            if (text == null) error = CodeTextImport.FILE_INVALID_MESSAGE
            else deliver(text, ImportSourceCleanup.documentSource(context, uri))
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted && !noCamera) {
            CameraPreview(onText = { deliver(it) }, onNoCamera = { noCamera = true })
            Box(
                Modifier.align(Alignment.Center).size(260.dp)
                    .border(3.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(20.dp)),
            )
        }
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                when {
                    noCamera -> "No camera is available. Choose a QR code image from Photos instead."
                    granted -> prompt
                    else -> "$CAMERA_RATIONALE You can still choose a QR code image from Photos."
                },
                color = Color.White,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.widthIn(max = 480.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp)).padding(12.dp),
            )
            Spacer(Modifier.weight(1f))
            error?.let {
                Text(
                    it,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.widthIn(max = 480.dp)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                )
                Spacer(Modifier.size(12.dp))
            }
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!granted && asked) {
                    Button(onClick = { permission.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                        Text("Allow Camera")
                    }
                }
                Button(
                    onClick = { error = null; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Choose from Photos")
                }
                Button(
                    onClick = { error = null; filePicker.launch(CodeTextImport.PICKER_MIME_TYPES) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                ) {
                    Icon(Icons.Outlined.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Import file")
                }
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                    Text("Cancel", color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(onText: (String) -> Unit, onNoCamera: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnText by rememberUpdatedState(onText)
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // TextureView: a SurfaceView can draw under the dialog window.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
    LaunchedEffect(lifecycleOwner) {
        val p: ProcessCameraProvider = kotlinx.coroutines.suspendCancellableCoroutine<ProcessCameraProvider?> { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                { if (cont.isActive) cont.resumeWith(Result.success(runCatching { future.get() }.getOrNull())) },
                ContextCompat.getMainExecutor(context),
            )
        } ?: run { onNoCamera(); return@LaunchedEffect }
        provider = p
        val selector = when {
            runCatching { p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false) -> CameraSelector.DEFAULT_BACK_CAMERA
            runCatching { p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false) -> CameraSelector.DEFAULT_FRONT_CAMERA
            else -> { onNoCamera(); return@LaunchedEffect }
        }
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build(),
            )
            .build()
        val main = ContextCompat.getMainExecutor(context)
        analysis.setAnalyzer(executor) { image -> decodeFrame(image)?.let { t -> main.execute { currentOnText(t) } } }
        runCatching {
            p.unbindAll()
            p.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
        }.onFailure { onNoCamera() }
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { provider?.unbindAll() }
            executor.shutdown()
        }
    }
}

/** Luminance (Y plane) of one frame through zxing. Never throws. */
private fun decodeFrame(image: ImageProxy): String? = try {
    val plane = image.planes[0]
    val buf = plane.buffer
    val data = ByteArray(buf.remaining()).also { buf.get(it) }
    val stride = plane.rowStride
    val w = image.width; val h = image.height
    if (stride < w || data.size < stride * (h - 1) + w) null
    else QrLuminanceDecoder.decodeFrame(PlanarYUVLuminanceSource(data, stride, h, 0, 0, w, h, false))
} catch (_: Throwable) {
    null
} finally {
    image.close()
}
