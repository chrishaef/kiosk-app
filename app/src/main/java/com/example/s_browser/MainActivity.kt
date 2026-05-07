package com.example.s_browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.ValueCallback
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.URLUtil
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** MIME type hint for [Intent.ACTION_CREATE_DOCUMENT] from a download file name. */
private fun mimeTypeForFileName(fileName: String): String {
    return when {
        fileName.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
        fileName.endsWith(".xlsx", ignoreCase = true) ->
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        fileName.endsWith(".xls", ignoreCase = true) -> "application/vnd.ms-excel"
        fileName.endsWith(".zip", ignoreCase = true) -> "application/zip"
        fileName.endsWith(".csv", ignoreCase = true) -> "text/csv"
        else -> "application/octet-stream"
    }
}

/**
 * Opens the system "Save as" UI (SAF). Input: Pair(mimeType, suggestedFileName).
 */
private class CreateDocumentWithMime : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent {
        val (mimeType, title) = input
        return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, title)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
}

/** Chromium reports this when a load is superseded by a new navigation (not a real outage). */
private fun isCanceledNavigationError(description: CharSequence?): Boolean {
    if (description.isNullOrBlank()) return false
    val s = description.toString()
    return s.contains("ERR_ABORTED", ignoreCase = true) ||
        s.contains("ERR_CANCELED", ignoreCase = true)
}

@Suppress("DEPRECATION")
private fun applyShopWebViewColorPolicy(settings: WebSettings) {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
        WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_OFF)
    }
    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
        WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
    }
}

class MainActivity : ComponentActivity() {
    private var lockTaskHintShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Cleanup old staged downloads from previous sessions
        cleanupStagedDownloads(this)

