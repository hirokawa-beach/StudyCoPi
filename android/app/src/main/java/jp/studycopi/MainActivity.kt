package jp.studycopi

import android.os.Bundle
import android.content.Intent
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine

abstract class JapaneseActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) {
        val japanese = Configuration(base.resources.configuration).apply { setLocales(LocaleList.forLanguageTags("ja-JP")) }
        super.attachBaseContext(base.createConfigurationContext(japanese))
    }
}
class MainActivity : JapaneseActivity() {
    private lateinit var model: StudyViewModel
    private var scheduledOpening: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory(application))[StudyViewModel::class.java]
        handleNotification(intent)
        setContent { StudyTheme { StudyApp(model) } }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch { combine(model.data, model.ready) { data, ready -> ready && data.wakeRuns.isNotEmpty() }
                    .collect { ringing -> if (ringing) startActivity(Intent(this@MainActivity, WakeActivity::class.java)) } }
                launch { combine(model.data, model.ready, model.now) { data, ready, point ->
                    if (ready) focusDue(data, point.wall).firstOrNull { !ScheduledFocus.seen(this@MainActivity, it) } else null
                }.collect { plan ->
                    if (plan == null) scheduledOpening = null
                    else if (scheduledOpening != focusKey(plan)) {
                        scheduledOpening = focusKey(plan); startActivity(ScheduledFocus.intent(this@MainActivity, plan))
                    }
                } }
            }
        }
    }
    override fun onStart() {
        super.onStart(); model.resume()
    }
    override fun onStop() { model.suspendTicks(); super.onStop() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleNotification(intent) }
    private fun handleNotification(intent: Intent) {
        intent.getStringExtra("openScreen")?.takeIf { it in setOf("today", "plans", "stats", "wake") }?.let { model.requestedScreen.value = it }
        if (intent.getBooleanExtra("openToday", false)) model.requestedScreen.value = "today"
        if (intent.getBooleanExtra("openWakeAlarms", false)) model.requestedScreen.value = "wake"
        if (intent.hasExtra("openTimer")) model.requestedScreen.value = if (intent.getBooleanExtra("openTimer", false)) "timer" else "today"
    }
}
