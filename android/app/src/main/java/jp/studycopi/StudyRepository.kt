package jp.studycopi

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

fun clockPoint(context: Context): ClockPoint = ClockPoint(System.currentTimeMillis(), SystemClock.elapsedRealtime(),
    Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0))

class StudyApplication : Application() {
    val repository by lazy { StudyRepository(this) }
    override fun onCreate() { super.onCreate(); AlarmScheduler.createChannels(this); WakeScheduler.createChannel(this) }
}

/** One atomic document, serialized through a mutex; failed writes never publish a new UI state. */
class StudyRepository(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "study-data.json"))
    private val mutex = Mutex()
    private var loaded = false
    private val mutable = MutableStateFlow(StudyData())
    val data = mutable.asStateFlow()
    suspend fun load(): StudyData = withContext(Dispatchers.IO) { mutex.withLock {
        if (!loaded) {
            mutable.value = if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists())
                BackupCodec.decode(file.openRead().bufferedReader().use { it.readText() }, internal = true) else StudyData()
            loaded = true
        }
        mutable.value
    } }
    suspend fun update(transform: (StudyData) -> StudyData): StudyData = withContext(Dispatchers.IO) { mutex.withLock {
        check(loaded) { "データを読み込めていません。バックアップから復元してください" }
        persist(transform(mutable.value))
    } }
    suspend fun restore(replacement: StudyData): StudyData = withContext(Dispatchers.IO) { mutex.withLock {
        persist(replacement.copy(preferences = mutable.value.preferences))
    } }
    private fun persist(next: StudyData): StudyData {
        BackupCodec.validate(next)
        if (loaded && next == mutable.value) return next
        val output = file.startWrite()
        try {
            output.write(BackupCodec.encode(next, internal = true).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) { file.failWrite(output); throw error }
        loaded = true
        mutable.value = next
        return next
    }
}
