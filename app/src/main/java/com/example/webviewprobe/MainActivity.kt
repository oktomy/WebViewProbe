package com.example.webviewprobe

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var etUrl: EditText
    private lateinit var etCount: EditText
    private lateinit var etDelay: EditText
    private lateinit var cbUa: CheckBox
    private lateinit var cbDom: CheckBox
    private lateinit var cbCache: CheckBox
    private lateinit var btnStart: Button
    private lateinit var tvProgress: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvErrors: TextView
    private lateinit var tvAnalysis: TextView
    private lateinit var webView: WebView

    private var isRunning = false
    private var currentCount = 0
    private var maxCount = 0
    private var delayMs = 2000L
    private var targetUrl = ""

    private var loadStartTime = 0L
    private val latencies = mutableListOf<Long>()
    private var httpErrorCount = 0
    private var oomCrashCount = 0
    private var timeoutCount = 0

    private val CHROME_MOBILE_UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
    private var defaultUa = ""

    // 改用最原生的 Handler 來處理延遲與 Timeout
    private val mainHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private var nextLoadRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupWebView()

        btnStart.setOnClickListener {
            if (isRunning) stopTest() else startTest()
        }
    }

    private fun initViews() {
        etUrl = findViewById(R.id.etUrl)
        etCount = findViewById(R.id.etCount)
        etDelay = findViewById(R.id.etDelay)
        cbUa = findViewById(R.id.cbUa)
        cbDom = findViewById(R.id.cbDom)
        cbCache = findViewById(R.id.cbCache)
        btnStart = findViewById(R.id.btnStart)
        tvProgress = findViewById(R.id.tvProgress)
        tvStats = findViewById(R.id.tvStats)
        tvErrors = findViewById(R.id.tvErrors)
        tvAnalysis = findViewById(R.id.tvAnalysis)
        webView = findViewById(R.id.webView)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        defaultUa = webView.settings.userAgentString
        WebView.setWebContentsDebuggingEnabled(true)

        webView.settings.apply {
            javaScriptEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url == targetUrl) loadStartTime = System.currentTimeMillis()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!isRunning || url != targetUrl) return

                timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                val latency = System.currentTimeMillis() - loadStartTime
                latencies.add(latency)
                
                updateDashboard()
                scheduleNextLoad()
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame == true) {
                    httpErrorCount++
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) httpErrorCount++
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                oomCrashCount++
                updateDashboard()
                stopTest()
                Toast.makeText(this@MainActivity, "進程崩潰(OOM)！測試停止", Toast.LENGTH_LONG).show()
                return true
            }
        }
    }

    private fun startTest() {
        targetUrl = etUrl.text.toString().trim()
        val countStr = etCount.text.toString()
        val delayStr = etDelay.text.toString()

        if (targetUrl.isEmpty() || countStr.isEmpty() || delayStr.isEmpty()) {
            Toast.makeText(this, "請填寫完整參數", Toast.LENGTH_SHORT).show()
            return
        }

        if (!targetUrl.startsWith("http")) {
            targetUrl = "https://$targetUrl"
            etUrl.setText(targetUrl)
        }

        maxCount = countStr.toIntOrNull() ?: 10
        delayMs = delayStr.toLongOrNull() ?: 2000L
        currentCount = 0
        latencies.clear()
        httpErrorCount = 0
        oomCrashCount = 0
        timeoutCount = 0
        isRunning = true

        btnStart.text = "停止測試"
        tvAnalysis.visibility = View.GONE
        
        webView.settings.domStorageEnabled = cbDom.isChecked
        webView.settings.userAgentString = if (cbUa.isChecked) CHROME_MOBILE_UA else defaultUa

        executeSingleLoad()
    }

    private fun stopTest() {
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        webView.stopLoading()
        btnStart.text = "開始測試"
        generateAnalysisReport()
    }

    private fun executeSingleLoad() {
        if (!isRunning) return
        currentCount++
        updateDashboard()

        if (cbCache.isChecked) {
            webView.clearCache(true)
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }

        timeoutRunnable = Runnable {
            if (isRunning) {
                timeoutCount++
                webView.stopLoading()
                updateDashboard()
                scheduleNextLoad()
            }
        }
        mainHandler.postDelayed(timeoutRunnable!!, 15000L)

        webView.loadUrl(targetUrl)
    }

    private fun scheduleNextLoad() {
        if (currentCount >= maxCount) {
            stopTest()
            Toast.makeText(this, "測試完成", Toast.LENGTH_SHORT).show()
            return
        }

        nextLoadRunnable = Runnable { executeSingleLoad() }
        mainHandler.postDelayed(nextLoadRunnable!!, delayMs)
    }

    @SuppressLint("SetTextI18n")
    private fun updateDashboard() {
        tvProgress.text = "進度: $currentCount / $maxCount"
        if (latencies.isNotEmpty()) {
            val min = latencies.minOrNull() ?: 0
            val max = latencies.maxOrNull() ?: 0
            val avg = latencies.average().toLong()
            tvStats.text = "延遲(ms) - 最小:$min | 最大:$max | 平均:$avg"
        }
        tvErrors.text = "異常 - HTTP:$httpErrorCount | OOM:$oomCrashCount | Timeout:$timeoutCount"
    }

    @SuppressLint("SetTextI18n")
    private fun generateAnalysisReport() {
        if (currentCount == 0) return
        tvAnalysis.visibility = View.VISIBLE
        val report = StringBuilder("🔍 【綜合排查建議】\n")

        if (oomCrashCount > 0) report.append("❌ OOM 白屏: WebView 記憶體撐爆，請檢查前端 DOM 節點數量或改用虛擬列表。\n")
        if (httpErrorCount > 0 || timeoutCount > 0) {
            if (cbUa.isChecked) report.append("⚠️ 網路異常 (已偽裝UA): 排除 WAF 阻擋。請檢查伺服器連線池或負載狀態。\n")
            else report.append("⚠️ 網路異常 (預設UA): 請勾選「偽裝UA」再測。若恢復正常，代表 WAF 阻擋了 WebView 流量。\n")
        }
        if (latencies.isNotEmpty() && latencies.average() > 3000) report.append("🐢 載入過慢: 確認已開啟 DOM Storage。\n")
        
        if (oomCrashCount == 0 && httpErrorCount == 0 && timeoutCount == 0 && latencies.average() < 2500) {
            report.append("✅ 探針測試良好: 若主 App 仍卡頓，問題不在 Web 端。請朝主 App 的多執行緒資源搶佔或記憶體外洩方向排查。")
        }
        tvAnalysis.text = report.toString()
    }
}
