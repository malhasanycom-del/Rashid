package com.rashid.iptv

import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private val Bg = Color(0xFF080D16)
private val Panel = Color(0xFF111A29)
private val Accent = Color(0xFF35D0C4)
private val Muted = Color(0xFF9BA9BC)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Panel)) {
                RashidApp()
            }
        }
    }
}

private fun httpGet(address: String): String {
    val connection = URL(address).openConnection() as HttpURLConnection
    connection.connectTimeout = 15000
    connection.readTimeout = 20000
    connection.requestMethod = "GET"
    connection.setRequestProperty("User-Agent", "RashidIPTV/1.0")
    return try {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code !in 200..299) error("HTTP $code: ${body.take(180)}")
        body
    } finally {
        connection.disconnect()
    }
}

private fun loadM3uUrl(address: String): List<Channel> {
    val connection = URL(address.trim()).openConnection() as HttpURLConnection
    connection.connectTimeout = 15000
    connection.readTimeout = 20000
    connection.instanceFollowRedirects = true
    connection.setRequestProperty("User-Agent", "RashidIPTV/1.0")
    return try {
        val code = connection.responseCode
        if (code !in 200..299) error("فشل رابط M3U: HTTP $code")
        val parsed = connection.inputStream.bufferedReader(Charsets.UTF_8).use { M3uParser.parse(it) }
        if (parsed.isEmpty()) error("لم يعثر التطبيق على قنوات في رابط M3U. تحقق من الرابط وصلاحيته.")
        parsed
    } finally {
        connection.disconnect()
    }
}

private fun loadXtream(base: String, username: String, password: String): List<Channel> {
    val cleanBase = base.trim().trimEnd('/')
    require(cleanBase.startsWith("http://") || cleanBase.startsWith("https://")) {
        "أدخل عنوان الخادم كاملًا، مثل https://example.com:8080"
    }
    require(username.isNotBlank() && password.isNotBlank()) { "أدخل اسم المستخدم وكلمة المرور." }
    val user = URLEncoder.encode(username.trim(), "UTF-8")
    val pass = URLEncoder.encode(password, "UTF-8")
    val api = "$cleanBase/player_api.php?username=$user&password=$pass"
    val account = org.json.JSONObject(httpGet(api))
    val userInfo = account.optJSONObject("user_info")
    if (userInfo == null || userInfo.optString("auth") == "0") {
        error("بيانات Xtream غير صحيحة أو رفض الخادم تسجيل الدخول.")
    }
    val liveUrl = "$cleanBase/player_api.php?username=$user&password=$pass&action=get_live_streams"
    val arr = JSONArray(httpGet(liveUrl))
    val list = ArrayList<Channel>(arr.length())
    for (i in 0 until arr.length()) {
        val item = arr.optJSONObject(i) ?: continue
        val streamId = item.optString("stream_id")
        if (streamId.isBlank() || streamId == "null") continue
        val name = item.optString("name").ifBlank { "قناة ${i + 1}" }
        val category = item.optString("category_name").ifBlank { "بث مباشر" }
        val ext = item.optString("container_extension").ifBlank { "ts" }
        val streamUrl = "$cleanBase/live/$user/$pass/$streamId.$ext"
        list += Channel(list.size + 1, name, streamUrl, item.optString("stream_icon"), category, item.optString("epg_channel_id"))
    }
    if (list.isEmpty()) error("تم الاتصال بالخادم لكن لم تُرجع واجهة Xtream أي قنوات مباشرة.")
    return list
}

