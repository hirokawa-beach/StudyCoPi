package jp.studycopi

import android.appwidget.AppWidgetManager
import android.os.Build
import android.os.Bundle
import android.util.SizeF

/** Launcher dimensions are dp. Exact sizes prevent a small layout filling a larger host. */
@Suppress("DEPRECATION")
fun widgetSizes(options: Bundle, kind: WidgetKind): List<SizeF> {
    if (Build.VERSION.SDK_INT >= 31) {
        val sizes = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            .orEmpty().filter { it.width.isFinite() && it.height.isFinite() && it.width >= 60 && it.height >= 50 }.distinct()
        if (sizes.isNotEmpty()) return sizes.take(16)
    }
    val defaultWidth = if (kind in listOf(WidgetKind.TODAY, WidgetKind.PLANS, WidgetKind.NEXT)) 350 else 170
    val defaultHeight = if (kind in listOf(WidgetKind.TODAY, WidgetKind.PLANS)) 280 else 140
    val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, defaultWidth).coerceAtLeast(60)
    val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, defaultHeight).coerceAtLeast(50)
    val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minWidth).coerceAtLeast(minWidth)
    val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minHeight).coerceAtLeast(minHeight)
    return listOf(SizeF(minWidth.toFloat(), maxHeight.toFloat()), SizeF(maxWidth.toFloat(), minHeight.toFloat())).distinct()
}
