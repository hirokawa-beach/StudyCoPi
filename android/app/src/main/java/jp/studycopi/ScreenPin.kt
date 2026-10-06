package jp.studycopi

import android.app.ActivityManager
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

fun Context.isScreenPinned(): Boolean =
    getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

// The OS confirmation is asynchronous and need not trigger a window-focus callback.
// Observe the actual OS state while visible, including an external unpin operation.
fun JapaneseActivity.observeScreenPin(refresh: () -> Unit) {
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) { refresh(); delay(250) }
        }
    }
}
