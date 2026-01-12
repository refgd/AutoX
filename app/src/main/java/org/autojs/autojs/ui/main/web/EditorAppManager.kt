package org.autojs.autojs.ui.main.web

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.fragment.app.Fragment
import com.stardust.autojs.util.dataStore
import com.stardust.toast
import com.stardust.util.IntentUtil
import kotlinx.coroutines.flow.first

class EditorAppManager : Fragment() {
    val webView by lazy {
        object : WebView(requireContext()) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK &&
                    event.action == KeyEvent.ACTION_UP &&
                    canGoBack()
                ) {
                    goBack()
                    return true
                } else
                    return super.dispatchKeyEvent(event)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val composeView = ComposeView(requireContext())
        composeView.setContent { Page() }
        return composeView
    }

    suspend fun setup(webView: WebView) {
        webView.webViewClient = WebViewClient(webView.context, DocumentSource.DOC_V2_LOCAL.uri)
        webView.settings.apply {
            javaScriptEnabled = true  //设置支持Javascript交互
            domStorageEnabled = true
            databaseEnabled = true   //开启 database storage API 功能
        }
        loadSavedPage(webView)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Page() {
        LaunchedEffect(webView) {
            setup(webView)
        }
        AndroidView(factory = {
            webView
        })
    }

    companion object {
        const val TAG = "EditorAppManager"
        const val DocumentSourceKEY = "DocumentSource"
        val savedUri = stringPreferencesKey("DocumentSavedUri")

        private var saveStatus: SharedPreferences? = null

        @Synchronized
        fun getSaveStatus(context: Context): SharedPreferences {
            if (saveStatus == null) {
                saveStatus = context.getSharedPreferences(TAG, Context.MODE_PRIVATE)
            }
            return saveStatus!!
        }

        fun loadHomeDocument(webView: WebView) {
            webView.loadUrl("https://appassets.androidplatform.net")
        }

        suspend fun loadSavedPage(webView: WebView) {
            val first = webView.context.dataStore.data.first()
            val uri = first[savedUri]
            if (uri != null) {
                webView.loadUrl(uri)
            } else loadHomeDocument(webView)
        }

        suspend fun saveCurrentPage(webView: WebView) {
            val uri = webView.url
            webView.context.dataStore.edit {
                if (uri != null) it[savedUri] = uri
            }
        }

        fun openDocument(context: Context) {
            val name = getSaveStatus(context).getString(
                DocumentSourceKEY,
                DocumentSource.DOC_V2_LOCAL.name
            )
            val uri = DocumentSource.valueOf(name!!).let {
                if (it.isLocal) it.openUri
                else it.uri
            }
            if (uri != null) {
                IntentUtil.browse(context, uri)
            } else {
                toast(context, "此文档未提供在线uri")
            }
        }

        fun switchDocument(webView: WebView, documentSource: DocumentSource) {
            if (documentSource.isLocal) {
                webView.webViewClient = WebViewClient(webView.context, documentSource.uri)
                webView.loadUrl("https://appassets.androidplatform.net")
            } else
                webView.loadUrl(documentSource.uri)
            getSaveStatus(webView.context).edit {
                putString(DocumentSourceKEY, documentSource.name)
            }
        }
    }
}