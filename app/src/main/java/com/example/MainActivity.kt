package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.example.ui.theme.MyApplicationTheme
import com.google.android.gms.ads.*
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

private const val TAG = "CashlyWebView"

class MainActivity : ComponentActivity() {

    private var interstitialAd: InterstitialAd? = null
    private var rewardedAd: RewardedAd? = null

    // Official AdMob Test Ad Unit IDs
    private val testBannerAdUnitId = "ca-app-pub-3940256099942544/6300978111"
    private val testInterstitialAdUnitId = "ca-app-pub-3940256099942544/1033173712"
    private val testRewardedAdUnitId = "ca-app-pub-3940256099942544/5224354917"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize Mobile Ads SDK
        try {
            MobileAds.initialize(this) { status ->
                Log.d(TAG, "AdMob initialization complete: $status")
                loadInterstitialAd()
                loadRewardedAd()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing MobileAds SDK", e)
        }

        // Retrieve saved user dark mode preference (defaults to system if not saved)
        val sharedPrefs = getSharedPreferences("cashly_prefs", Context.MODE_PRIVATE)
        val defaultSystemTheme = resources.configuration.uiMode and 
                android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES

        setContent {
            var isDarkMode by remember { 
                mutableStateOf(sharedPrefs.getBoolean("dark_mode", defaultSystemTheme)) 
            }

            MyApplicationTheme(darkTheme = isDarkMode) {
                MainAppScreen(
                    context = this,
                    isDarkMode = isDarkMode,
                    onToggleDarkMode = {
                        isDarkMode = !isDarkMode
                        sharedPrefs.edit().putBoolean("dark_mode", isDarkMode).apply()
                    },
                    interstitialAd = interstitialAd,
                    rewardedAd = rewardedAd,
                    onReloadInterstitial = { loadInterstitialAd() },
                    onReloadRewarded = { loadRewardedAd() },
                    exitApp = { finish() },
                    bannerAdUnitId = testBannerAdUnitId
                )
            }
        }
    }

