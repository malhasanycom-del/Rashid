package com.rashid.iptv

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
    val text = httpGet(address.trim())
    val parsed = M3uParser.parse(text)
    if (parsed.isEmpty()) error("لم يعثر التطبيق على قنوات في رابط M3U. تحقق من الرابط وصلاحيته.")
    return parsed
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
    var sourceText by remember { mutableStateOf(prefs.getString("playlist_text", "").orEmpty()) }
    var channels by remember { mutableStateOf(M3uParser.parse(sourceText)) }
    var current by remember { mutableStateOf<Channel?>(null) }
    var message by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var m3uUrl by remember { mutableStateOf(prefs.getString("m3u_url", "").orEmpty()) }
    var xtreamHost by remember { mutableStateOf(prefs.getString("xtream_host", "").orEmpty()) }
    var xtreamUser by remember { mutableStateOf(prefs.getString("xtream_user", "").orEmpty()) }
    var xtreamPass by remember { mutableStateOf(prefs.getString("xtream_pass", "").orEmpty()) }
    var favorites by remember { mutableStateOf(prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()) }
    val player = remember(context) { ExoPlayer.Builder(context).build() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                loading = true
                try {
                    val text = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                            ?: error("تعذر قراءة الملف")
                    }
                    val parsed = M3uParser.parse(text)
                    if (parsed.isEmpty()) error("لم نعثر على قنوات. تحقق من ملف M3U.")
                    sourceText = text
                    channels = parsed
                    prefs.edit().putString("playlist_text", text).apply()
                    tab = "كل القنوات"
                    message = "تم تحميل ${parsed.size} قناة بنجاح"
                } catch (e: Exception) {
                    message = "تعذر فتح الملف: ${e.localizedMessage ?: "خطأ غير معروف"}"
                } finally { loading = false }
            }
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(current?.url) {
        current?.let {
            player.setMediaItem(MediaItem.fromUri(it.url))
            player.prepare()
            player.playWhenReady = true
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
                                        prefs.edit().remove("playlist_text").putString("m3u_url", m3uUrl.trim()).apply()
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
                                        prefs.edit().remove("playlist_text").putString("xtream_host", xtreamHost.trim())
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
                            sourceText = ""; channels = emptyList(); current = null; player.stop()
                            prefs.edit().remove("playlist_text").remove("m3u_url").remove("xtream_host")
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
                            val visible = channels.filter { channel ->
                                channel.name.contains(query, ignoreCase = true) &&
                                    (tab != "المفضلة" || favorites.contains(channel.url))
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
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(visible, key = { it.url }) { channel ->
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
                        Column(Modifier.weight(1.25f).fillMaxHeight().background(Panel, RoundedCornerShape(16.dp)).padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(current?.name ?: "المشغل", color = Color.White, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f), maxLines = 1)
                                Text(if (current == null) "جاهز" else "تشغيل", color = Accent, fontSize = 12.sp)
                            }
                            Spacer(Modifier.height(6.dp))
                            AndroidView(modifier = Modifier.fillMaxWidth().weight(1f),
                                factory = { ctx -> PlayerView(ctx).apply { player = player; useController = true; setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING) } },
                                update = { it.player = player })
                            Spacer(Modifier.height(4.dp))
                            Text("قد لا تعمل بعض الروابط التي تتطلب ترويسات أو مصادقة خاصة.", color = Muted, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
    if (message.isNotBlank()) AlertDialog(onDismissRequest = { message = "" }, title = { Text("راشد") },
        text = { Text(message) }, confirmButton = { TextButton(onClick = { message = "" }) { Text("حسنًا") } })
    if (loading) Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Accent)
    }
}
