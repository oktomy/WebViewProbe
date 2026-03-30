package com.example.webviewprobe

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.util.Log
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

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
    private var isWaitingForPage = false // 用來鎖定每一次的載入狀態
    
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

    // FTP
    private lateinit var etFtpHost: EditText
    private lateinit var etFtpUser: EditText
    private lateinit var etFtpPw: EditText
    private lateinit var btnUpload: Button
    private lateinit var btnClearLog: Button

    private val logFileName = "probe_logs.txt"
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    // 追蹤時間戳
    private var requestSentTime = 0L
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupWebView()

        btnStart.setOnClickListener {
            if (isRunning) stopTest() else startTest()
        }

        btnUpload.setOnClickListener { uploadLogToFtp() }
        btnClearLog.setOnClickListener {
            val file = File(filesDir, logFileName)
            if (file.exists()) file.delete()
            Toast.makeText(this, "Log 已清除", Toast.LENGTH_SHORT).show()
        }        
    }

    private fun initViews() {

        etFtpHost = findViewById(R.id.etFtpHost)
        etFtpUser = findViewById(R.id.etFtpUser)
        etFtpPw = findViewById(R.id.etFtpPw)
        btnUpload = findViewById(R.id.btnUpload)
        btnClearLog = findViewById(R.id.btnClearLog)
        
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

    private fun writeLog(message: String) {
        try {
            val file = File(filesDir, logFileName)
            val fos = FileOutputStream(file, true) // Append 模式
            val logEntry = "${timeFormat.format(Date())} | $message\n"
            fos.write(logEntry.toByteArray())
            fos.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
                // 移除網址比對，統一在 executeSingleLoad 紀錄開始時間最準
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                
                // 【關鍵修復】：不再比對 url 字串，只要還在等待狀態，就視為本次載入完成。
                if (!isRunning || !isWaitingForPage) return

                val receivedTime = System.currentTimeMillis()
                val duration = receivedTime - requestSentTime
                
                // 寫入本地 Log
                writeLog("SUCCESS | URL: $url | Duration: ${duration}ms")
               
                isWaitingForPage = false // 成功攔截，上鎖避免重複觸發

                timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                val latency = System.currentTimeMillis() - loadStartTime
                latencies.add(latency)
                
                updateDashboard()
                scheduleNextLoad()
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame == true) httpErrorCount++
            }

            // 發生錯誤也紀錄 Log
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    writeLog("ERROR | URL: ${request.url} | Desc: ${error?.description}")
                }
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

        // 確保先清除前一次可能殘留的計時器
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }

        isWaitingForPage = true // 標記開始等待
        requestSentTime = System.currentTimeMillis() // 紀錄發出請求時間        
        writeLog("REQUEST | Round: $currentCount | Target: $targetUrl")        
        
        loadStartTime = System.currentTimeMillis() // 在這裡紀錄時間最準確

        timeoutRunnable = Runnable {
            if (isRunning && isWaitingForPage) {
                isWaitingForPage = false // 超時了，解除等待狀態
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

    private fun uploadLogToFtp() {
        val host = etFtpHost.text.toString()
        val user = etFtpUser.text.toString()
        val pw = etFtpPw.text.toString()

        if (host.isEmpty()) {
            Toast.makeText(this, "請輸入 FTP 主機", Toast.LENGTH_SHORT).show()
            return
        }

        Thread {
            val client = FTPClient()
            try {
                client.connect(host, 21)
                client.login(user, pw)
                client.enterLocalPassiveMode()
                client.setFileType(FTP.BINARY_FILE_TYPE)

                val logFile = File(filesDir, logFileName)
                if (!logFile.exists()) {
                    runOnUiThread { Toast.makeText(this, "找不到 Log 檔案", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }

                logFile.inputStream().use { 
                    val remoteName = "Probe_${Build.MODEL}_${System.currentTimeMillis()}.txt"
                    val success = client.storeFile(remoteName, it)
                    runOnUiThread {
                        if (success) Toast.makeText(this, "上傳成功: $remoteName", Toast.LENGTH_LONG).show()
                        else Toast.makeText(this, "上傳失敗", Toast.LENGTH_SHORT).show()
                    }
                }
                client.logout()
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "FTP 錯誤: ${e.message}", Toast.LENGTH_LONG).show() }
            } finally {
                client.disconnect()
            }
        }.start()
    }    
}