        setContent {
            ShopWebView(
                url = "http://127.0.0.1:8000",
                onMinimize = {
                    disableLockTaskMode()
                    moveTaskToBack(true)
                },
                onGoHome = {
                    disableLockTaskMode()
                    val intent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                },
                onShareFile = {
                    disableLockTaskMode()
                },
                onExit = {
                    disableLockTaskMode()
                    finishAffinity()
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
        enableLockTaskModeIfPossible()
    }

    private fun enterImmersiveMode() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun enableLockTaskModeIfPossible() {
        try {
            startLockTask()
        } catch (_: Exception) {
            if (!lockTaskHintShown) {
                lockTaskHintShown = true
                Toast.makeText(
                    this,
                    "Für vollen Kiosk-Schutz bitte App-Anheften/Screen Pinning aktivieren.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun disableLockTaskMode() {
        try {
            stopLockTask()
        } catch (_: Exception) {
            // Ignore if not pinned/locked.
        }
    }
}

private fun cleanupStagedDownloads(context: Context, exclude: String? = null) {
    try {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (dir != null && dir.exists()) {
            dir.listFiles()?.forEach { 
                if (exclude == null || it.name != exclude) {
                    it.delete() 
                }
            }
        }
    } catch (_: Exception) { }
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun ShopWebView(
    url: String,
    modifier: Modifier = Modifier,
    onMinimize: () -> Unit,
    onGoHome: () -> Unit,
    onShareFile: () -> Unit,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    /** After the first successful paint, we stop the full-screen loader on navigations (avoids flicker). */
    var initialWebPaintDone by remember { mutableStateOf(false) }
    var hasError by remember { mutableStateOf(false) }
    val retryTickState = remember { mutableIntStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    
    // UI Visibility States
    var showPinDialog by remember { mutableStateOf(false) }
    var showPinSetupDialog by remember { mutableStateOf(false) }
    var showPinChangeDialog by remember { mutableStateOf(false) }
    var showExitConfirmDialog by remember { mutableStateOf(false) }
    var showMinimizeConfirmDialog by remember { mutableStateOf(false) }
    var showServerUrlDialog by remember { mutableStateOf(false) }
    var showAdminMenu by remember { mutableStateOf(false) }
    var showGoHomeConfirmDialog by remember { mutableStateOf(false) }
    var showDownloadActionDialog by remember { mutableStateOf(false) }

    // Persistent Settings & Tracking
    var savedAdminPin by remember(context) { mutableStateOf(loadAdminPin(context)) }
    var configuredUrl by remember(context) { mutableStateOf(loadServerUrl(context, url)) }
    var downloadActionFileName by remember { mutableStateOf("") }
    
    var filePathCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val pendingDownloadIdState = remember { mutableLongStateOf(-1L) }
    var pendingDownloadSaveSource by remember { mutableStateOf<Uri?>(null) }

    // Kiosk-style: Back is completely disabled to prevent re-entering restricted areas.
    BackHandler {
        Toast.makeText(context, "Zurück ist deaktiviert.", Toast.LENGTH_SHORT).show()
    }

    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback ?: return@rememberLauncherForActivityResult
        val uris = if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            when {
                data == null -> emptyArray()
                data.clipData != null -> {
                    val clip = data.clipData!!
                    Array(clip.itemCount) { idx -> clip.getItemAt(idx).uri }
                }
                data.data != null -> arrayOf(data.data!!)
                else -> emptyArray()
            }
        } else {
            emptyArray()
        }
        callback.onReceiveValue(uris)
        filePathCallback = null
    }

    val allowedHosts = remember(configuredUrl) {
        buildSet {
            add("127.0.0.1")
            add("localhost")
            configuredUrl.toUri().host?.lowercase()?.let { add(it) }
        }
    }
    val adminPinRef = rememberUpdatedState(newValue = savedAdminPin)
    val currentAllowedHosts by rememberUpdatedState(allowedHosts)
    val scope = rememberCoroutineScope()

    val saveDownloadToLauncher = rememberLauncherForActivityResult(CreateDocumentWithMime()) { destUri ->
        val src = pendingDownloadSaveSource
        pendingDownloadSaveSource = null
        if (src == null) return@rememberLauncherForActivityResult
        if (destUri == null) {
            Toast.makeText(context, "Speichern abgebrochen.", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(src)?.use { input ->
                        context.contentResolver.openOutputStream(destUri)?.use { output ->
                            input.copyTo(output)
                        }
                    }
                    // Delete the staged file immediately after successful copy
                    java.io.File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), downloadActionFileName).delete()
                }
                Toast.makeText(context, "Datei gespeichert.", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(context, "Speichern fehlgeschlagen.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Reliable completion check for the most recent app-started download.
    LaunchedEffect(pendingDownloadIdState.longValue) {
        val id = pendingDownloadIdState.longValue
        if (id <= 0L) return@LaunchedEffect
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        while (true) {
            try {
                val query = DownloadManager.Query().setFilterById(id)
                dm.query(query).use { cursor ->
                    if (cursor != null && cursor.moveToFirst()) {
                        val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val titleIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
                        val status = if (statusIdx >= 0) cursor.getInt(statusIdx) else -1
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            val srcUri = dm.getUriForDownloadedFile(id)
                            val title =
                                if (titleIdx >= 0) cursor.getString(titleIdx) ?: "download" else "download"
                            if (srcUri != null) {
                                // Clean up all older files before showing the new one
                                cleanupStagedDownloads(context, exclude = title)
                                pendingDownloadSaveSource = srcUri
                                downloadActionFileName = title
                                showDownloadActionDialog = true
                            }
                            pendingDownloadIdState.longValue = -1L
                            return@LaunchedEffect
                        }
                        if (status == DownloadManager.STATUS_FAILED) {
                            pendingDownloadIdState.longValue = -1L
                            return@LaunchedEffect
                        }
                    }
                }
            } catch (_: Exception) {
                pendingDownloadIdState.longValue = -1L
                return@LaunchedEffect
            }
            delay(1000)
        }
    }
    // Auto-retry after 3s whenever we are in error state.
    LaunchedEffect(hasError, retryTickState.intValue) {
        if (hasError && retryTickState.intValue > 0) {
            webViewRef?.loadUrl(configuredUrl)
        }
        if (hasError) {
            delay(3000)
            retryTickState.intValue += 1
        }
    }


    // Match shop portal so any gap between WebView paints is not pure black.
    val portalBg = Color("#252b23".toColorInt())
    
    Box(modifier = modifier.fillMaxSize().background(portalBg)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    
                    // Prevent native long-press link previews
                    // stealing kiosk long-press gestures (easter egg/admin trigger).
                    isLongClickable = false
                    setOnLongClickListener { true }
                    isHapticFeedbackEnabled = false
                    applyShopWebViewColorPolicy(settings)
                    // Shopkasse --kas-bg-mid (WebView clears to this between full navigations).
                    setBackgroundColor("#252b23".toColorInt())
                    webViewRef = this
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val host = request?.url?.host?.lowercase() ?: return true
                            return host !in currentAllowedHosts
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            if (!initialWebPaintDone) {
                                isLoading = true
                            }
                            hasError = false
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            isLoading = false
                            initialWebPaintDone = true
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            if (request?.isForMainFrame != true) return
                            if (isCanceledNavigationError(error?.description)) return
                            isLoading = false
                            hasError = true
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onShowFileChooser(
                            webView: WebView?,
                            filePathCallbackParam: ValueCallback<Array<Uri>>?,
                            fileChooserParams: FileChooserParams?
                        ): Boolean {
                            if (filePathCallbackParam == null) return false
                            filePathCallback?.onReceiveValue(null)
                            filePathCallback = filePathCallbackParam
                            return try {
                                val chooserIntent =
                                    fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                        type = "*/*"
                                    }
                                fileChooserLauncher.launch(chooserIntent)
                                true
                            } catch (_: Exception) {
                                filePathCallback?.onReceiveValue(null)
                                filePathCallback = null
                                false
                            }
                        }
                    }
                    setDownloadListener { dlUrl, userAgent, contentDisposition, mimeType, _ ->
                        try {
                            val req = DownloadManager.Request(dlUrl.toUri())
                            val cookies = CookieManager.getInstance().getCookie(dlUrl)
                            if (!cookies.isNullOrBlank()) {
                                req.addRequestHeader("Cookie", cookies)
                            }
                            if (!userAgent.isNullOrBlank()) {
                                req.addRequestHeader("User-Agent", userAgent)
                            }
                            val fileName =
                                URLUtil.guessFileName(dlUrl, contentDisposition, mimeType)
                            req.setTitle(fileName)
                            req.setDescription("Shopkasse Download")
                            req.setNotificationVisibility(
                                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                            )
                            if (context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) != null) {
                                req.setDestinationInExternalFilesDir(
                                    context,
                                    Environment.DIRECTORY_DOWNLOADS,
                                    fileName
                                )
                            } else {
                                req.setDestinationInExternalPublicDir(
                                    Environment.DIRECTORY_DOWNLOADS,
                                    fileName
                                )
                            }
                            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                            val id = dm.enqueue(req)
                            pendingDownloadIdState.longValue = id
                            Toast.makeText(context, "Download gestartet: $fileName", Toast.LENGTH_SHORT).show()
                        } catch (_: Exception) {
                            try {
                                val i = Intent(Intent.ACTION_VIEW, dlUrl.toUri())
                                context.startActivity(i)
                            } catch (_: Exception) {
                                Toast.makeText(context, "Download fehlgeschlagen.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    loadUrl(configuredUrl)
                }
            },
            onRelease = { webView ->
                webView.destroy()
            }
        )

        // Loading Overlay
        if ((isLoading && !initialWebPaintDone) || hasError) {
            Box(
                modifier = Modifier.fillMaxSize().background(portalBg), // Opaque background to hide WebView errors
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (hasError) {
                        Text(
                            text = "Shopsystem nicht erreichbar!",
                            color = Color.White,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Text(
                            text = "Termux gestartet?",
                            color = adminAccentColor(),
                            modifier = Modifier.padding(bottom = 24.dp)
                        )
                        
                        Button(
                            onClick = { 
                                hasError = false
                                webViewRef?.reload() 
                            },
                            colors = adminButtonColors(),
                            modifier = Modifier.fillMaxWidth(0.6f)
                        ) {
                            Text("Erneut versuchen")
                        }
                        
                        Button(
                            onClick = { showGoHomeConfirmDialog = true },
                            colors = adminButtonColors(),
                            modifier = Modifier.fillMaxWidth(0.6f).padding(top = 12.dp)
                        ) {
                            Text("Zurück")
                        }
                    } else {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
            }
        }

        // Hidden admin trigger zone: hold 5s in bottom-right corner.
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(84.dp)
                .pointerInput(adminPinRef.value) {
                    awaitEachGesture {
                        awaitFirstDown()
                        val timer = withTimeoutOrNull(5000L) {
                            waitForUpOrCancellation()
                        }
                        if (timer == null) {
                            if (adminPinRef.value.isNullOrBlank()) {
                                showPinSetupDialog = true
                            } else {
                                showPinDialog = true
                            }
                        }
                    }
                }
        )

        // --- Dialogs ---
        
        if (showPinSetupDialog) {
            AdminPinSetupDialog(
                onDismiss = { showPinSetupDialog = false },
                onPinSet = { pin ->
                    saveAdminPin(context, pin)
                    savedAdminPin = pin
                    showPinSetupDialog = false
                    showAdminMenu = true
                }
            )
        }

        if (showPinDialog) {
            AdminPinEntryDialog(
                savedPin = savedAdminPin ?: "",
                onDismiss = { showPinDialog = false },
                onSuccess = {
                    showPinDialog = false
                    showAdminMenu = true
                }
            )
        }

        if (showPinChangeDialog) {
            AdminPinChangeDialog(
                currentSavedPin = savedAdminPin ?: "",
                onDismiss = { showPinChangeDialog = false },
                onPinChanged = { newPin ->
                    saveAdminPin(context, newPin)
                    savedAdminPin = newPin
                    showPinChangeDialog = false
                }
            )
        }

        if (showAdminMenu) {
            AdminMenuDialog(
                onDismiss = { showAdminMenu = false },
                onReload = { webViewRef?.reload() },
                onChangeUrl = { showServerUrlDialog = true },
                onMinimize = { showMinimizeConfirmDialog = true },
                onGoHome = { showGoHomeConfirmDialog = true },
                onChangePin = { showPinChangeDialog = true },
                onExit = { showExitConfirmDialog = true }
            )
        }

        if (showServerUrlDialog) {
            ServerUrlDialog(
                currentUrl = configuredUrl,
                defaultUrl = url,
                onDismiss = { showServerUrlDialog = false },
                onSave = { newUrl ->
                    saveServerUrl(context, newUrl)
                    configuredUrl = newUrl
                    showServerUrlDialog = false
                    hasError = false
                    retryTickState.intValue = 0
                    webViewRef?.loadUrl(newUrl)
                }
            )
        }

        if (showExitConfirmDialog) {
            KioskActionConfirmDialog(
                title = "App wirklich beenden?",
                text = "Zum Schutz vor Fehlbedienung muss das Beenden zusätzlich bestätigt werden.",
                adminPin = savedAdminPin,
                onDismiss = { showExitConfirmDialog = false },
                onAction = onExit
            )
        }

        if (showMinimizeConfirmDialog) {
            KioskActionConfirmDialog(
                title = "App minimieren?",
                text = "Das Verlassen des Kiosk-Vordergrunds ist nur nach zusätzlicher Bestätigung erlaubt.",
                adminPin = savedAdminPin,
                onDismiss = { showMinimizeConfirmDialog = false },
                onAction = onMinimize
            )
        }

        if (showGoHomeConfirmDialog) {
            KioskActionConfirmDialog(
                title = "Kiosk-Modus verlassen?",
                text = "Möchten Sie den geschützten Bereich verlassen und zum Startbildschirm zurückkehren?",
                adminPin = savedAdminPin,
                onDismiss = { showGoHomeConfirmDialog = false },
                onAction = onGoHome
            )
        }

        if (showDownloadActionDialog) {
            DownloadActionDialog(
                fileName = downloadActionFileName,
                onDismiss = {
                    showDownloadActionDialog = false
                    pendingDownloadSaveSource = null
                },
                onSaveLocally = {
                    showDownloadActionDialog = false
                    saveDownloadToLauncher.launch(
                        Pair(mimeTypeForFileName(downloadActionFileName), downloadActionFileName)
                    )
                },
                onShare = {
                    showDownloadActionDialog = false
                    onShareFile()
                    shareFile(context, pendingDownloadSaveSource, downloadActionFileName)
                    pendingDownloadSaveSource = null
                }
            )
        }
    }
}

// --- Specialized UI Components ---

@Composable
fun AdminPinSetupDialog(onDismiss: () -> Unit, onPinSet: (String) -> Unit) {
    val p1 = remember { mutableStateOf("") }
    val p2 = remember { mutableStateOf("") }
    val errorMsg = remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Admin-PIN festlegen") },
        text = {
            Column {
                OutlinedTextField(
                    value = p1.value,
                    onValueChange = { p1.value = it.filter(Char::isDigit).take(8) },
                    label = { Text("Neuer PIN (mind. 4 Ziffern)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = p2.value,
                    onValueChange = { p2.value = it.filter(Char::isDigit).take(8) },
                    label = { Text("PIN wiederholen") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                if (errorMsg.value.isNotBlank()) {
                    Text(text = errorMsg.value, color = Color(0xFFB00020), modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                when {
                    p1.value.length < 4 -> errorMsg.value = "PIN muss mindestens 4 Ziffern haben."
                    p1.value != p2.value -> errorMsg.value = "PINs stimmen nicht überein."
                    else -> onPinSet(p1.value)
                }
            }, colors = adminButtonColors()) { Text("Speichern") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen", color = adminAccentColor()) }
        }
    )
}

@Composable
fun AdminPinEntryDialog(savedPin: String, onDismiss: () -> Unit, onSuccess: () -> Unit) {
    val input = remember { mutableStateOf("") }
    val hasError = remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Admin-PIN") },
        text = {
            Column {
                OutlinedTextField(
                    value = input.value,
                    onValueChange = { input.value = it.filter(Char::isDigit).take(8) },
                    label = { Text("PIN eingeben") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth()
                )
                if (hasError.value) {
                    Text(text = "PIN falsch.", color = Color(0xFFB00020), modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (input.value == savedPin) onSuccess() else hasError.value = true
            }, colors = adminButtonColors()) { Text("Öffnen") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen", color = adminAccentColor()) }
        }
    )
}

@Composable
fun AdminPinChangeDialog(currentSavedPin: String, onDismiss: () -> Unit, onPinChanged: (String) -> Unit) {
    val oldInput = remember { mutableStateOf("") }
    val n1 = remember { mutableStateOf("") }
    val n2 = remember { mutableStateOf("") }
    val errorMsg = remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("PIN ändern") },
        text = {
            Column {
                OutlinedTextField(
                    value = oldInput.value,
                    onValueChange = { oldInput.value = it.filter(Char::isDigit).take(8) },
                    label = { Text("Alter PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = n1.value,
                    onValueChange = { n1.value = it.filter(Char::isDigit).take(8) },
                    label = { Text("Neuer PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = n2.value,
                    onValueChange = { n2.value = it.filter(Char::isDigit).take(8) },
                    label = { Text("Neuen PIN wiederholen") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                if (errorMsg.value.isNotBlank()) {
                    Text(text = errorMsg.value, color = Color(0xFFB00020), modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                when {
                    oldInput.value != currentSavedPin -> errorMsg.value = "Alter PIN ist falsch."
                    n1.value.length < 4 -> errorMsg.value = "Neuer PIN zu kurz."
                    n1.value != n2.value -> errorMsg.value = "Neue PINs ungleich."
                    else -> onPinChanged(n1.value)
                }
            }, colors = adminButtonColors()) { Text("Speichern") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen", color = adminAccentColor()) }
        }
    )
}

@Composable
fun AdminMenuDialog(
    onDismiss: () -> Unit,
    onReload: () -> Unit,
    onChangeUrl: () -> Unit,
    onMinimize: () -> Unit,
    onGoHome: () -> Unit,
    onChangePin: () -> Unit,
    onExit: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Admin-Bereich") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                AdminMenuButton("Server neu laden", onClick = { onDismiss(); onReload() })
                AdminMenuButton("Server-Adresse ändern", onClick = { onDismiss(); onChangeUrl() })
                AdminMenuButton("Minimieren", onClick = { onDismiss(); onMinimize() })
                AdminMenuButton("App verlassen", onClick = { onDismiss(); onGoHome() })
                AdminMenuButton("PIN ändern", onClick = { onDismiss(); onChangePin() })
                AdminMenuButton("App beenden", isDanger = true, onClick = { onDismiss(); onExit() })
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Schließen", color = adminAccentColor()) }
        }
    )
}

@Composable
fun AdminMenuButton(text: String, isDanger: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = if (isDanger) adminDangerButtonColors() else adminButtonColors(),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    ) { Text(text) }
}

@Composable
fun ServerUrlDialog(currentUrl: String, defaultUrl: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var input by remember { mutableStateOf(currentUrl) }
    var testInfo by remember { mutableStateOf("") }
    var isTesting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Server-Adresse") },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.trim() },
                    label = { Text("URL des Webdienstes") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        val norm = normalizeServerUrl(input) ?: return@Button
                        isTesting = true
                        testInfo = "Verbindung wird geprüft..."
                        scope.launch {
                            val ok = isServerReachable(norm)
                            isTesting = false
                            testInfo = if (ok) "Verbindung erfolgreich." else "Server nicht erreichbar."
                        }
                    },
                    enabled = !isTesting,
                    colors = adminButtonColors(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text(if (isTesting) "Prüfe..." else "Verbindung testen") }
                
                TextButton(onClick = { input = defaultUrl }) {
                    Text("Auf Standard zurücksetzen", color = adminAccentColor())
                }
                
                if (testInfo.isNotBlank()) {
                    Text(
                        text = testInfo,
                        color = if (testInfo.contains("erfolgreich")) Color(0xFF2F6D2C) else adminAccentColor(),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val norm = normalizeServerUrl(input)
                onSave(norm ?: currentUrl)
            }, colors = adminButtonColors()) { Text("Speichern") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen", color = adminAccentColor()) }
        }
    )
}

@Composable
fun KioskActionConfirmDialog(
    title: String,
    text: String,
    adminPin: String?,
    onDismiss: () -> Unit,
    onAction: () -> Unit
) {
    val showPinEntry = remember { mutableStateOf(false) }

    if (showPinEntry.value) {
        AdminPinEntryDialog(savedPin = adminPin ?: "", onDismiss = { showPinEntry.value = false }, onSuccess = onAction)
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = {
                Button(onClick = {
                    if (adminPin.isNullOrBlank()) onAction() else showPinEntry.value = true
                }, colors = adminButtonColors()) { Text("Weiter") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Abbrechen", color = adminAccentColor()) }
            }
        )
    }
}

@Composable
fun DownloadActionDialog(fileName: String, onDismiss: () -> Unit, onSaveLocally: () -> Unit, onShare: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Datei heruntergeladen") },
        text = {
            Column {
                Text("Was möchten Sie mit '$fileName' tun?")
            }
        },
        confirmButton = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onSaveLocally, modifier = Modifier.fillMaxWidth(), colors = adminButtonColors()) {
                    Text("Auf Gerät speichern")
                }
                Button(onClick = onShare, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), colors = adminButtonColors()) {
                    Text("Per E-Mail senden")
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("Abbrechen", color = adminAccentColor())
                }
            }
        },
        dismissButton = null
    )
}

// --- Helper Functions & Theming ---

@Composable fun adminButtonColors() = ButtonDefaults.buttonColors(containerColor = Color(0xFF2F6D2C), contentColor = Color.White)
@Composable fun adminDangerButtonColors() = ButtonDefaults.buttonColors(containerColor = Color(0xFF8A1E1E), contentColor = Color.White)
@Composable fun adminAccentColor() = Color(0xFFE8BC2E)

private fun shareFile(context: Context, fileUri: Uri?, fileName: String) {
    if (fileUri == null) return
    try {
        val file = java.io.File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (!file.exists()) return
        val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeTypeForFileName(fileName)
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_SUBJECT, "Shopkasse - Dateiversand")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Datei senden via..."))
    } catch (_: Exception) { }
}

private fun loadAdminPin(context: Context): String? {
    val prefs = context.getSharedPreferences("shopkasse_admin", Context.MODE_PRIVATE)
    return prefs.getString("admin_pin", null)?.trim()?.takeIf { it.isNotBlank() }
}

private fun saveAdminPin(context: Context, pin: String) {
    context.getSharedPreferences("shopkasse_admin", Context.MODE_PRIVATE).edit { putString("admin_pin", pin) }
}

private fun loadServerUrl(context: Context, defaultUrl: String): String {
    val saved = context.getSharedPreferences("shopkasse_admin", Context.MODE_PRIVATE).getString("server_url", null)
    return normalizeServerUrl(saved ?: "") ?: defaultUrl
}

private fun saveServerUrl(context: Context, url: String) {
    context.getSharedPreferences("shopkasse_admin", Context.MODE_PRIVATE).edit { putString("server_url", url) }
}

private fun normalizeServerUrl(raw: String): String? {
    val input = raw.trim()
    if (input.isBlank()) return null
    val candidate = if ("://" in input) input else "http://$input"
    return try {
        val parsed = candidate.toUri()
        if (parsed.scheme?.lowercase() in setOf("http", "https") && !parsed.host.isNullOrBlank()) candidate else null
    } catch (_: Exception) { null }
}

private suspend fun isServerReachable(url: String): Boolean = withContext(Dispatchers.IO) {
    try {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 2500
        con.readTimeout = 2500
        con.responseCode in 200..499
    } catch (_: Exception) { false }
}
