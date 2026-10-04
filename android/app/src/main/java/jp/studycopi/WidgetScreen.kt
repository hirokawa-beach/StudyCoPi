package jp.studycopi

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable fun WidgetScreen(data: StudyData) {
    val context = LocalContext.current
    val manager = remember { AppWidgetManager.getInstance(context) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("ホーム画面ウィジェット", style = MaterialTheme.typography.headlineSmall)
            Text("6種類から選べます。追加後に長押ししてサイズを変更すると、表示も大きさに合わせて切り替わります。", modifier = Modifier.padding(top = 12.dp))
        }
        items(StudyWidget.providers) { (kind, provider) ->
            val content = widgetPresentation(data, kind)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(kind.title, style = MaterialTheme.typography.titleMedium)
                    Text("サイズの目安：${kind.sizes}", style = MaterialTheme.typography.labelSmall)
                    Text(content.label, style = MaterialTheme.typography.labelMedium)
                    Text(content.value, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                    content.rows.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    if (manager.isRequestPinAppWidgetSupported) OutlinedButton(onClick = {
                        manager.requestPinAppWidget(ComponentName(context, provider), null, null)
                    }, modifier = Modifier.fillMaxWidth()) { Text("${kind.title}を追加") }
                }
            }
        }
        item { Text("ホーム画面を長押し → ウィジェット → StudyCoPiからも追加できます。マス数とサイズはホームアプリによって異なります。記録済みの時間を表示し、保存時と↻ボタンで更新します。", style = MaterialTheme.typography.bodySmall) }
    }
}
