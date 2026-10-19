package cn.aust.mytimetable

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

class SchoolPortalActivity : ComponentActivity() {
    private lateinit var portal: WebView
    private var loading by mutableStateOf(true)
    private var problem by mutableStateOf<String?>(null)
    private var closed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The official Angular app requires JavaScript and localStorage.
        // Never attach a native JS bridge or log credentials/page content.
        WebView.setWebContentsDebuggingEnabled(false)
        portal = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(false)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val blocked = !SchoolPortalPolicy.allowsNavigation(request.url.toString())
                    if (blocked && request.isForMainFrame) {
                        problem = "学校页面尝试跳转到其他地址，接入验证版已停止跳转。请返回后反馈此情况。"
                        loading = false
                    }
                    return blocked
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    if (closed) return
                    loading = true
                    problem = null
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    if (!closed) loading = false
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (request.isForMainFrame && !closed) {
                        problem = "教务页面连接失败，请检查网络后重试。"
                        loading = false
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse,
                ) {
                    if (request.isForMainFrame && !closed) {
                        problem = "教务页面返回 HTTP ${errorResponse.statusCode}，请稍后重试。"
                        loading = false
                    }
                }

                override fun onReceivedSslError(
                    view: WebView,
                    handler: SslErrorHandler,
                    error: SslError,
                ) {
                    handler.cancel()
                    if (!closed) {
                        problem = "无法验证学校站点的安全连接，已停止加载。"
                        loading = false
                    }
                }
            }
            loadUrl(SchoolPortalPolicy.HOME)
        }

        setContent {
            TimetableTheme {
                Scaffold { insets ->
                    Column(Modifier.fillMaxSize().padding(insets)) {
                        Row(Modifier.fillMaxWidth()) {
                            TextButton(onClick = { finish() }) { Text("返回") }
                            TextButton(onClick = {
                                problem = null
                                loading = true
                                portal.loadUrl(SchoolPortalPolicy.HOME)
                            }) { Text("重新加载") }
                        }
                        Text(
                            "学校官方页面 · jwglyd.aust.edu.cn",
                            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            "请在下方学校页面登录。这里只验证访问与登录，尚未导入课表。",
                            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        problem?.let { message ->
                            Text(
                                message,
                                Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        AndroidView(
                            factory = { portal },
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::portal.isInitialized) portal.onResume()
    }

    override fun onPause() {
        if (::portal.isInitialized) {
            CookieManager.getInstance().flush()
            portal.onPause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        closed = true
        if (::portal.isInitialized) portal.stopLoading()
        super.onDestroy()
        if (::portal.isInitialized) portal.destroy()
    }
}
