package com.fk.panel

import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.fk.panel.ui.FkPanelTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FkPanelTheme {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    FkApp()
                }
            }
        }
    }
}

private enum class Tab(val label: String) {
    SCREEN("屏幕"), TERMINAL("终端"), FILES("文件"), STATUS("状态")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FkApp() {
    var tab by remember { mutableStateOf(Tab.SCREEN) }
    var connected by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("正在定位设备…") }
    var devW by remember { mutableIntStateOf(1080) }
    var devH by remember { mutableIntStateOf(2400) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { Api.resolve() }
        }.onSuccess {
            connected = true
            status = "已连接 ${Api.base}"
            withContext(Dispatchers.IO) { runCatching { Api.stat() } }.onSuccess { st ->
                devW = st.optInt("w", devW); devH = st.optInt("h", devH)
            }
        }.onFailure {
            status = "找不到设备，点状态页『重新找地址』"
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("FK 控制台", style = MaterialTheme.typography.titleLarge)
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.SCREEN -> Icons.Filled.PhoneAndroid
                                    Tab.TERMINAL -> Icons.Filled.Terminal
                                    Tab.FILES -> Icons.Filled.Folder
                                    Tab.STATUS -> Icons.Filled.Info
                                },
                                contentDescription = t.label
                            )
                        },
                        label = { Text(t.label, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                Tab.SCREEN -> ScreenPane(connected, devW, devH) { w, h -> devW = w; devH = h }
                Tab.TERMINAL -> TerminalPane(connected)
                Tab.FILES -> FilesPane(connected)
                Tab.STATUS -> StatusPane({ status = it; connected = true }, status)
            }
        }
    }
}

