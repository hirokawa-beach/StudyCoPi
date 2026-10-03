package jp.studycopi

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject

data class GuideContent(val version: Int, val steps: List<JSONObject>, val topics: List<JSONObject>) {
    companion object {
        fun load(context: Context): GuideContent {
            val root = JSONObject(context.assets.open("study-guide.json").bufferedReader().use { it.readText() })
            fun list(key: String) = root.getJSONArray(key).let { array -> (0 until array.length()).map(array::getJSONObject) }
            return GuideContent(root.getInt("version"), list("steps"), list("topics"))
        }
        fun seen(context: Context, version: Int) = context.getSharedPreferences("study_guide", Context.MODE_PRIVATE).getInt("version", 0) >= version
        fun markSeen(context: Context, version: Int) { context.getSharedPreferences("study_guide", Context.MODE_PRIVATE).edit().putInt("version", version).apply() }
    }
}

val LocalGuideBounds = staticCompositionLocalOf<(String, Rect) -> Unit> { { _, _ -> } }
fun Modifier.guideTarget(key: String): Modifier = composed {
    val report = LocalGuideBounds.current
    this.testTag("guide-target-$key").onGloballyPositioned { report(key, it.boundsInWindow()) }
}

@Composable fun StudyGuideDialog(content: GuideContent, index: Int, target: Rect?, move: (Int) -> Unit, close: () -> Unit) {
    val step = content.steps[index]
    val density = LocalDensity.current
    var panelHeight by remember { mutableIntStateOf(0) }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f) }
        // A modal window blocks actions under the tour; only the real controls are spotlighted.
        BoxWithConstraints(Modifier.fillMaxSize().testTag("study-guide")) {
            val gap = with(density) { 16.dp.toPx() }
            val screenHeight = with(density) { maxHeight.toPx() }
            val screenWidth = with(density) { maxWidth.toPx() }
            val safeTop = with(density) { WindowInsets.safeDrawing.getTop(this).toFloat() }
            val safeBottom = with(density) { WindowInsets.safeDrawing.getBottom(this).toFloat() }
            val hole = target?.inflate(with(density) { 6.dp.toPx() })
            val below = target != null && target.bottom + gap + panelHeight < screenHeight - safeBottom - gap
            val y = if (target == null) safeTop + gap else if (below) target.bottom + gap else (target.top - panelHeight - gap).coerceAtLeast(safeTop + gap)
            val panelWidth = with(density) { (maxWidth - 32.dp).coerceAtMost(400.dp).toPx() }
            val x = ((target?.center?.x ?: screenWidth / 2) - panelWidth / 2).coerceIn(gap, (screenWidth - panelWidth - gap).coerceAtLeast(gap))
            Canvas(Modifier.fillMaxSize()) {
                val shade = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(0f, 0f, size.width, size.height))
                    hole?.let { addRoundRect(RoundRect(it, CornerRadius(12.dp.toPx()))) }
                }
                drawPath(shade, Color.Black.copy(alpha = .55f))
                hole?.let { drawRoundRect(Color.White, it.topLeft, it.size, CornerRadius(12.dp.toPx()), style = Stroke(2.dp.toPx())) }
            }
            Surface(Modifier.offset { IntOffset(x.toInt(), y.toInt()) }.width(with(density) { panelWidth.toDp() })
                .heightIn(max = with(density) { (if (target != null) maxOf(target.top - safeTop, screenHeight - safeBottom - target.bottom) - gap * 2 else screenHeight - safeTop - safeBottom - gap * 2).coerceAtLeast(160.dp.toPx()).toDp() })
                .onSizeChanged { panelHeight = it.height }, shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("使い方", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = close) { Text("あとで", style = MaterialTheme.typography.labelMedium) }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${index + 1} / ${content.steps.size}", style = MaterialTheme.typography.labelMedium)
                        LinearProgressIndicator(progress = { (index + 1).toFloat() / content.steps.size }, modifier = Modifier.weight(1f).height(6.dp))
                    }
                    key(index) {
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(step.getString("coachTitle"), style = MaterialTheme.typography.titleLarge)
                            Text(step.getString("coach"), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Text((if (below) "↑ " else "↓ ") + step.getString("targetLabel"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 20.dp))
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { move(index - 1) }, enabled = index > 0, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("戻る") }
                        Button(onClick = { if (index == content.steps.lastIndex) close() else move(index + 1) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(if (index == content.steps.lastIndex) "使い始める" else "次へ") }
                    }
                }
            }
        }
    }
}

@Composable fun StudyHelpScreen(guide: GuideContent, replay: () -> Unit, navigate: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("使い方", style = MaterialTheme.typography.headlineSmall)
            Text("予定を立てる、勉強する、振り返る。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
            Button(onClick = replay) { Text("チュートリアルを見る") }
            Text("知りたい操作を選ぶ", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
        }
        items(guide.topics, key = { it.getString("key") }) { topic ->
            val key = topic.getString("key")
            Card(onClick = { expanded = if (expanded == key) "" else key }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(16.dp).semantics { stateDescription = if (expanded == key) "展開中" else "折りたたみ中" }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(topic.getString("title"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Icon(if (expanded == key) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                    }
                    if (expanded == key) {
                        val lines = topic.getJSONArray("body")
                        repeat(lines.length()) { Text(lines.getString(it), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp)) }
                        if (topic.optString("android").isNotEmpty()) Text(topic.getString("android"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                        FilledTonalButton(onClick = { navigate(topic.getString("route")) }, modifier = Modifier.padding(top = 12.dp)) { Text(topic.getString("action")) }
                    }
                }
            }
        }
    }
}
