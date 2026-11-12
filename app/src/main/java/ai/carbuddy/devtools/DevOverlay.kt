package ai.carbuddy.devtools

import ai.carbuddy.trip.RuntimeUi
import ai.carbuddy.trip.TripRuntime
import ai.carbuddy.ui.components.Pill
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Color(0xFFD7E3F4), lineHeight = 18.sp)
private val hmsFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
private const val DEV_NOTE = "개발자 뷰 · 실제 앱은 주행 중 화면을 띄우지 않아요"

/** Debug-build only (SPEC 15장 DevOverlay): live state, recent events, captured episodes, replay panel. */
@Composable
fun BoxScope.DevOverlay(ui: RuntimeUi, runtime: TripRuntime) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
        if (open) {
            Column(
                Modifier.width(460.dp).heightIn(max = 620.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xEE050B16))
                    .border(1.dp, Color(0xFF2E4469), RoundedCornerShape(16.dp)).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) { DevContent(ui, runtime) }
            Spacer(Modifier.height(8.dp))
        }
        Box(Modifier.clip(RoundedCornerShape(999.dp)).clickable { open = !open }) {
            Pill(if (open) "DEV ▾" else "DEV ▴", Color(0xFF203252), Color(0xFF9FE3EA))
        }
    }
}

/** While driving the real app shows nothing; debug builds show this for the demo video. */
@Composable
fun DrivingDevView(ui: RuntimeUi, runtime: TripRuntime? = null) {
    Column(Modifier.fillMaxSize().background(Color(0xFF050B16)).padding(32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(DEV_NOTE, style = mono.copy(fontSize = 16.sp, color = Color(0xFF9FE3EA)))
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) { stateBlock(ui) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("포착된 에피소드", style = mono.copy(fontWeight = FontWeight.Bold, fontSize = 16.sp))
                ui.captured.forEach { c ->
                    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFFF2A541)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text("● $c", style = mono.copy(color = Color(0xFF0B1528), fontWeight = FontWeight.Bold, fontSize = 18.sp))
                    }
                }
                ui.lastRecap?.let { Text("🔊 $it", style = mono.copy(fontSize = 15.sp, color = Color(0xFFF6F3EC))) }
            }
        }
        Text("최근 이벤트", style = mono.copy(fontWeight = FontWeight.Bold))
        ui.recentEvents.take(8).forEach { Text(it, style = mono) }
        runtime?.let { ReplayPanel(ui, it) }
    }
}

@Composable
private fun DevContent(ui: RuntimeUi, runtime: TripRuntime) {
    stateBlock(ui)
    if (ui.captured.isNotEmpty()) Text("포착: " + ui.captured.joinToString(" · "), style = mono.copy(color = Color(0xFFF2A541)))
    Text("미지원 신호: " + ui.unsupported.ifEmpty { listOf("없음") }.joinToString(", "), style = mono.copy(color = Color(0xFF8FA3C0)))
    ReplayPanel(ui, runtime)
    Text("최근 이벤트", style = mono.copy(fontWeight = FontWeight.Bold))
    ui.recentEvents.forEach { Text(it, style = mono) }
}

@Composable
private fun stateBlock(ui: RuntimeUi) {
    val t = if (ui.now > 0) Instant.ofEpochMilli(ui.now).atZone(ZoneId.of("Asia/Seoul")).format(hmsFmt) else "-"
    Text("${ui.tripState}   $t", style = mono.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF5FD4E0)))
    Text(
        "gear=${ui.gear}  belt=${ui.belt}  ${"%.0f".format(ui.speedKmh)}km/h\n" +
            "dest=${ui.destName ?: "-"}  remain=${ui.remainSec?.let { "${it / 60}m${it % 60}s" } ?: "-"}\n" +
            "seats=${ui.passengers}  episodes=${ui.episodes.size}  canShowUi=${ui.canShowUi}\n" +
            "trip=${ui.trip?.id?.take(8) ?: "-"}  ${"%.1f".format(ui.trip?.distanceKm ?: 0.0)}km",
        style = mono,
    )
}

@Composable
private fun ReplayPanel(ui: RuntimeUi, runtime: TripRuntime) {
    val r = ui.replay
    Text("재생 패널", style = mono.copy(fontWeight = FontWeight.Bold))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (name in listOf("scenario_a.jsonl", "commute_b.jsonl")) {
            DevChip(name.removeSuffix(".jsonl"), r.running && r.name == name) { runtime.startReplay(name, r.speed, r.useLogTime) }
        }
        DevChip("■ stop", false) { runtime.stopReplay() }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (sp in listOf(1f, 10f, 60f, 300f)) DevChip("${sp.toInt()}x", r.speed == sp) { runtime.setReplaySpeed(sp) }
        DevChip("wall clock", false) { runtime.useWallClock() }
    }
    if (r.running) Text("▶ ${r.name} @${r.speed.toInt()}x", style = mono.copy(color = Color(0xFF9FE3EA)))
    // 영상 반복 촬영용: DB 전체 삭제 후 앱 재시작 (첫 실행 화면부터 다시)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var confirm by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var exported by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DevChip(if (confirm) "정말 초기화?" else "데이터 초기화", confirm) {
            if (!confirm) confirm = true
            else runtime.resetAllData {
                ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let { i ->
                    ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
                }
                Runtime.getRuntime().exit(0)
            }
        }
        if (confirm) DevChip("취소", false) { confirm = false }
        // 실제 주행 → 재생 로그 (adb pull 후 docs/replay에)
        DevChip("마지막 여정 내보내기", false) { runtime.exportLastTrip { exported = it ?: "내보낼 여정 없음" } }
    }
    exported?.let { Text(it, style = mono.copy(color = Color(0xFF9FE3EA), fontSize = 12.sp)) }
}

@Composable
private fun DevChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).background(if (selected) Color(0xFF5FD4E0) else Color(0xFF1B2D4F))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = mono.copy(color = if (selected) Color(0xFF0B1528) else Color(0xFFD7E3F4), fontWeight = FontWeight.Bold)) }
}