/* ---------------- 屏幕 ---------------- */
@Composable
fun ScreenPane(connected: Boolean, devW: Int, devH: Int, onSize: (Int, Int) -> Unit) {
    var boxSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(connected, devW, devH, boxSize) {
                        if (!connected) return@pointerInput
                        var lastX = 0; var lastY = 0
                        var lastSent = 0L
                        detectDragGestures(
                            onDragStart = { off ->
                                lastX = (off.x * devW / size.width).toInt()
                                lastY = (off.y * devH / size.height).toInt()
                                Api.touch("down", lastX, lastY)
                            },
                            onDrag = { change, _ ->
                                val now = System.currentTimeMillis()
                                if (now - lastSent >= 25) {
                                    lastSent = now
                                    val x = (change.position.x * devW / size.width).toInt()
                                    val y = (change.position.y * devH / size.height).toInt()
                                    if (x != lastX || y != lastY) {
                                        lastX = x; lastY = y
                                        Api.touch("move", x, y)
                                    }
                                }
                            },
                            onDragEnd = { Api.touch("up", lastX, lastY) },
                            onDragCancel = { Api.touch("up", lastX, lastY) }
                        )
                    }
                    .pointerInput(connected, devW, devH, boxSize) {
                        if (!connected) return@pointerInput
                        detectTapGestures { off ->
                            val x = (off.x * devW / size.width).toInt()
                            val y = (off.y * devH / size.height).toInt()
                            Api.tap(x, y)
                        }
                    }
            ) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).also { sv ->
                            sv.holder.addCallback(object : SurfaceHolder.Callback {
                                private var decoder: VideoDecoder? = null
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    if (Api.base.isEmpty()) return
                                    decoder = VideoDecoder(holder.surface) { w, h -> onSize(w, h) }
                                    decoder?.start(Api.streamUrl(), Api.auth())
                                }
                                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                                override fun surfaceDestroyed(h: SurfaceHolder) {
                                    decoder?.stop(); decoder = null
                                }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(
                "返回" to 4, "主页" to 3, "最近" to 187, "电源" to 26
            ).forEach { (label, code) ->
                FilledTonalButton(
                    onClick = { Api.key(code) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                ) { Text(label, style = MaterialTheme.typography.labelMedium) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf("音量+" to 24, "音量-" to 25, "亮屏" to 224, "解锁" to 82).forEach { (label, code) ->
                FilledTonalButton(
                    onClick = { Api.key(code) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                ) { Text(label, style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

/* ---------------- 终端 ---------------- */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalPane(connected: Boolean) {
    var cmd by remember { mutableStateOf("") }
    var asRoot by remember { mutableStateOf(true) }
    val log = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()

    fun exec() {
        if (cmd.isBlank() || !connected) return
        val c = cmd
        log.add("$ " + c)
        cmd = ""
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Api.runCmd(c, asRoot) }.getOrNull() }
            val out = r?.optString("out", "") ?: "执行失败"
            val err = r?.optString("err", "").orEmpty()
            if (out.isNotBlank()) log.add(out.trimEnd())
            if (err.isNotBlank()) log.add("[err] " + err.trimEnd())
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
        ) {
            LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
                items(log) { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = cmd,
                onValueChange = { cmd = it },
                label = { Text("命令", style = MaterialTheme.typography.labelMedium) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { exec() }, shape = RoundedCornerShape(50)) { Text("执行") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = asRoot, onCheckedChange = { asRoot = it })
            Text("用 root 执行 (su)", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { log.clear() }) { Text("清空") }
        }
    }
}

/* ---------------- 文件 ---------------- */
@Composable
fun FilesPane(connected: Boolean) {
    val ctx = LocalContext.current
    var path by remember { mutableStateOf("/sdcard/Download") }
    var items by remember { mutableStateOf(listOf<Triple<String, Boolean, Long>>()) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun load(p: String) {
        if (!connected) return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Api.ls(p) }.getOrNull() }
            if (r != null) {
                if (r.has("error")) { msg = r.optString("error"); return@launch }
                val (realPath, list) = Api.parseListings(r)
                path = realPath
                items = list
                msg = "${list.size} 项"
            }
        }
    }

    LaunchedEffect(connected) { if (connected) load(path) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = path,
                onValueChange = { path = it },
                label = { Text("路径", style = MaterialTheme.typography.labelMedium) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { load(path) }, shape = RoundedCornerShape(50)) { Text("打开") }
        }
        Spacer(Modifier.height(8.dp))
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Card(
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    if (path != "/" && path.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.ArrowUpward, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { load(path.substringBeforeLast('/', "").ifEmpty { "/" }) }) {
                                Text("返回上级", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                items(items) { (name, isDir, size) ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (isDir) Icons.Filled.Folder else Icons.Filled.Description,
                            null,
                            tint = if (isDir) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.bodyMedium)
                            if (!isDir && size > 0) Text(
                                "${size / 1024} KB",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (isDir) {
                            TextButton(onClick = { load(path.trimEnd('/') + "/" + name) }) { Text("进入") }
                        } else {
                            TextButton(onClick = {
                                busy = true; msg = "下载中…"
                                scope.launch {
                                    val f = withContext(Dispatchers.IO) {
                                        runCatching {
                                            Api.download(path.trimEnd('/') + "/" + name, ctx.filesDir) { p ->
                                                msg = "下载中 … $p%"
                                            }
                                        }
                                    }
                                    busy = false
                                    msg = f.getOrNull()?.let { "已保存 ${it.absolutePath}" } ?: "下载失败"
                                }
                            }) { Text("下载") }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

/* ---------------- 状态 ---------------- */
@Composable
fun StatusPane(onConnected: (String) -> Unit, statusText: String) {
    var info by remember { mutableStateOf("读取中…") }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            val st = withContext(Dispatchers.IO) { runCatching { Api.stat() }.getOrNull() }
            info = if (st == null) "读取失败" else buildString {
                appendLine("root      " + st.optString("root"))
                appendLine()
                appendLine("tailscale " + st.optString("ts"))
                appendLine()
                appendLine("sshd      " + st.optString("sshd"))
                appendLine()
                appendLine("服务      " + st.optString("svc"))
                appendLine()
                appendLine("存储      " + st.optString("disk"))
                appendLine()
                append("地址      " + Api.base)
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Text(
                info,
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
        }
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("快捷操作", style = MaterialTheme.typography.titleMedium)
                listOf(
                    "重启手机" to "reboot",
                    "重新提权" to "bootroot",
                    "重启服务" to "restartall",
                    "重刷 adb" to "fixadb"
                ).forEach { (label, act) ->
                    FilledTonalButton(
                        onClick = {
                            scope.launch { withContext(Dispatchers.IO) { runCatching { Api.act(act) } } }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(50)
                    ) { Text(label, style = MaterialTheme.typography.labelLarge) }
                }
                Button(
                    onClick = {
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { runCatching { Api.resolve() } }
                            if (r.isSuccess) { onConnected("已连接 ${Api.base}"); refresh() }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(50)
                ) { Text("重新找地址", style = MaterialTheme.typography.labelLarge) }
            }
        }
        OutlinedButton(onClick = { refresh() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(50)) {
            Text("刷新状态")
        }
        Text(
            statusText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
