import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var etUrl: EditText
    private lateinit var etCount: EditText
    private lateinit var etDelay: EditText
    private lateinit var swUa: Switch
    private lateinit var swDom: Switch
    private lateinit var swCache: Switch
    private lateinit var btnStart: Button
    private lateinit var tvProgress: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvErrors: TextView
    private lateinit var webView: WebView

    // 測試狀態變數
    private var isRunning = false
    private var currentCount = 0
    private var maxCount = 0
    private var delayMs = 2000L
    private var targetUrl = ""

    // 統計數據
    private var loadStartTime = 0L
    private val latencies = mutableListOf<Long>()
    private var httpErrorCount = 0
    private var oomCrashCount = 0
    private var timeoutCount = 0

    private val CHROME_MOBILE_UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
    private var defaultUa = ""
    private var timeoutJob: Job? = null

    @SuppressLint("SetJavaScriptEnabled")
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
        swUa = findViewById(R.id.swUa)
        swDom = findViewById(R.id.swDom)
        swCache = findViewById(R.id.swCache)
        btnStart = findViewById(R.id.btnStart)
        tvProgress = findViewById(R.id.tvProgress)
        tvStats = findViewById(R.id.tvStats)
        tvErrors = findViewById(R.id.tvErrors)
        webView = findViewById(R.id.webView)
        tvAnalysis = findViewById(R.id.tvAnalysis)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        defaultUa = webView.settings.userAgentString
        
        // 開啟遠端除錯 (重要：讓電腦 Chrome 可以 Inspect)
        WebView.setWebContentsDebuggingEnabled(true)

        webView.settings.apply {
            javaScriptEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // 確保只記錄主 URL 的載入時間，過濾掉 iframe 等子請求
                if (url == targetUrl) {
                    loadStartTime = System.currentTimeMillis()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!isRunning || url != targetUrl) return

                timeoutJob?.cancel() // 取消超時監控
                val latency = System.currentTimeMillis() - loadStartTime
                latencies.add(latency)
                
                updateDashboard()
                scheduleNextLoad()
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                super.onReceivedHttpError(view, request, errorResponse)
                // 只計算主框架的 HTTP 錯誤
                if (request?.isForMainFrame == true) {
                    httpErrorCount++
                    Log.e("WebViewProbe", "HTTP Error: ${errorResponse?.statusCode}")
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    httpErrorCount++ // 視為網路或 DNS 錯誤
                }
            }

            // 攔截 OOM (Out Of Memory) 導致的白畫面崩潰
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                oomCrashCount++
                updateDashboard()
                Log.e("WebViewProbe", "Render process gone! OOM Crash detected.")
                // 重建 WebView 的邏輯較複雜，此處先記錄並停止測試以保留現場
                stopTest()
                Toast.makeText(this@MainActivity, "WebView 進程崩潰 (可能發生OOM)！已停止測試", Toast.LENGTH_LONG).show()
                return true
            }
        }
    }

    private fun startTest() {
        targetUrl = etUrl.text.toString().trim()
        val countStr = etCount.text.toString()
        val delayStr = etDelay.text.toString()

        if (targetUrl.isEmpty() || countStr.isEmpty() || delayStr.isEmpty()) {
            Toast.makeText(this, "請填寫完整測試參數", Toast.LENGTH_SHORT).show()
            return
        }

        if (!targetUrl.startsWith("http")) {
            targetUrl = "https://$targetUrl"
            etUrl.setText(targetUrl)
        }

        // 初始化變數
        maxCount = countStr.toIntOrNull() ?: 10
        delayMs = delayStr.toLongOrNull() ?: 2000L
        currentCount = 0
        latencies.clear()
        httpErrorCount = 0
        oomCrashCount = 0
        timeoutCount = 0
        isRunning = true

        btnStart.text = "停止測試"
        
        // 套用環境變數
        webView.settings.domStorageEnabled = swDom.isChecked
        webView.settings.userAgentString = if (swUa.isChecked) CHROME_MOBILE_UA else defaultUa

        tvAnalysis.visibility = View.GONE // 測試開始時隱藏報告
        tvAnalysis.text = ""

        executeSingleLoad()
    }

    private fun stopTest() {
        isRunning = false
        timeoutJob?.cancel()
        webView.stopLoading()
        btnStart.text = "開始測試"

        // 測試停止時，觸發分析報告生成
        generateAnalysisReport()		
    }

    private fun executeSingleLoad() {
        if (!isRunning) return
        currentCount++
        updateDashboard()

        if (swCache.isChecked) {
            webView.clearCache(true)
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }

        // 啟動超時看門狗 (Watchdog) - 若 15 秒未載入完畢視為 Timeout
        timeoutJob = lifecycleScope.launch {
            delay(15000L)
            if (isRunning) {
                timeoutCount++
                webView.stopLoading()
                Log.e("WebViewProbe", "Timeout detected!")
                updateDashboard()
                scheduleNextLoad()
            }
        }

        webView.loadUrl(targetUrl)
    }

    private fun scheduleNextLoad() {
        if (currentCount >= maxCount) {
            stopTest()
            Toast.makeText(this, "測試完成", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            delay(delayMs)
            executeSingleLoad()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateDashboard() {
        tvProgress.text = "進度: $currentCount / $maxCount"
        
        if (latencies.isNotEmpty()) {
            val min = latencies.minOrNull() ?: 0
            val max = latencies.maxOrNull() ?: 0
            val avg = latencies.average().toLong()
            tvStats.text = "延遲 (毫秒) - 最小: $min | 最大: $max | 平均: $avg"
        }
        
        tvErrors.text = "異常次數 - HTTP/網路錯誤: $httpErrorCount | OOM 白屏: $oomCrashCount | 超時(15s): $timeoutCount"
    }
	
    @SuppressLint("SetTextI18n")
    private fun generateAnalysisReport() {
        if (currentCount == 0) return
        
        tvAnalysis.visibility = View.VISIBLE
        val report = java.lang.StringBuilder("🔍 【綜合分析與排查建議】\n\n")
    
        // 1. 致命錯誤：OOM 白畫面分析
        if (oomCrashCount > 0) {
            report.append("❌ 偵測到白屏 (OOM)：\n")
            report.append("   WebView 記憶體被撐爆。請前端檢查是否單次載入過多 DOM 節點、圖表未釋放，或建議實作虛擬列表 (Virtual Scroll) 與分頁機制。\n\n")
        }
    
        // 2. 網路與連線異常分析
        if (httpErrorCount > 0 || timeoutCount > 0) {
            if (swUa.isChecked) {
                report.append("⚠️ 網路/超時異常 (已偽裝 UA)：\n")
                report.append("   已偽裝成原生瀏覽器仍發生錯誤，排除 WAF 阻擋。請系統工程師檢查伺服器高併發負載、連線池 (Connection Pool) 狀態或資料庫查詢效能。\n\n")
            } else {
                report.append("⚠️ 網路/超時異常 (預設 UA)：\n")
                report.append("   請嘗試打開「偽裝原生 Chrome UA」再測一次。若開啟後正常，極大機率是 WAF / 防禦設備將 WebView 的流量誤判為爬蟲而進行限流或阻擋。\n\n")
            }
        }
    
        // 3. 延遲與效能波動分析
        if (latencies.isNotEmpty()) {
            val avg = latencies.average()
            val min = latencies.minOrNull() ?: 0
            val max = latencies.maxOrNull() ?: 0
    
            if (avg > 3000) {
                report.append("🐢 平均載入偏慢 (>3秒)：\n")
                report.append("   若原生瀏覽器很快，可能是前端 JS 在 WebView 中執行效率不佳，或前端框架缺乏 DOM Storage 支援（請確認測試時已開啟 DOM Storage）。\n\n")
            }
    
            // 判斷波動是否過大 (最大延遲是最小延遲的 3 倍以上，且最小延遲不為 0)
            if (min > 0 && max > min * 3) {
                report.append("📉 延遲極度不穩：\n")
                report.append("   最大延遲 ($max ms) 是最小延遲的數倍。可能是 CDN 命中率不佳、後端 API 偶發性卡頓，或是測試設備的網路環境波動。\n\n")
            }
        }
    
        // 4. 一切正常時的推論
        if (oomCrashCount == 0 && httpErrorCount == 0 && timeoutCount == 0 && (latencies.average() < 2500)) {
            report.append("✅ 探針環境測試結果良好：\n")
            report.append("   載入順暢且無報錯。若回到「主 App」依然卡頓，代表問題不在 Web 端或網路端。請 App 團隊往「主 App 的多執行緒搶佔資源」、「記憶體洩漏 (Memory Leak)」或「主 App 內嵌的其他 SDK 干擾」方向排查。")
        }
    
        tvAnalysis.text = report.toString()
    }	
	
}