@Composable
private fun RashidApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("rashid_prefs", android.content.Context.MODE_PRIVATE) }
    var tab by remember { mutableStateOf("الرئيسية") }
    var query by remember { mutableStateOf("") }
    var selectedGroup by remember { mutableStateOf("الكل") }
    var sourceText by remember { mutableStateOf("") }
    var channels by remember { mutableStateOf(emptyList<Channel>()) }
    val groupNames = remember(channels) {
        listOf("الكل") + channels.asSequence()
            .map { it.group.trim().ifBlank { "غير مصنف" } }
            .distinct()
            .sorted()
            .toList()
    }
    var current by remember { mutableStateOf<Channel?>(null) }
    var message by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var fullScreen by remember { mutableStateOf(false) }
    var playbackError by remember { mutableStateOf("") }
    var m3uUrl by remember { mutableStateOf(prefs.getString("m3u_url", "").orEmpty()) }
    var xtreamHost by remember { mutableStateOf(prefs.getString("xtream_host", "").orEmpty()) }
    var xtreamUser by remember { mutableStateOf(prefs.getString("xtream_user", "").orEmpty()) }
    var xtreamPass by remember { mutableStateOf(prefs.getString("xtream_pass", "").orEmpty()) }
    var favorites by remember { mutableStateOf(prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()) }
    val exoPlayer = remember(context) {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("RashidIPTV/1.0 (Android)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20000)
            .setReadTimeoutMs(30000)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(httpFactory))
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        playbackError = when (error.errorCode) {
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "تعذر الاتصال بمصدر البث. تحقق من الإنترنت أو رابط القناة."
                            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "صيغة البث غير مدعومة أو أن رابط القناة غير صحيح."
                            else -> "تعذر تشغيل القناة: \${error.errorCodeName}"
                        }
                    }
                })
            }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                loading = true
                try {
                    try { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
                    val parsed = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            BufferedReader(InputStreamReader(input, Charsets.UTF_8), 64 * 1024).use { M3uParser.parse(it) }
                        } ?: error("تعذر قراءة الملف")
                    }
                    if (parsed.isEmpty()) error("لم نعثر على قنوات. تحقق من ملف M3U.")
                    sourceText = ""
                    channels = parsed
                    prefs.edit().putString("local_playlist_uri", uri.toString()).remove("playlist_text")
                        .remove("m3u_url").remove("xtream_host").remove("xtream_user").remove("xtream_pass").apply()
                    tab = "كل القنوات"
                    message = "تم تحميل ${parsed.size} قناة بنجاح"
                } catch (e: Exception) {
                    message = "تعذر فتح الملف: ${e.localizedMessage ?: "خطأ غير معروف"}"
                } finally { loading = false }
            }
        }
    }
    LaunchedEffect(Unit) {
        val savedUrl = prefs.getString("m3u_url", "").orEmpty()
        val savedHost = prefs.getString("xtream_host", "").orEmpty()
        val savedUser = prefs.getString("xtream_user", "").orEmpty()
        val savedPass = prefs.getString("xtream_pass", "").orEmpty()
        val savedUri = prefs.getString("local_playlist_uri", "").orEmpty()
        if (savedUrl.isNotBlank() || savedHost.isNotBlank() || savedUri.isNotBlank()) {
            loading = true
            try {
                val restored = withContext(Dispatchers.IO) {
                    when {
                        savedUrl.isNotBlank() -> loadM3uUrl(savedUrl)
                        savedHost.isNotBlank() -> loadXtream(savedHost, savedUser, savedPass)
                        else -> context.contentResolver.openInputStream(Uri.parse(savedUri))?.use { input ->
                            BufferedReader(InputStreamReader(input, Charsets.UTF_8), 64 * 1024).use { M3uParser.parse(it) }
                        } ?: emptyList()
                    }
                }
                channels = restored
            } catch (e: Exception) {
                message = "تعذر استعادة المصدر المحفوظ: ${e.localizedMessage ?: "أعد تحميل القائمة من قسم المصادر"}"
            } finally {
                loading = false
            }
        }
    }
    DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }
    LaunchedEffect(current?.url) {
        current?.let {
            playbackError = ""
            val itemBuilder = MediaItem.Builder().setUri(it.url)
            if (it.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) {
                itemBuilder.setMimeType("application/x-mpegURL")
            }
            exoPlayer.setMediaItem(itemBuilder.build())
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }
    BackHandler(enabled = tab != "الرئيسية") { tab = "الرئيسية" }

    Row(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.width(190.dp).fillMaxHeight().background(Panel).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("راشد", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Accent)
            Text("IPTV PLAYER", fontSize = 11.sp, color = Muted)
            Spacer(Modifier.height(8.dp))
            listOf("الرئيسية", "كل القنوات", "المفضلة", "المصادر", "الإعدادات").forEach { item ->
                var focused by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth()
                    .background(if (tab == item || focused) Color(0xFF203347) else Color.Transparent, RoundedCornerShape(10.dp))
                    .onFocusChanged { focused = it.isFocused }.clickable { tab = item }.focusable()
                    .padding(horizontal = 10.dp, vertical = 12.dp)) {
                    Text(item, color = if (tab == item || focused) Accent else Color.White, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.weight(1f))
            Text("${channels.size} قناة", color = Muted, fontSize = 12.sp)
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 20.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tab, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("مشغل القنوات • واجهة أفقية للتحكم بالريموت", color = Muted, fontSize = 12.sp)
                }
                if (tab == "المصادر") Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("استيراد ملف") }
            }
            Spacer(Modifier.height(12.dp))
            when (tab) {
                "المصادر" -> {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column(Modifier.weight(1f).fillMaxHeight().background(Panel, RoundedCornerShape(16.dp)).padding(14.dp)) {
                            Text("رابط M3U / M3U8", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Text("الصق رابط القائمة المباشر", color = Muted, fontSize = 12.sp)
                            OutlinedTextField(value = m3uUrl, onValueChange = { m3uUrl = it },
                                modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("https://.../playlist.m3u") })
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = {
                                scope.launch {
                                    loading = true
                                    try {
                                        val parsed = withContext(Dispatchers.IO) { loadM3uUrl(m3uUrl) }
                                        channels = parsed
                                        sourceText = ""
                                        prefs.edit().remove("playlist_text").remove("local_playlist_uri")
                                            .remove("xtream_host").remove("xtream_user").remove("xtream_pass")
                                            .putString("m3u_url", m3uUrl.trim()).apply()
                                        tab = "كل القنوات"
                                        message = "تم تحميل ${parsed.size} قناة من رابط M3U"
                                    } catch (e: Exception) {
                                        message = "فشل تحميل M3U: ${e.localizedMessage ?: "تحقق من الرابط والإنترنت"}"
                                    } finally { loading = false }
                                }
                            }, enabled = m3uUrl.isNotBlank() && !loading, modifier = Modifier.focusable()) { Text("تحميل الرابط") }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.focusable()) { Text("اختيار ملف محلي") }
                        }
                        Column(Modifier.weight(1f).fillMaxHeight().background(Panel, RoundedCornerShape(16.dp)).padding(14.dp)) {
                            Text("Xtream Codes", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Text("أدخل بيانات مزوّد الخدمة", color = Muted, fontSize = 12.sp)
                            OutlinedTextField(value = xtreamHost, onValueChange = { xtreamHost = it },
                                modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("عنوان الخادم مع المنفذ") })
                            OutlinedTextField(value = xtreamUser, onValueChange = { xtreamUser = it },
                                modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("اسم المستخدم") })
                            OutlinedTextField(value = xtreamPass, onValueChange = { xtreamPass = it },
                                modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("كلمة المرور") })
                            Spacer(Modifier.height(6.dp))
                            Button(onClick = {
                                scope.launch {
                                    loading = true
                                    try {
                                        val parsed = withContext(Dispatchers.IO) { loadXtream(xtreamHost, xtreamUser, xtreamPass) }
                                        channels = parsed
                                        sourceText = ""
                                        prefs.edit().remove("playlist_text").remove("local_playlist_uri").remove("m3u_url")
                                            .putString("xtream_host", xtreamHost.trim())
                                            .putString("xtream_user", xtreamUser.trim()).putString("xtream_pass", xtreamPass).apply()
                                        tab = "كل القنوات"
                                        message = "تم الاتصال وتحميل ${parsed.size} قناة مباشرة"
                                    } catch (e: Exception) {
                                        message = "فشل اتصال Xtream: ${e.localizedMessage ?: "تحقق من البيانات"}"
                                    } finally { loading = false }
                                }
                            }, enabled = xtreamHost.isNotBlank() && xtreamUser.isNotBlank() && xtreamPass.isNotBlank() && !loading,
                                modifier = Modifier.focusable()) { Text("اتصال وتحميل القنوات") }
                            Text("استخدم بيانات الاشتراك التي يوفرها مزوّدك فقط.", color = Muted, fontSize = 10.sp)
                        }
                    }
                }
                "الإعدادات" -> {
                    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(20.dp)) {
                        Icon(Icons.Default.Settings, contentDescription = null, tint = Accent)
                        Text("إعدادات راشد", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Text("اتجاه الشاشة: أفقي فقط", color = Accent)
                        Text("المصادر المحفوظة على الجهاز تبقى متاحة بعد إغلاق التطبيق.", color = Muted)
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = {
                            sourceText = ""; channels = emptyList(); current = null; exoPlayer.stop()
                            prefs.edit().remove("playlist_text").remove("local_playlist_uri").remove("m3u_url").remove("xtream_host")
                                .remove("xtream_user").remove("xtream_pass").apply()
                            message = "تم حذف بيانات المصادر المحفوظة"
                        }) { Text("حذف بيانات المصادر") }
                    }
                }
                else -> {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column(Modifier.weight(1.05f).fillMaxHeight()) {
                            OutlinedTextField(value = query, onValueChange = { query = it },
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                                label = { Text("ابحث عن قناة") }, leadingIcon = { Icon(Icons.Default.Search, null) })
                            Spacer(Modifier.height(8.dp))
                            if (tab == "كل القنوات" || tab == "المفضلة") {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(groupNames, key = { it }) { group ->
                                        FilterChip(
                                            selected = selectedGroup == group,
                                            onClick = { selectedGroup = group },
                                            label = { Text(group, maxLines = 1) }
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                            val visible = remember(channels, query, tab, favorites, selectedGroup) {
                                channels.filter { channel ->
                                    channel.name.contains(query, ignoreCase = true) &&
                                        (tab != "المفضلة" || favorites.contains(channel.url)) &&
                                        (selectedGroup == "الكل" || channel.group.trim().ifBlank { "غير مصنف" } == selectedGroup)
                                }
                            }
                            if (visible.isEmpty()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Default.LiveTv, contentDescription = null, tint = Muted, modifier = Modifier.size(44.dp))
                                        Text(if (channels.isEmpty()) "أضف رابط M3U أو Xtream من المصادر" else "لا توجد نتائج", color = Muted)
                                        if (channels.isEmpty()) Button(onClick = { tab = "المصادر" }) { Text("إضافة مصدر") }
                                    }
                                }
                            } else {
                                val groupedVisible = remember(visible) {
                                    visible.groupBy { it.group.trim().ifBlank { "غير مصنف" } }.toSortedMap()
                                }
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    groupedVisible.forEach { (groupName, groupChannels) ->
                                        item(key = "group-header:$groupName") {
                                            Text(
                                                "$groupName  •  ${groupChannels.size}",
                                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 3.dp)
                                                    .background(Color(0xFF203347), RoundedCornerShape(8.dp)).padding(8.dp),
                                                color = Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp
                                            )
                                        }
                                        items(groupChannels, key = { it.url }) { channel ->
                                            Row(Modifier.fillMaxWidth()
                                                .background(if (current?.url == channel.url) Color(0xFF203B48) else Panel, RoundedCornerShape(10.dp))
                                                .clickable { current = channel }.focusable()
                                                .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(channel.name, color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1)
                                                    Text(channel.group, color = Muted, fontSize = 11.sp, maxLines = 1)
                                                }
                                                TextButton(onClick = {
                                                    favorites = if (favorites.contains(channel.url)) favorites - channel.url else favorites + channel.url
                                                    prefs.edit().putStringSet("favorites", favorites).apply()
                                                }) { Icon(Icons.Default.Favorite, contentDescription = "المفضلة",
                                                    tint = if (favorites.contains(channel.url)) Accent else Muted) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Column(Modifier.weight(1.25f).fillMaxHeight().background(Panel, RoundedCornerShape(16.dp)).padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(current?.name ?: "المشغل", color = Color.White, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f), maxLines = 1)
                                TextButton(onClick = { fullScreen = !fullScreen }) {
                                    Text(if (fullScreen) "تصغير" else "ملء الشاشة")
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            AndroidView(modifier = Modifier.fillMaxWidth().weight(1f),
                                factory = { ctx -> PlayerView(ctx).apply {
                                    this.player = exoPlayer
                                    useController = true
                                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                                    setKeepContentOnPlayerReset(true)
                                } },
                                update = { it.player = exoPlayer })
                            if (playbackError.isNotBlank()) {
                                Text(playbackError, color = Color(0xFFFF8A80), fontSize = 12.sp)
                                TextButton(onClick = {
                                    playbackError = ""
                                    exoPlayer.prepare()
                                    exoPlayer.playWhenReady = true
                                }) { Text("إعادة المحاولة") }
                            } else {
                                Text("إذا تعذر التشغيل، تحقق من صلاحية الرابط ودعم صيغة البث.", color = Muted, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
    }
    if (message.isNotBlank()) AlertDialog(onDismissRequest = { message = "" }, title = { Text("راشد") },
        text = { Text(message) }, confirmButton = { TextButton(onClick = { message = "" }) { Text("حسنًا") } })
    if (loading) Box(Modifier.fillMaxSize().background(Color(0xAA080D16)), contentAlignment = Alignment.Center) {
        Column(Modifier.background(Panel, RoundedCornerShape(18.dp)).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Accent)
            Spacer(Modifier.height(14.dp))
            Text("جارٍ تحميل القائمة وتحليل القنوات…", color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text("قد يستغرق ذلك وقتًا حسب حجم الملف وسرعة الإنترنت", color = Muted, fontSize = 12.sp)
        }
    }
}
