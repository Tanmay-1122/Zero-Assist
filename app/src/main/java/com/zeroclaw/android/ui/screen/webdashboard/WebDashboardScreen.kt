// Copyright 2026 Zero-Assist Community, MIT License

package com.zeroclaw.android.ui.screen.webdashboard

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Full-screen embedded WebView that loads the ZeroClaw web dashboard.
 *
 * The gateway must be running with web_enabled=true for content to load.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebDashboardScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    val backgroundColor = MaterialTheme.colorScheme.background.toArgb()

    BackHandler {
        val w = webView
        if (w != null && w.canGoBack()) {
            w.goBack()
        } else {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Web Dashboard") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams =
                            ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        webViewClient = WebViewClient()
                        webChromeClient = WebChromeClient()
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            allowFileAccess = true
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                        }
                        setBackgroundColor(backgroundColor)
                        webView = this
                        loadUrl("http://127.0.0.1:42617/")
                    }
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                destroy()
            }
            webView = null
        }
    }
}