    private fun loadInterstitialAd() {
        val adRequest = AdRequest.Builder().build()
        InterstitialAd.load(
            this,
            testInterstitialAdUnitId,
            adRequest,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                    Log.d(TAG, "Interstitial Ad Loaded successfully.")
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialAd = null
                    Log.e(TAG, "Interstitial Ad failed to load: ${error.message}")
                }
            }
        )
    }

    private fun loadRewardedAd() {
        val adRequest = AdRequest.Builder().build()
        RewardedAd.load(
            this,
            testRewardedAdUnitId,
            adRequest,
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                    Log.d(TAG, "Rewarded Ad Loaded successfully.")
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedAd = null
                    Log.e(TAG, "Rewarded Ad failed to load: ${error.message}")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun MainAppScreen(
    context: ComponentActivity,
    isDarkMode: Boolean,
    onToggleDarkMode: () -> Unit,
    interstitialAd: InterstitialAd?,
    rewardedAd: RewardedAd?,
    onReloadInterstitial: () -> Unit,
    onReloadRewarded: () -> Unit,
    exitApp: () -> Unit,
    bannerAdUnitId: String
) {
    var webView: WebView? by remember { mutableStateOf(null) }
    var progress by remember { mutableStateOf(0) }
    var isPageLoading by remember { mutableStateOf(false) }
    var hasError by remember { mutableStateOf(false) }
    
    // Check initial connection
    val isOnline = remember { mutableStateOf(isNetworkConnected(context)) }
    
    // Dialog state
    var showExitConfirm by remember { mutableStateOf(false) }
    var showRewardDialog by remember { mutableStateOf(false) }
    var rewardedTipsRewardText by remember { mutableStateOf("") }

    // Intercept hardware back button to navigate backwards in WebView history
    BackHandler {
        if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            showExitConfirm = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Cashly App",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                },
                actions = {
                    // Refresh Button
                    IconButton(
                        onClick = {
                            hasError = false
                            isOnline.value = isNetworkConnected(context)
                            
                            // Load interstitial ad on refresh as a clean integration point
                            if (interstitialAd != null) {
                                interstitialAd.show(context)
                                onReloadInterstitial()
                            }
                            webView?.reload()
                        },
                        modifier = Modifier.testTag("refresh_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "রিফ্রেশ"
                        )
                    }

                    // Tonal Theme Toggle Switch (Dark Mode)
                    IconButton(
                        onClick = {
                            onToggleDarkMode()
                            // Inject Dark Style CSS directly into WebView
                            injectThemeStyles(webView, !isDarkMode)
                        },
                        modifier = Modifier.testTag("theme_toggle")
                    ) {
                        Icon(
                            painter = painterResource(
                                id = if (isDarkMode) {
                                    android.R.drawable.btn_star_big_on // Sparky star for dark mode
                                } else {
                                    android.R.drawable.btn_star_big_off // Off star for light mode
                                }
                            ),
                            contentDescription = "ডার্ক মোড পরিবর্তন",
                            tint = if (isDarkMode) Color.Yellow else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Exit App Button
                    IconButton(
                        onClick = { showExitConfirm = true },
                        modifier = Modifier.testTag("exit_app_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "বন্ধ করুন",
                            tint = Color.Red
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                // Horizontal Toolbar Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Back & Forward Controls
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        IconButton(
                            onClick = { webView?.goBack() },
                            enabled = webView?.canGoBack() == true,
                            modifier = Modifier.testTag("nav_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "পিছনে যান"
                            )
                        }

                        IconButton(
                            onClick = { webView?.goForward() },
                            enabled = webView?.canGoForward() == true,
                            modifier = Modifier.testTag("সামনে যান")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "সামনে যান"
                            )
                        }
                    }

                    // Rewarded Ads demonstration button - "Free Earn Tips"
                    Button(
                        onClick = {
                            if (rewardedAd != null) {
                                rewardedAd.show(context) { rewardItem ->
                                    val amount = rewardItem.amount
                                    val type = rewardItem.type
                                    Log.d(TAG, "Rewarded user with: $amount $type")
                                    rewardedTipsRewardText = "অভিনন্দন! আপনি সফল বিজ্ঞাপনটি সম্পন্ন করেছেন। আপনার বোনাস ক্যাশলি টিপস: ১. ক্যাশলি ব্লগের আর্টিকেল নিয়মিত পড়লে আয়ের সুযোগ বৃদ্ধি পাবে। ২. প্রতিদিন রিফ্রেশ বাটন ব্যবহার করে নতুন অফারগুলো দেখুন।"
                                    showRewardDialog = true
                                    onReloadRewarded()
                                }
                            } else {
                                // Default or failure fallback tips
                                rewardedTipsRewardText = "ক্যাশলি টিপস: আপনার ক্যাশলি ব্লগে স্বাগতম! এখানে আপনি ঘরে বসে ফ্রিল্যান্সিং, ক্রিপ্টোকারেন্সি এবং মোবাইল দিয়ে আয়ের সেরা ট্রিকস পাবেন। (বিজ্ঞাপন লোড হচ্ছে, পুনরায় চেষ্টা করুন)"
                                showRewardDialog = true
                                onReloadRewarded()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("reward_ad_button")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "টিপস বাটন",
                                modifier = Modifier.size(16.dp)
                            )
                            Text("মেশিনে আয় টিপস", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Banner Ad Integration Container
                ComposeAdMobBanner(
                    unitId = bannerAdUnitId,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(if (isDarkMode) Color(0xFF121212) else Color.White)
        ) {
            // Check Network connection
            if (!isOnline.value) {
                OfflineWarningView(
                    onRetry = {
                        isOnline.value = isNetworkConnected(context)
                        if (isOnline.value) {
                            hasError = false
                            webView?.reload()
                        }
                    }
                )
            } else if (hasError) {
                LoadingErrorView(
                    onRetry = {
                        hasError = false
                        webView?.reload()
                    }
                )
            }

            // WebView integration with performance optimizations
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        
                        // Performance and Low Internet optimizations
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            
                            // Caching engine optimization for lightweight & fast loading
                            cacheMode = if (!isNetworkConnected(ctx)) {
                                WebSettings.LOAD_CACHE_ELSE_NETWORK
                            } else {
                                WebSettings.LOAD_DEFAULT
                            }
                            
                            // Keep hardware acceleration active
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                isPageLoading = true
                                hasError = false
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isPageLoading = false
                                // Auto inject dark/light mode CSS styling
                                injectThemeStyles(view, isDarkMode)
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                // Double check if it's main request failing
                                if (request?.isForMainFrame == true) {
                                    hasError = true
                                    isPageLoading = false
                                }
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }

                            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                Log.d(TAG, "WebView Console: ${consoleMessage?.message()}")
                                return super.onConsoleMessage(consoleMessage)
                            }
                        }

                        loadUrl("https://cashly4you.blogspot.com/")
                        webView = this
                    }
                },
                update = { view ->
                    webView = view
                },
                modifier = Modifier.fillMaxSize()
            )

            // Linear Progress Indicator for fluid user experience
            if (isPageLoading) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .height(4.dp),
                    color = MaterialTheme.colorScheme.secondary,
                    trackColor = MaterialTheme.colorScheme.secondaryContainer
                )
            }
        }
    }

    // Exit Confirmation Dialog
    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "সতর্কতা",
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(
                    text = "অ্যাপ বন্ধ করতে চান?",
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Text(
                    text = "আপনি কি নিশ্চিতভাবেই ক্যাশলি অ্যাপটি বন্ধ করে দিতে চান?",
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExitConfirm = false
                        // Show Interstitial on Exiting if available
                        if (interstitialAd != null) {
                            interstitialAd.show(context)
                        }
                        exitApp()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("হ্যাঁ, বন্ধ করুন", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showExitConfirm = false }
                ) {
                    Text("না", fontWeight = FontWeight.SemiBold)
                }
            },
            modifier = Modifier.testTag("exit_confirmation_dialog")
        )
    }

    // Rewarded Info Dialog
    if (showRewardDialog) {
        Dialog(onDismissRequest = { showRewardDialog = false }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "টিপস তথ্য",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = "ক্যাশলি টিপস এবং ট্রিকস",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = rewardedTipsRewardText,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Button(
                        onClick = { showRewardDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Text("ঠিক আছে", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// Helper to check network network state
fun isNetworkConnected(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val activeNetwork = cm.activeNetwork ?: return false
    val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

// Elegant View for handling slow/no internet network conditions
@Composable
fun OfflineWarningView(onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.95f))
            .clickable(enabled = false) {}, // Intercept clicks
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "কোন ইন্টারনেট নেই",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(48.dp)
                )
                Text(
                    text = "ইন্টারনেট সংযোগ নেই!",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = "আপনার ফোনের ডেটা অথবা ওয়াই-ফাই অন করুন এবং পুনরায় চেষ্টা করুন। অফলাইনে থাকার কারণে সাইটের ডাটা ক্যাশড থেকে লোড করা হচ্ছে।",
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                )
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onErrorContainer,
                        contentColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("পুনরায় চেষ্টা করুন", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// Elegant View for handling web resource loading errors (like slow timeout or block)
@Composable
fun LoadingErrorView(onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "ত্রুটি",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp)
            )
            Text(
                text = "পৃষ্ঠা লোড করতে সমস্যা হচ্ছে!",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                textAlign = TextAlign.Center
            )
            Text(
                text = "ইন্টারনেটের গতি কম বা সার্ভার সমস্যার কারণে লোড ব্যর্থ হয়েছে। আপনি রিফ্রেশ করে পুনরায় চেষ্টা করতে পারেন।",
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onRetry,
                modifier = Modifier.widthIn(min = 180.dp)
            ) {
                Text("পুনরায় লোড করুন", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// Injects Custom CSS directly into Cashly Blogspot WebView to make the loaded page support light & dark modes instantly
fun injectThemeStyles(webView: WebView?, isDarkMode: Boolean) {
    if (webView == null) return
    
    val css = if (isDarkMode) {
        """
        /* Target common blogspot tags to force background contrast adjustments */
        body, html, .main-outer, .header-outer, .content-outer, .post-outer, .sidebar-outer, .footer-outer {
            background-color: #121212 !important;
            color: #E2E2E2 !important;
        }
        h1, h2, h3, h4, h5, h6, .post-title, .title {
            color: #FFFFFF !important;
        }
        a, p, span, li, div {
            color: #E2E2E2 !important;
        }
        /* Style card like boxes */
        .post, .widget, .card, .comment {
            background-color: #1E1E1E !important;
            border-color: #333333 !important;
            box-shadow: 0 2px 4px rgba(0,0,0,0.5) !important;
        }
        a:link, a:visited {
            color: #4BB4FF !important;
        }
        """
    } else {
        "" // default blogspot style
    }

    if (css.isNotEmpty()) {
        val js = """
            (function() {
                var style = document.getElementById('cashly-theme-css');
                if (!style) {
                    style = document.createElement('style');
                    style.id = 'cashly-theme-css';
                    document.head.appendChild(style);
                }
                style.innerHTML = `$css`;
            })()
        """.trimIndent()
        
        webView.post {
            webView.evaluateJavascript(js, null)
        }
    } else {
        // Remove style node if light mode selected
        val jsRemove = """
            (function() {
                var style = document.getElementById('cashly-theme-css');
                if (style) {
                    style.parentNode.removeChild(style);
                }
            })()
        """.trimIndent()
        
        webView.post {
            webView.evaluateJavascript(jsRemove, null)
        }
    }
}

// AdMob Banner Composer component
@Composable
fun ComposeAdMobBanner(unitId: String, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            AdView(context).apply {
                setAdSize(AdSize.BANNER)
                adUnitId = unitId
                val adRequest = AdRequest.Builder().build()
                loadAd(adRequest)
            }
        },
        update = { adView ->
            // Re-loads or updates ad if lifecycle triggers
            Log.d(TAG, "AdMob Banner adView updated.")
        },
        modifier = modifier
    )
}

// Temporary Greeting defined for screenshot and legacy testing
@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.padding(16.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Cashly Web App", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Hello, $name! This preview certifies that the Cashly styling works as intended.", fontSize = 14.sp)
        }
    }
}
