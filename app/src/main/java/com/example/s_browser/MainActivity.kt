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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
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
                onMinimize = { moveTaskToBack(true) },
                onOpenTermux = {
                    val launchIntent = packageManager.getLaunchIntentForPackage("com.termux")
                    if (launchIntent != null) {
                        startActivity(launchIntent)
                    } else {
                        Toast.makeText(this, "Termux nicht gefunden.", Toast.LENGTH_SHORT).show()
                    }
                },
                onExit = { finishAffinity() }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
    }

    private fun enterImmersiveMode() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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
    var retryTick by remember { mutableIntStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var showPinDialog by remember { mutableStateOf(false) }
    var showPinSetupDialog by remember { mutableStateOf(false) }
    var showPinChangeDialog by remember { mutableStateOf(false) }
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
    var isOnline by remember { mutableStateOf(true) }
    var lastInteractionMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var savedAdminPin by remember(context) { mutableStateOf(loadAdminPin(context)) }
    var filePathCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var pendingDownloadId by remember { mutableLongStateOf(-1L) }
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

    val allowedHosts = remember { setOf("127.0.0.1", "localhost") }
    val adminPinRef = rememberUpdatedState(newValue = savedAdminPin)

    // Reliable completion check for the most recent app-started download.
    LaunchedEffect(pendingDownloadId) {
        val id = pendingDownloadId
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
                            pendingDownloadId = -1L
                            return@LaunchedEffect
                        }
                        if (status == DownloadManager.STATUS_FAILED) {
                            pendingDownloadId = -1L
                            return@LaunchedEffect
                        }
                    }
                }
            } catch (_: Exception) {
                pendingDownloadId = -1L
                return@LaunchedEffect
            }
            delay(1000)
        }
    }
    // Auto-retry after 3s whenever we are in error state.
    LaunchedEffect(hasError, retryTick) {
        if (hasError && retryTick > 0) {
            webViewRef?.loadUrl(url)
        }
        if (hasError) {
            delay(3000)
            retryTick += 1
        }
    }

    // Connectivity/health poll for visible online/offline indicator.
    LaunchedEffect(url) {
        while (true) {
            isOnline = checkUrlReachable(url)
            delay(5000)
        }
    }

    // Auto-reload on inactivity (5 minutes).
    LaunchedEffect(lastInteractionMs) {
        while (true) {
            delay(30000)
            val idleMs = System.currentTimeMillis() - lastInteractionMs
            if (idleMs >= 5 * 60 * 1000L) {
                webViewRef?.reload()
                lastInteractionMs = System.currentTimeMillis()
            }
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
                                lastInteractionMs = System.currentTimeMillis()
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
                            isOnline = false
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
                            pendingDownloadId = id
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
                    loadUrl(url)
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

        // Online/offline badge.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 10.dp, end = 10.dp)
                .background(
                    color = if (isOnline) Color(0xCC1F7A1F) else Color(0xCC8A1E1E),
                    shape = RoundedCornerShape(10.dp)
                )
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = if (isOnline) "Running" else "Offline",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall
            )
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
                    TextButton(
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
                        }
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
                    ) { Text("Abbrechen") }
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
                    TextButton(
                        onClick = {
                            if (pinInput == (savedAdminPin ?: "")) {
                                showPinDialog = false
                                showAdminMenu = true
                                pinInput = ""
                                pinError = false
                            } else {
                                pinError = true
                            }
                        }
                    ) { Text("Öffnen") }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showPinDialog = false
                            pinInput = ""
                            pinError = false
                        }
                    ) { Text("Abbrechen") }
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
                    TextButton(
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
                        }
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
                    ) { Text("Abbrechen") }
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
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Server neu laden") }
                        Button(
                            onClick = {
                                showAdminMenu = false
                                onMinimize()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        ) { Text("Minimieren") }
                        Button(
                            onClick = {
                                showAdminMenu = false
                                onOpenTermux()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        ) { Text("Zu Termux wechseln") }
                        Button(
                            onClick = {
                                showAdminMenu = false
                                showPinChangeDialog = true
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        ) { Text("PIN ändern") }
                        Button(
                            onClick = {
                                showAdminMenu = false
                                onExit()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        ) { Text("App beenden") }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAdminMenu = false }) { Text("Schließen") }
                },
                dismissButton = {}
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

private suspend fun checkUrlReachable(url: String): Boolean = withContext(Dispatchers.IO) {
    return@withContext try {
        val con = URL(url).openConnection() as HttpURLConnection
        con.requestMethod = "GET"
        con.connectTimeout = 1500
        con.readTimeout = 1500
        con.instanceFollowRedirects = true
        con.connect()
        val code = con.responseCode
        con.disconnect()
        code in 200..399
    } catch (_: Exception) {
        false
    }
}