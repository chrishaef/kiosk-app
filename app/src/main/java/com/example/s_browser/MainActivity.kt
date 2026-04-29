package com.example.s_browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.ValueCallback
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
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

        // Kiosk-style: Back should not leave the app accidentally.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    Toast.makeText(
                        this@MainActivity,
                        "Zurück ist deaktiviert.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )

        setContent {
            ShopWebView(
                url = "http://127.0.0.1:8000",
                onMinimize = {
                    disableLockTaskMode()
                    moveTaskToBack(true)
                },
                onOpenTermux = {
                    disableLockTaskMode()
                    val launchIntent = packageManager.getLaunchIntentForPackage("com.termux")
                    if (launchIntent != null) {
                        startActivity(launchIntent)
                    } else {
                        Toast.makeText(this, "Termux nicht gefunden.", Toast.LENGTH_SHORT).show()
                    }
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

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun ShopWebView(
    url: String,
    modifier: Modifier = Modifier,
    onMinimize: () -> Unit,
    onOpenTermux: () -> Unit,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    /** After the first successful paint, we stop the full-screen loader on navigations (avoids flicker). */
    var initialWebPaintDone by remember { mutableStateOf(false) }
    var hasError by remember { mutableStateOf(false) }
    val retryTickState = remember { mutableIntStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var showPinDialog by remember { mutableStateOf(false) }
    var showPinSetupDialog by remember { mutableStateOf(false) }
    var showPinChangeDialog by remember { mutableStateOf(false) }
    var showExitConfirmDialog by remember { mutableStateOf(false) }
    var showExitPinDialog by remember { mutableStateOf(false) }
    var showMinimizeConfirmDialog by remember { mutableStateOf(false) }
    var showMinimizePinDialog by remember { mutableStateOf(false) }
    var showServerUrlDialog by remember { mutableStateOf(false) }
    var showAdminMenu by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }
    var pinSetupInput by remember { mutableStateOf("") }
    var pinSetupInput2 by remember { mutableStateOf("") }
    var pinSetupError by remember { mutableStateOf("") }
    var pinOldInput by remember { mutableStateOf("") }
    var pinNewInput by remember { mutableStateOf("") }
    var pinNewInput2 by remember { mutableStateOf("") }
    var pinChangeError by remember { mutableStateOf("") }
    var exitPinInput by remember { mutableStateOf("") }
    var exitPinError by remember { mutableStateOf(false) }
    var minimizePinInput by remember { mutableStateOf("") }
    var minimizePinError by remember { mutableStateOf(false) }
    var serverUrlInput by remember { mutableStateOf("") }
    var serverUrlError by remember { mutableStateOf("") }
    var serverUrlTestInfo by remember { mutableStateOf("") }
    var serverUrlTestRunning by remember { mutableStateOf(false) }
    val lastInteractionMsState = remember { mutableLongStateOf(System.currentTimeMillis()) }
    var savedAdminPin by remember(context) { mutableStateOf(loadAdminPin(context)) }
    var configuredUrl by remember(context) { mutableStateOf(loadServerUrl(context, url)) }
    var filePathCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val pendingDownloadIdState = remember { mutableLongStateOf(-1L) }
    var downloadedUri by remember { mutableStateOf<Uri?>(null) }
    var downloadedName by remember { mutableStateOf("") }
    var showShareDialog by remember { mutableStateOf(false) }

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
    val scope = rememberCoroutineScope()

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
                            downloadedUri = dm.getUriForDownloadedFile(id)
                            downloadedName =
                                if (titleIdx >= 0) cursor.getString(titleIdx) ?: "Download" else "Download"
                            if (downloadedUri != null) {
                                showShareDialog = true
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

    // Auto-reload on inactivity (5 minutes).
    LaunchedEffect(lastInteractionMsState.longValue) {
        while (true) {
            delay(30000)
            val idleMs = System.currentTimeMillis() - lastInteractionMsState.longValue
            if (idleMs >= 5 * 60 * 1000L) {
                webViewRef?.reload()
                lastInteractionMsState.longValue = System.currentTimeMillis()
            }
        }
    }

    // Match shop portal so any gap between WebView paints is not pure black.
    val portalBg = Color("#252b23".toColorInt())
    val adminButtonColors = ButtonDefaults.buttonColors(
        containerColor = Color(0xFF2F6D2C),
        contentColor = Color.White
    )
    val adminDangerButtonColors = ButtonDefaults.buttonColors(
        containerColor = Color(0xFF8A1E1E),
        contentColor = Color.White
    )
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
                    // Prevent native long-press link previews/context badges from
                    // stealing kiosk long-press gestures (easter egg/admin trigger).
                    isLongClickable = false
                    setOnLongClickListener { true }
                    isHapticFeedbackEnabled = false
                    applyShopWebViewColorPolicy(settings)
                    // Shopkasse --kas-bg-mid (WebView clears to this between full navigations).
                    setBackgroundColor("#252b23".toColorInt())
                    // Do not call performClick() here: on WebView it can fire an extra accessibility
                    // "click" and accidentally activate header links (e.g. "/" → admin session cleared).
                    setOnTouchListener { _, e ->
                        when (e.actionMasked) {
                            MotionEvent.ACTION_DOWN,
                            MotionEvent.ACTION_MOVE,
                            MotionEvent.ACTION_UP,
                            MotionEvent.ACTION_POINTER_DOWN -> {
                                lastInteractionMsState.longValue = System.currentTimeMillis()
                            }
                        }
                        false
                    }
                    webViewRef = this
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val host = request?.url?.host?.lowercase() ?: return true
                            return host !in allowedHosts
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            // Only block the UI on the first load; in-app links would otherwise flash the overlay.
                            if (!initialWebPaintDone) {
                                isLoading = true
                            }
                            hasError = false
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            isLoading = false
                            initialWebPaintDone = true
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onReceivedError(
                            view: WebView?,
                            errorCode: Int,
                            description: String?,
                            failingUrl: String?
                        ) {
                            if (isCanceledNavigationError(description)) return
                            isLoading = false
                            hasError = true
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
                            req.setDestinationInExternalPublicDir(
                                Environment.DIRECTORY_DOWNLOADS,
                                fileName
                            )
                            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                            val id = dm.enqueue(req)
                            pendingDownloadIdState.longValue = id
                            Toast.makeText(
                                context,
                                "Download gestartet: $fileName",
                                Toast.LENGTH_SHORT
                            ).show()
                        } catch (_: Exception) {
                            // Fallback: open in external handler/browser.
                            try {
                                val i = Intent(Intent.ACTION_VIEW, dlUrl.toUri())
                                context.startActivity(i)
                            } catch (_: Exception) {
                                Toast.makeText(
                                    context,
                                    "Download konnte nicht gestartet werden.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                    loadUrl(configuredUrl)
                }
            }
        )

        if ((isLoading && !initialWebPaintDone) || hasError) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCC000000)),
                contentAlignment = Alignment.Center
            ) {
                if (hasError) {
                    Text(
                        text = "Verbindung wird wiederhergestellt...",
                        color = Color.White
                    )
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }

        // Hidden admin trigger zone: hold 5s in bottom-right corner (View + Handler avoids Compose pointer APIs).
        AndroidView(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(84.dp),
            factory = { ctx ->
                val overlay = View(ctx)
                overlay.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                val handler = Handler(Looper.getMainLooper())
                var pendingLongPress: Runnable? = null
                overlay.setOnTouchListener { v, ev ->
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            pendingLongPress?.let(handler::removeCallbacks)
                            pendingLongPress = Runnable {
                                if (adminPinRef.value.isNullOrBlank()) {
                                    showPinSetupDialog = true
                                } else {
                                    showPinDialog = true
                                }
                            }
                            pendingLongPress?.let { handler.postDelayed(it, 5000L) }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            pendingLongPress?.let(handler::removeCallbacks)
                            pendingLongPress = null
                        }
                    }
                    if (ev.actionMasked == MotionEvent.ACTION_UP) {
                        v.performClick()
                    }
                    false
                }
                overlay
            }
        )

        if (showPinSetupDialog) {
            AlertDialog(
                onDismissRequest = {
                    showPinSetupDialog = false
                    pinSetupInput = ""
                    pinSetupInput2 = ""
                    pinSetupError = ""
                },
                title = { Text("Admin-PIN festlegen") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = pinSetupInput,
                            onValueChange = { pinSetupInput = it.filter(Char::isDigit).take(8) },
                            label = { Text("Neuer PIN (mind. 4 Ziffern)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword,
                                imeAction = ImeAction.Next
                            ),
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = pinSetupInput2,
                            onValueChange = { pinSetupInput2 = it.filter(Char::isDigit).take(8) },
                            label = { Text("PIN wiederholen") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword,
                                imeAction = ImeAction.Done
                            ),
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        )
                        if (pinSetupError.isNotBlank()) {
                            Text(
                                text = pinSetupError,
                                color = Color(0xFFB00020),
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val p1 = pinSetupInput.trim()
                            val p2 = pinSetupInput2.trim()
                            when {
                                p1.length < 4 -> pinSetupError = "PIN muss mindestens 4 Ziffern haben."
                                p1 != p2 -> pinSetupError = "PINs stimmen nicht überein."
                                else -> {
                                    saveAdminPin(context, p1)
                                    savedAdminPin = p1
                                    showPinSetupDialog = false
                                    showAdminMenu = true
                                    pinSetupInput = ""
                                    pinSetupInput2 = ""
                                    pinSetupError = ""
                                }
                            }
                        },
                        colors = adminButtonColors
                    ) { Text("Speichern") }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showPinSetupDialog = false
                            pinSetupInput = ""
                            pinSetupInput2 = ""
                            pinSetupError = ""
                        }
                    ) { Text("Abbrechen", color = Color(0xFFE8BC2E)) }
                }
            )
        }

        if (showPinDialog) {
            AlertDialog(
                onDismissRequest = {
                    showPinDialog = false
                    pinInput = ""
                    pinError = false
                },
                title = { Text("Admin-PIN") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = pinInput,
                            onValueChange = { pinInput = it.filter(Char::isDigit).take(8) },
                            label = { Text("PIN eingeben") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword,
                                imeAction = ImeAction.Done
                            ),
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (pinError) {
                            Text(
                                text = "PIN falsch.",
                                color = Color(0xFFB00020),
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (pinInput == (savedAdminPin ?: "")) {
                                showPinDialog = false
                                showAdminMenu = true
                                pinInput = ""
                                pinError = false
                            } else {
                                pinError = true
                            }
                        },
                        colors = adminButtonColors
                    ) { Text("Öffnen") }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showPinDialog = false
                            pinInput = ""
                            pinError = false
                        }
                    ) { Text("Abbrechen", color = Color(0xFFE8BC2E)) }
                }
            )
        }

        if (showPinChangeDialog) {
            AlertDialog(
                onDismissRequest = {
                    showPinChangeDialog = false
                    pinOldInput = ""
                    pinNewInput = ""
                    pinNewInput2 = ""
                    pinChangeError = ""
                },
                title = { Text("PIN ändern") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = pinOldInput,
                            onValueChange = { pinOldInput = it.filter(Char::isDigit).take(8) },
                            label = { Text("Alter PIN") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword,
                                imeAction = ImeAction.Next
                            ),
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = pinNewInput,
                            onValueChange = { pinNewInput = it.filter(Char::isDigit).take(8) },
                            label = { Text("Neuer PIN (mind. 4 Ziffern)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword,
                                imeAction = ImeAction.Next
                            ),
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        )
                        OutlinedTextField(
                            value = pinNewInput2,
                            onValueChange = { pinNewInput2 = it.filter(Char::isDigit).take(8) },
                            label = { Text("Neuen PIN wiederholen") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword,
                                imeAction = ImeAction.Done
                            ),
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        )
                        if (pinChangeError.isNotBlank()) {
                            Text(
                                text = pinChangeError,
                                color = Color(0xFFB00020),
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val oldPin = pinOldInput.trim()
                            val p1 = pinNewInput.trim()
                            val p2 = pinNewInput2.trim()
                            when {
                                oldPin != (savedAdminPin ?: "") -> pinChangeError = "Alter PIN ist falsch."
                                p1.length < 4 -> pinChangeError = "Neuer PIN muss mindestens 4 Ziffern haben."
                                p1 != p2 -> pinChangeError = "Neue PINs stimmen nicht überein."
                                else -> {
                                    saveAdminPin(context, p1)
                                    savedAdminPin = p1
                                    showPinChangeDialog = false
                                    pinOldInput = ""
                                    pinNewInput = ""
                                    pinNewInput2 = ""
                                    pinChangeError = ""
                                }
                            }
                        },
                        colors = adminButtonColors
                    ) { Text("Speichern") }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showPinChangeDialog = false
                            pinOldInput = ""
                            pinNewInput = ""
                            pinNewInput2 = ""
                            pinChangeError = ""
                        }
                    ) { Text("Abbrechen", color = Color(0xFFE8BC2E)) }
                }
            )
        }

        if (showAdminMenu) {
            AlertDialog(
                onDismissRequest = { showAdminMenu = false },
                title = { Text("Admin-Bereich") },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                showAdminMenu = false
                                webViewRef?.reload()
                            },
                            colors = adminButtonColors,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Server neu laden") }
                        Button(
                            onClick = {
                                showAdminMenu = false
                                serverUrlInput = configuredUrl
                                serverUrlError = ""
                                showServerUrlDialog = true
                            },
                            colors = adminButtonColors,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        ) { Text("Server-Adresse ändern") }
                        Button(
                            onClick = {
                                showAdminMenu = false
                                showMinimizeConfirmDialog = true
                            },
                            colors = adminButtonColors,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        ) { Text("Minimieren") }
                        Button(
                            onClick = {
                                showAdminMenu = false
                                onOpenTermux()
                            },
                            colors = adminButtonColors,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        ) { Text("Zu Termux wechseln") }
                        Button(
                            onClick = {
                                showAdminMenu = false
                                showPinChangeDialog = true
                            },
                            colors = adminButtonColors,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        ) { Text("PIN ändern") }
                        Button(
                            onClick = {
                                showAdminMenu = false
                                showExitConfirmDialog = true
                            },
                            colors = adminDangerButtonColors,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        ) { Text("App beenden") }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAdminMenu = false }) {
                        Text("Schließen", color = Color(0xFFE8BC2E))
                    }
                },
                dismissButton = {}
            )
        }

        if (showServerUrlDialog) {
            AlertDialog(
                onDismissRequest = {
                    showServerUrlDialog = false
                    serverUrlError = ""
                    serverUrlTestInfo = ""
                    serverUrlTestRunning = false
                },
                title = { Text("Server-Adresse") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = serverUrlInput,
                            onValueChange = { serverUrlInput = it.trim() },
                            label = { Text("URL des Webdienstes") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Done
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                val normalized = normalizeServerUrl(serverUrlInput)
                                if (normalized == null) {
                                    serverUrlError = "Ungültige URL. Beispiel: http://127.0.0.1:8000"
                                    serverUrlTestInfo = ""
                                    return@Button
                                }
                                serverUrlError = ""
                                serverUrlTestRunning = true
                                serverUrlTestInfo = "Verbindung wird geprüft..."
                                scope.launch {
                                    val ok = isServerReachable(normalized)
                                    serverUrlTestRunning = false
                                    serverUrlTestInfo = if (ok) {
                                        "Verbindung erfolgreich."
                                    } else {
                                        "Keine Verbindung zum Server."
                                    }
                                }
                            },
                            enabled = !serverUrlTestRunning,
                            colors = adminButtonColors,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        ) { Text(if (serverUrlTestRunning) "Prüfe..." else "Verbindung testen") }
                        TextButton(
                            onClick = {
                                serverUrlInput = url
                                serverUrlError = ""
                                serverUrlTestInfo = "Standard gesetzt: $url"
                            }
                        ) { Text("Auf Standard zurücksetzen", color = Color(0xFFE8BC2E)) }
                        if (serverUrlError.isNotBlank()) {
                            Text(
                                text = serverUrlError,
                                color = Color(0xFFB00020),
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                        if (serverUrlTestInfo.isNotBlank()) {
                            Text(
                                text = serverUrlTestInfo,
                                color = if (serverUrlTestInfo.contains("erfolgreich")) Color(0xFF2F6D2C) else Color(0xFFE8BC2E),
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val normalized = normalizeServerUrl(serverUrlInput)
                            if (normalized == null) {
                                serverUrlError = "Ungültige URL. Beispiel: http://127.0.0.1:8000"
                            } else {
                                saveServerUrl(context, normalized)
                                configuredUrl = normalized
                                showServerUrlDialog = false
                                serverUrlError = ""
                                serverUrlTestInfo = ""
                                serverUrlTestRunning = false
                                hasError = false
                                retryTickState.intValue = 0
                                webViewRef?.loadUrl(normalized)
                            }
                        },
                        colors = adminButtonColors
                    ) { Text("Speichern") }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showServerUrlDialog = false
                            serverUrlError = ""
                            serverUrlTestInfo = ""
                            serverUrlTestRunning = false
                        }
                    ) { Text("Abbrechen", color = Color(0xFFE8BC2E)) }
                }
            )
        }

        if (showExitConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showExitConfirmDialog = false },
                title = { Text("App wirklich beenden?") },
                text = {
                    Text("Zum Schutz vor Fehlbedienung muss das Beenden zusätzlich bestätigt werden.")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showExitConfirmDialog = false
                            if (savedAdminPin.isNullOrBlank()) {
                                onExit()
                            } else {
                                exitPinInput = ""
                                exitPinError = false
                                showExitPinDialog = true
                            }
                        },
                        colors = adminButtonColors
                    ) { Text("Weiter") }
                },
                dismissButton = {
                    TextButton(onClick = { showExitConfirmDialog = false }) {
                        Text("Abbrechen", color = Color(0xFFE8BC2E))
                    }
                }
            )
        }

        if (showExitPinDialog) {
            AlertDialog(
                onDismissRequest = {
                    showExitPinDialog = false
                    exitPinInput = ""
                    exitPinError = false
                },
                title = { Text("Admin-PIN bestätigen") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = exitPinInput,
                            onValueChange = { exitPinInput = it.filter(Char::isDigit).take(8) },
                            label = { Text("PIN zum Beenden") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword,
                                imeAction = ImeAction.Done
                            ),
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (exitPinError) {
                            Text(
                                text = "PIN falsch.",
                                color = Color(0xFFB00020),
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (exitPinInput == (savedAdminPin ?: "")) {
                                showExitPinDialog = false
                                exitPinInput = ""
                                exitPinError = false
                                onExit()
                            } else {
                                exitPinError = true
                            }
                        },
                        colors = adminDangerButtonColors
                    ) { Text("App beenden") }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showExitPinDialog = false
                            exitPinInput = ""
                            exitPinError = false
                        }
                    ) { Text("Abbrechen", color = Color(0xFFE8BC2E)) }
                }
            )
        }

        if (showMinimizeConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showMinimizeConfirmDialog = false },
                title = { Text("App minimieren?") },
                text = {
                    Text("Das Verlassen des Kiosk-Vordergrunds ist nur nach zusätzlicher Bestätigung erlaubt.")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showMinimizeConfirmDialog = false
                            if (savedAdminPin.isNullOrBlank()) {
                                onMinimize()
                            } else {
                                minimizePinInput = ""
                                minimizePinError = false
                                showMinimizePinDialog = true
                            }
                        },
                        colors = adminButtonColors
                    ) { Text("Weiter") }
                },
                dismissButton = {
                    TextButton(onClick = { showMinimizeConfirmDialog = false }) {
                        Text("Abbrechen", color = Color(0xFFE8BC2E))
                    }
                }
            )
        }

        if (showMinimizePinDialog) {
            AlertDialog(
                onDismissRequest = {
                    showMinimizePinDialog = false
                    minimizePinInput = ""
                    minimizePinError = false
                },
                title = { Text("Admin-PIN bestätigen") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = minimizePinInput,
                            onValueChange = { minimizePinInput = it.filter(Char::isDigit).take(8) },
                            label = { Text("PIN zum Minimieren") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword,
                                imeAction = ImeAction.Done
                            ),
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (minimizePinError) {
                            Text(
                                text = "PIN falsch.",
                                color = Color(0xFFB00020),
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (minimizePinInput == (savedAdminPin ?: "")) {
                                showMinimizePinDialog = false
                                minimizePinInput = ""
                                minimizePinError = false
                                onMinimize()
                            } else {
                                minimizePinError = true
                            }
                        },
                        colors = adminButtonColors
                    ) { Text("Minimieren") }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showMinimizePinDialog = false
                            minimizePinInput = ""
                            minimizePinError = false
                        }
                    ) { Text("Abbrechen", color = Color(0xFFE8BC2E)) }
                }
            )
        }

        if (showShareDialog) {
            AlertDialog(
                onDismissRequest = { showShareDialog = false },
                title = { Text("Download abgeschlossen") },
                text = { Text("Datei \"$downloadedName\" per Mail versenden?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showShareDialog = false
                            val uri = downloadedUri
                            if (uri != null) {
                                try {
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "*/*"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        putExtra(Intent.EXTRA_SUBJECT, downloadedName)
                                        putExtra(Intent.EXTRA_EMAIL, arrayOf(""))
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    val emailProbeIntent = Intent(
                                        Intent.ACTION_SENDTO,
                                        "mailto:".toUri()
                                    )
                                    val pm = context.packageManager
                                    val emailActivities = pm.queryIntentActivities(
                                        emailProbeIntent,
                                        PackageManager.MATCH_DEFAULT_ONLY
                                    )
                                    if (emailActivities.isNotEmpty()) {
                                        val targetedIntents = emailActivities.map { ri ->
                                            Intent(sendIntent).apply { `package` = ri.activityInfo.packageName }
                                        }
                                        val primary = targetedIntents.first()
                                        val chooser = Intent.createChooser(primary, "Per E-Mail senden")
                                        if (targetedIntents.size > 1) {
                                            chooser.putExtra(
                                                Intent.EXTRA_INITIAL_INTENTS,
                                                targetedIntents.drop(1).toTypedArray()
                                            )
                                        }
                                        context.startActivity(chooser)
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "Keine Mail-App gefunden.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                } catch (_: Exception) {
                                    Toast.makeText(
                                        context,
                                        "Teilen konnte nicht gestartet werden.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    ) { Text("Per E-Mail senden") }
                },
                dismissButton = {
                    TextButton(onClick = { showShareDialog = false }) { Text("Später") }
                }
            )
        }
    }
}

private fun loadAdminPin(context: Context): String? {
    val prefs = context.getSharedPreferences("shopkasse_admin", Context.MODE_PRIVATE)
    val pin = prefs.getString("admin_pin", null)?.trim()
    return if (pin.isNullOrBlank()) null else pin
}

private fun saveAdminPin(context: Context, pin: String) {
    val prefs = context.getSharedPreferences("shopkasse_admin", Context.MODE_PRIVATE)
    prefs.edit {
        putString("admin_pin", pin)
    }
}

private fun loadServerUrl(context: Context, defaultUrl: String): String {
    val prefs = context.getSharedPreferences("shopkasse_admin", Context.MODE_PRIVATE)
    val saved = prefs.getString("server_url", null)?.trim()
    return normalizeServerUrl(saved ?: "") ?: defaultUrl
}

private fun saveServerUrl(context: Context, url: String) {
    val prefs = context.getSharedPreferences("shopkasse_admin", Context.MODE_PRIVATE)
    prefs.edit {
        putString("server_url", url)
    }
}

private fun normalizeServerUrl(raw: String): String? {
    val input = raw.trim()
    if (input.isBlank()) return null
    val candidate = if ("://" in input) input else "http://$input"
    val parsed = candidate.toUri()
    val scheme = parsed.scheme?.lowercase()
    val host = parsed.host
    if (scheme !in setOf("http", "https") || host.isNullOrBlank()) return null
    return candidate
}

private suspend fun isServerReachable(url: String): Boolean = withContext(Dispatchers.IO) {
    return@withContext try {
        val con = URL(url).openConnection() as HttpURLConnection
        con.requestMethod = "GET"
        con.connectTimeout = 2500
        con.readTimeout = 2500
        con.instanceFollowRedirects = true
        con.connect()
        val code = con.responseCode
        con.disconnect()
        code in 200..499
    } catch (_: Exception) {
        false
    }
}