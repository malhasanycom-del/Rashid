package com.rashid.iptv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Bg = Color(0xFF080D16)
private val Panel = Color(0xFF111A29)
private val Accent = Color(0xFF35D0C4)
private val Muted = Color(0xFF9BA9BC)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Panel)) {
                RashidApp()
            }
        }
    }
}

@Composable
private fun RashidApp() {
    var tab by remember { mutableStateOf("الرئيسية") }
    var query by remember { mutableStateOf("") }
    var channels by remember { mutableStateOf(emptyList<Channel>()) }
    var current by remember { mutableStateOf<Channel?>(null) }
    BackHandler(enabled = tab != "الرئيسية") { tab = "الرئيسية" }

    Row(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.width(210.dp).fillMaxHeight().background(Panel).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("راشد", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Accent)
            Text("IPTV", fontSize = 13.sp, color = Muted)
            Spacer(Modifier.height(18.dp))
            listOf("الرئيسية", "كل القنوات", "المفضلة", "الأخيرة", "المصادر", "الإعدادات").forEach { item ->
                var focused by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth()
                    .background(if (tab == item || focused) Color(0xFF203347) else Color.Transparent, RoundedCornerShape(12.dp))
                    .onFocusChanged { focused = it.isFocused }.clickable { tab = item }.focusable()
                    .padding(horizontal = 12.dp, vertical = 13.dp)) {
                    Text(item, color = if (tab == item || focused) Accent else Color.White, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.weight(1f))
            Text("مشروع مستقل", color = Muted, fontSize = 11.sp)
        }
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tab, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("تجربة تلفاز عربية • تحكم بالريموت", color = Muted, fontSize = 13.sp)
                }
                Icon(Icons.Default.LiveTv, contentDescription = null, tint = Accent, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f).height(170.dp).background(Panel, RoundedCornerShape(18.dp)).padding(20.dp),
                    verticalArrangement = Arrangement.SpaceBetween) {
                    Text("شاهد ما تحب", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(current?.name ?: "مرحبًا بك في راشد IPTV", color = Accent, fontSize = 18.sp)
                    Text("أضف مصادر قنواتك لتبدأ", color = Muted, fontSize = 12.sp)
                }
                Column(Modifier.weight(0.8f).height(170.dp).background(Panel, RoundedCornerShape(18.dp)).padding(16.dp)) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = Accent, modifier = Modifier.size(26.dp))
                    Text("بحث القنوات", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("اسم القناة") }, singleLine = true)
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(if (tab == "المصادر") "إدارة المصادر" else "القنوات", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(10.dp))
            if (channels.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.LiveTv, contentDescription = null, tint = Muted, modifier = Modifier.size(54.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("لا توجد قنوات محمّلة بعد", color = Muted)
                        Text("سيتم ربط استيراد M3U والمشغّل في المرحلة التالية", color = Accent, fontSize = 13.sp)
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(channels.filter { it.name.contains(query, ignoreCase = true) }) { channel ->
                        Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp))
                            .clickable { current = channel }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(channel.id.toString(), color = Accent, modifier = Modifier.width(42.dp))
                            Column(Modifier.weight(1f)) {
                                Text(channel.name, fontWeight = FontWeight.Medium, color = Color.White)
                                Text(channel.group, color = Muted, fontSize = 12.sp)
                            }
                            Text("اختيار", color = Accent)
                        }
                    }
                }
            }
        }
    }
}
