package jp.studycopi

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable fun WidgetScreen(data: StudyData) {
    val context = LocalContext.current
    val manager = remember { AppWidgetManager.getInstance(context) }
    val content = widgetContent(data)
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("ホーム画面ウィジェット", style = MaterialTheme.typography.headlineSmall)
        Text("今日の予定と、記録済みの勉強時間をホーム画面から確認できます。タップすると今日の学習が開きます。")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(content.date, style = MaterialTheme.typography.titleMedium)
                Text("今日の勉強時間 · 記録済み", style = MaterialTheme.typography.labelMedium)
                Text(content.studied, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                content.rows.forEach { Text(it) }
                if (content.rows.isEmpty()) Text("今日の予定はありません")
                if (content.remaining > 0) Text("ほか${content.remaining}件")
            }
        }
        if (manager.isRequestPinAppWidgetSupported) Button(onClick = {
            manager.requestPinAppWidget(ComponentName(context, StudyWidget::class.java), null, null)
        }, modifier = Modifier.fillMaxWidth()) { Text("ホーム画面に追加") }
        Text("ホーム画面の空いている場所を長押し → ウィジェット → StudyCoPiからも追加できます。記録の保存時に更新され、↻ボタンでも更新できます。", style = MaterialTheme.typography.bodySmall)
    }
}
