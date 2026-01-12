package com.aiselp.autox.ui.material3.activity

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.aiselp.autox.ui.material3.theme.AppTheme
import com.stardust.autojs.util.DeviceInfo
import com.stardust.autojs.util.LogCat
import com.stardust.toast
import java.io.File

open class ErrorReportActivity : AppCompatActivity() {
    private val deviceMessage: String by lazy {
        DeviceInfo(this).toString()
    }
    private val crashInfo by lazy { handleIntent() }
    protected open val logCatSaveFile: File
        get() {
            return File(
                externalCacheDir, "autox-logcat.txt"
            )
        }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Scaffold(topBar = {
                    TopAppBar(
                        title = { Text(text = "应用崩溃了") },
                        actions = { AppBarActions() }
                    )
                }) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(it)
                    ) {
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(horizontal = 16.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(text = crashInfo, style = MaterialTheme.typography.bodyMedium)
                        }
                        BottomActions()
                    }
                }

            }
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("label", text)
        clipboard.setPrimaryClip(clip)
    }

    @Composable
    protected open fun AppBarActions() {
        var dropdownMenu by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { dropdownMenu = true }) {
                Icon(imageVector = Icons.Default.MoreVert, contentDescription = null)
            }
            DropdownMenu(expanded = dropdownMenu, onDismissRequest = { dropdownMenu = false }) {
                DropdownMenuItem(text = { Text(text = "获取logcat信息") }, onClick = {
                    dropdownMenu = false
                    Thread {
                        LogCat.saveLogcat(logCatSaveFile)
                        val uri = FileProvider.getUriForFile(
                            this@ErrorReportActivity,
                            "$packageName.fileprovider",
                            logCatSaveFile
                        )
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            setType("text/plain") // 设置文件类型
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        // 启动分享 Intent
                        startActivity(Intent.createChooser(shareIntent, logCatSaveFile.name))
                    }.start()
                })
            }
        }

    }

    @Composable
    protected open fun BottomActions() {
        Row(
            Modifier
                .padding(horizontal = 8.dp)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ElevatedButton(onClick = {
                copyToClipboard(crashInfo)
                toast(this@ErrorReportActivity, "复制成功")
            }) {
                Text(text = "复制信息")
            }
            ElevatedButton(onClick = {
                val mainActivity = intent.getStringExtra("mainActivity")
                try {
                    Intent(this@ErrorReportActivity, Class.forName(mainActivity!!)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(this)
                        finish()
                    }
                } catch (e: Exception) {
                    toast(this@ErrorReportActivity, "无法重启")
                }
            }) {
                Text(text = "重启软件")
            }
            ElevatedButton(onClick = { finish() }) {
                Text(text = "退出软件")
            }
        }
    }

    private fun handleIntent(): String {
        val message = intent.getStringExtra("message")
        val errorDetail = intent.getStringExtra("error")

        val crashInfo = String.format(
            "设备信息:\n%s\n\n错误信息:\n%s\n%s",
            deviceMessage, message, errorDetail
        )
        return crashInfo
    }

    companion object {
        fun install(app: Application, mainActivity: Class<out Activity>? = null) {
            val defaultUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
            val mainActivityClassName = mainActivity?.name
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                if (t !== Looper.getMainLooper().thread) {
                    return@setDefaultUncaughtExceptionHandler
                }
                val intent = Intent(app, ErrorReportActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("message", e.message)
                    putExtra("error", e.stackTraceToString())
                    putExtra("mainActivity", mainActivityClassName)
                }

                app.startActivity(intent)
                defaultUncaughtExceptionHandler?.uncaughtException(t, e)
            }
        }
    }
}