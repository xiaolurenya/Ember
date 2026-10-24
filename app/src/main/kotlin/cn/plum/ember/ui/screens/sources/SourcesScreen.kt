package cn.plum.ember.ui.screens.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.plum.ember.data.App
import cn.plum.ember.data.LibraryRepo
import cn.plum.ember.data.MediaSource
import cn.plum.ember.data.SourceType
import cn.plum.ember.ui.theme.Ember
import cn.plum.ember.ui.theme.EmberSoft
import cn.plum.ember.ui.theme.InkRaised
import cn.plum.ember.ui.theme.TextHi
import cn.plum.ember.ui.theme.TextLow
import cn.plum.ember.ui.theme.TextMid
import cn.plum.ember.ui.widgets.tvFocus
import cn.plum.ember.util.Json
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 源管理页：已保存源列表 + 添加/编辑表单。
 * 支持六种源类型：Emby / Jellyfin / Plex / AList / WebDAV / 本地目录。
 * 「扫码快速添加」按钮先占位（需要相机权限和二维码库，v1.1 再接）。
 */
@Composable
fun SourcesScreen(
    repo: LibraryRepo,
    onDone: () -> Unit,
    onEditSource: (MediaSource) -> Unit = {}
) {
    var list by remember { mutableStateOf(App.sources.all()) }
    var editing by remember { mutableStateOf<MediaSource?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<MediaSource?>(null) }

    Box(Modifier.fillMaxSize().background(cn.plum.ember.ui.theme.Ink)) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("媒体源", color = TextHi, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (list.isNotEmpty()) {
                    TextButton(onClick = onDone) { Text("进入", color = Ember) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "支持 Emby / Jellyfin / Plex / AList / WebDAV / 本地目录，可保存多套配置随时切换",
                color = TextLow, fontSize = 13.sp
            )
            Spacer(Modifier.height(18.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(list, key = { it.id }) { s ->
                    SourceRow(
                        source = s,
                        onEdit = { editing = s },
                        onDelete = { deleting = s }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { adding = true },
                modifier = Modifier.fillMaxWidth().height(52.dp)
                    .clip(RoundedCornerShape(14.dp))
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("添加媒体源", fontSize = 15.sp)
            }
        }

        // ---- 添加表单 ----
        if (adding) {
            SourceForm(
                initial = null,
                onDismiss = { adding = false },
                onTest = { s, pwd -> repo.testSource(s, pwd) },
                onSave = { s, pwd ->
                    val r = repo.upsertSource(s, pwd)
                    if (r.isSuccess) list = App.sources.all()
                    r
                }
            )
        }

        // ---- 编辑表单 ----
        editing?.let { s ->
            SourceForm(
                initial = s,
                onDismiss = { editing = null },
                onTest = { ns, pwd -> repo.testSource(ns, pwd) },
                onSave = { ns, pwd ->
                    val r = repo.upsertSource(ns, pwd)
                    if (r.isSuccess) list = App.sources.all()
                    r
                }
            )
        }

        // ---- 删除确认 ----
        deleting?.let { s ->
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text("删除源", color = TextHi) },
                text = { Text("确定删除「${s.name}」？观看进度会保留，但需要重新登录才能继续观看。", color = TextMid) },
                confirmButton = {
                    TextButton(onClick = {
                        repo.removeSource(s.id)
                        list = App.sources.all()
                        deleting = null
                    }) { Text("删除", color = Color(0xFFFF6B6B)) }
                },
                dismissButton = {
                    TextButton(onClick = { deleting = null }) { Text("取消", color = TextMid) }
                },
                containerColor = InkRaised
            )
        }
    }
}

@Composable
private fun SourceRow(source: MediaSource, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(InkRaised)
            .padding(14.dp)
            .tvFocus(1.02f) { onEdit() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
                .background(Ember.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Text(source.type.name.take(2), color = Ember, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(source.name, color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (source.locked) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.Lock, contentDescription = "已锁定",
                        tint = EmberSoft, modifier = Modifier.size(14.dp))
                }
            }
            Text(
                "${source.type.label} · ${source.baseUrl}",
                color = TextLow, fontSize = 12.sp,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Default.Edit, contentDescription = "编辑", tint = TextMid)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "删除", tint = TextMid.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun SourceForm(
    initial: MediaSource?,
    onDismiss: () -> Unit,
    onTest: suspend (MediaSource, String?) -> Result<Unit>,
    onSave: suspend (MediaSource, String?) -> Result<Unit>
) {
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(initial?.type ?: SourceType.EMBY) }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    // 地址和端口分开填（大哥要求的）：剥掉协议后按最后一个 : 拆
    val strippedBase = initial?.baseUrl?.removePrefix("http://")?.removePrefix("https://") ?: ""
    var host by remember { mutableStateOf(strippedBase.substringBeforeLast(':').ifBlank { strippedBase }) }
    var port by remember { mutableStateOf(if (strippedBase.contains(':')) strippedBase.substringAfterLast(':') else "") }
    var https by remember { mutableStateOf(initial?.baseUrl?.startsWith("https") ?: false) }
    var username by remember { mutableStateOf(initial?.username ?: "") }
    var password by remember { mutableStateOf("") }
    var token by remember { mutableStateOf(initial?.token ?: "") }
    var extraHeaders by remember { mutableStateOf(
        initial?.extraHeaders?.entries?.joinToString("\n") { "${it.key}: ${it.value}" } ?: ""
    ) }
    var useProxy by remember { mutableStateOf(initial?.useProxy ?: false) }
    var proxyUrl by remember { mutableStateOf(initial?.proxyUrl ?: "") }
    var skipTls by remember { mutableStateOf(initial?.skipTlsVerify ?: false) }
    var locked by remember { mutableStateOf(initial?.locked ?: false) }
    var testing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var testMsg by remember { mutableStateOf<String?>(null) }

    /** 凭据形态：用户名+密码 / 单 Token / 无需凭据 */
    val needUserPass = type == SourceType.EMBY || type == SourceType.JELLYFIN || type == SourceType.WEBDAV
    val needToken = type == SourceType.PLEX || type == SourceType.ALIST
    val busy = testing || saving
    val hasSavedSecret = initial?.secretRef != null

    /** 该类型服务的默认端口（只作输入框占位提示，留空 = 不拼端口） */
    val defaultPort = when (type) {
        SourceType.EMBY, SourceType.JELLYFIN -> "8096"
        SourceType.PLEX -> "32400"
        SourceType.ALIST -> "5244"
        SourceType.WEBDAV -> "5005"
        SourceType.LOCAL -> null
    }

    /** 表单 → MediaSource。⚠️ secretRef 必须透传，否则编辑保存后旧密码丢失 */
    fun buildSource(): MediaSource {
        val cleanedHost = host.trim().trimEnd('/')
        // 端口留空就不拼（用户可能用反代/默认端口）
        val fullBase = when (type) {
            SourceType.LOCAL -> cleanedHost
            else -> {
                val p = port.trim()
                "${if (https) "https" else "http"}://$cleanedHost" + if (p.isNotEmpty()) ":$p" else ""
            }
        }
        val hdrs = extraHeaders.lineSequence()
            .mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }
            .toMap()
        return MediaSource(
            id = initial?.id ?: UUID.randomUUID().toString().take(8),
            type = type,
            name = name.ifBlank { type.label },
            baseUrl = fullBase,
            username = if (needUserPass) username.takeIf { it.isNotBlank() } else null,
            token = if (needToken) token.takeIf { it.isNotBlank() } else null,
            extraHeaders = hdrs,
            useProxy = useProxy,
            proxyUrl = if (useProxy) proxyUrl.takeIf { it.isNotBlank() } else null,
            skipTlsVerify = skipTls,
            // 本地目录：路径同时写进 baseUrl 和 scanRoot（LocalAdapter 读 scanRoot）
            scanRoot = if (type == SourceType.LOCAL) cleanedHost.ifBlank { "/storage/emulated/0" } else null,
            locked = locked,
            secretRef = initial?.secretRef
        )
    }

    /** 密码单独传（不落库，走 EncryptedSharedPreferences）；留空 = 沿用原密码 */
    fun buildPassword(): String? =
        if (needUserPass) password.takeIf { it.isNotBlank() } else null

    fun feedback(r: Result<Unit>, okText: String) {
        testMsg = if (r.isSuccess) "✓ $okText"
        else "✗ ${r.exceptionOrNull()?.message ?: "失败"}"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加媒体源" else "编辑源", color = TextHi) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 类型选择
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SourceType.entries.forEach { t ->
                        FilterChip(
                            selected = type == t,
                            onClick = { type = t },
                            label = { Text(t.label, fontSize = 12.sp) }
                        )
                    }
                }

                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("显示名称") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                // 地址 + 端口分开填
                if (type == SourceType.LOCAL) {
                    OutlinedTextField(
                        value = host, onValueChange = { host = it },
                        label = { Text("本地根目录") },
                        placeholder = { Text("/storage/emulated/0/Movies") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = host, onValueChange = { host = it },
                            label = { Text("服务器地址（IP 或域名）") },
                            placeholder = { Text("192.168.2.225") },
                            modifier = Modifier.weight(1f), singleLine = true
                        )
                        OutlinedTextField(
                            value = port, onValueChange = { port = it.filter { c -> c.isDigit() } },
                            label = { Text("端口") },
                            placeholder = { Text(defaultPort ?: "") },
                            modifier = Modifier.width(110.dp), singleLine = true
                        )
                    }
                }
                if (type != SourceType.LOCAL) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = https, onCheckedChange = { https = it })
                        Spacer(Modifier.width(8.dp))
                        Text("使用 HTTPS", color = TextMid, fontSize = 13.sp)
                    }
                }
                if (needUserPass) {
                    OutlinedTextField(
                        value = username, onValueChange = { username = it },
                        label = { Text("用户名") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        label = { Text(if (hasSavedSecret) "密码（已保存，留空则不修改）" else "密码") },
                        placeholder = { if (hasSavedSecret) Text("••••••••") else null },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                }
                if (needToken) {
                    OutlinedTextField(
                        value = token, onValueChange = { token = it },
                        label = { Text(if (type == SourceType.PLEX) "X-Plex-Token" else "Token / Cookie") },
                        placeholder = { Text(
                            if (type == SourceType.PLEX) "在 Plex Web 里 F12 取 X-Plex-Token"
                            else "AList 的 token，或 115 的 UID=xx; CID=xx; SEID=xx"
                        ) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                }
                OutlinedTextField(
                    value = extraHeaders, onValueChange = { extraHeaders = it },
                    label = { Text("自定义请求头（User-Agent / Referer / Cookie，每行一个）") },
                    placeholder = { Text("User-Agent: Mozilla/5.0 ...\nCookie: UID=xxx; CID=xxx; SEID=xxx") },
                    modifier = Modifier.fillMaxWidth().height(100.dp)
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = useProxy, onCheckedChange = { useProxy = it })
                    Spacer(Modifier.width(8.dp))
                    Text("使用代理", color = TextMid, fontSize = 13.sp)
                }
                if (useProxy) {
                    OutlinedTextField(
                        value = proxyUrl, onValueChange = { proxyUrl = it },
                        label = { Text("http://host:port 或 socks5://host:port") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = skipTls, onCheckedChange = { skipTls = it })
                    Spacer(Modifier.width(8.dp))
                    Text("跳过 HTTPS 证书校验（自签名）", color = TextMid, fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = locked, onCheckedChange = { locked = it })
                    Spacer(Modifier.width(8.dp))
                    Text("生物识别锁（进入需指纹/面容）", color = TextMid, fontSize = 13.sp)
                }

                if (busy) Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Ember, strokeWidth = 2.dp)
                    Text(if (testing) "正在连接…" else "正在保存…", color = TextMid, fontSize = 12.sp)
                }
                testMsg?.let {
                    Text(it,
                        color = if (it.startsWith("✓")) Color(0xFF4CD964) else Color(0xFFFF6B6B),
                        fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 测试连接：先探活再保存，避免「点了没反应」
                TextButton(
                    enabled = !busy && host.isNotBlank(),
                    onClick = {
                        testMsg = null
                        testing = true
                        scope.launch {
                            val r = runCatching { onTest(buildSource(), buildPassword()) }
                                .getOrElse { Result.failure(it) }
                            testing = false
                            feedback(r, "连接成功")
                        }
                    }
                ) { Text("测试连接", color = if (busy) TextLow else Ember) }

                Spacer(Modifier.width(4.dp))
                Button(
                    enabled = !busy && host.isNotBlank(),
                    onClick = {
                        testMsg = null
                        saving = true
                        scope.launch {
                            val r = runCatching { onSave(buildSource(), buildPassword()) }
                                .getOrElse { Result.failure(it) }
                            saving = false
                            if (r.isSuccess) {
                                onDismiss()
                            } else {
                                // 关键：失败必须说出来，不然用户以为按钮坏了
                                feedback(r, "已保存")
                            }
                        }
                    }
                ) { Text(if (saving) "保存中…" else "保存") }
            }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text("取消", color = TextMid) }
        },
        containerColor = InkRaised
    )
}
