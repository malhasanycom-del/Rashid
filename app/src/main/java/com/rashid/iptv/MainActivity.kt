package com.rashid.iptv

import android.net.Uri
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
import android.widget.Toast

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

@Composable
private fun RashidApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("rashid_prefs", android.content.Context.MODE_PRIVATE) }
    var tab by remember { mutableStateOf("الرئيسية") }
    var query by remember { mutableStateOf("") }
    var sourceText by remember { mutableStateOf(prefs.getString("playlist_text", "").orEmpty()) }
    var channels by remember { mutableStateOf(M3uParser.parse(sourceText)) }
    var current by remember { mutableStateOf<Channel?>(null) }
    var message by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var favorites by remember { mutableStateOf(prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()) }
    val player = remember(context) { ExoPlayer.Builder(context).build() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                loading = true
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: error("تعذر قراءة الملف")
                val parsed = M3uParser.parse(text)
                if (parsed.isEmpty()) {
                    message = "لم نعثر على قنوات. تأكد أن الملف بصيغة M3U صحيحة."
                } else {
                    sourceText = text
                    channels = parsed
                    prefs.edit().putString("playlist_text", text).apply()
                    tab = "كل القنوات"
                    message = "تم تحميل ${parsed.size} قناة بنجاح"
                }
            } catch (e: Exception) {
                message = "تعذر فتح الملف: ${e.localizedMessage ?: "خطأ غير معروف"}"
            } finally {
                loading = false
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
                if (tab == "المصادر") {
                    Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("استيراد ملف M3U") }
                }
            }
            Spacer(Modifier.height(12.dp))
            when (tab) {
                "المصادر" -> {
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f).background(Panel, RoundedCornerShape(16.dp)).padding(18.dp)) {
                            Text("مصادر القنوات", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(10.dp))
                            Text("استورد ملف قائمة تشغيل M3U أو M3U8 من جهازك. سيُحفظ محتوى القائمة محليًا لتبقى بعد إغلاق التطبيق.", color = Muted)
                            Spacer(Modifier.height(14.dp))
                            Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.focusable()) { Text("اختيار ملف من الجهاز") }
                            Spacer(Modifier.height(8.dp))
                            Text("الحالة: ${if (channels.isEmpty()) "لا توجد قائمة" else "تم تحميل ${channels.size} قناة"}", color = Accent)
                        }
                        Column(Modifier.weight(1f).background(Panel, RoundedCornerShape(16.dp)).padding(18.dp)) {
                            Text("مصدر الشبكة", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("إضافة روابط M3U عبر الإنترنت وXtream Codes وStalker Portal غير مفعّلة بعد في هذه النسخة.", color = Muted)
                        }
                    }
                }
                "الإعدادات" -> {
                    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(20.dp)) {
                        Icon(Icons.Default.Settings, contentDescription = null, tint = Accent)
                        Text("إعدادات راشد", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Text("اتجاه الشاشة: أفقي فقط", color = Accent)
                        Text("مصدر القنوات المحفوظ: ${if (sourceText.isBlank()) "لا يوجد" else "قائمة محلية"}", color = Muted)
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = {
                            sourceText = ""
                            channels = emptyList()
                            current = null
                            prefs.edit().remove("playlist_text").apply()
                            player.stop()
                            message = "تم حذف قائمة القنوات المحفوظة"
                        }) { Text("حذف قائمة القنوات") }
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
                                        Text(if (channels.isEmpty()) "استورد ملف M3U من قسم المصادر" else "لا توجد نتائج", color = Muted)
                                        if (channels.isEmpty()) Button(onClick = { tab = "المصادر" }) { Text("إضافة قائمة") }
                                    }
                                }
                            } else {
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(visible, key = { it.url }) { channel ->
                                        Row(Modifier.fillMaxWidth()
                                            .background(if (current?.url == channel.url) Color(0xFF203B48) else Panel, RoundedCornerShape(10.dp))
                                            .clickable { current = channel }.focusable()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text(channel.name, color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1)
                                                Text(channel.group, color = Muted, fontSize = 11.sp, maxLines = 1)
                                            }
                                            TextButton(onClick = {
                                                favorites = if (favorites.contains(channel.url)) favorites - channel.url else favorites + channel.url
                                                prefs.edit().putStringSet("favorites", favorites).apply()
                                            }) {
                                                Icon(Icons.Default.Favorite, contentDescription = "المفضلة",
                                                    tint = if (favorites.contains(channel.url)) Accent else Muted)
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
                                Text(if (current == null) "جاهز" else "تشغيل", color = Accent, fontSize = 12.sp)
                            }
                            Spacer(Modifier.height(6.dp))
                            AndroidView(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true; setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING) } },
                                update = { it.player = player }
                            )
                            Spacer(Modifier.height(4.dp))
                            Text("يدعم تشغيل روابط الفيديو المتوافقة مع Media3؛ قد لا تعمل بعض الروابط التي تتطلب ترويسات أو مصادقة خاصة.", color = Muted, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
    if (message.isNotBlank()) {
        AlertDialog(onDismissRequest = { message = "" }, title = { Text("راشد") },
            text = { Text(message) }, confirmButton = { TextButton(onClick = { message = "" }) { Text("حسنًا") } })
    }
    if (loading) {
        Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Accent)
        }
    }
}
