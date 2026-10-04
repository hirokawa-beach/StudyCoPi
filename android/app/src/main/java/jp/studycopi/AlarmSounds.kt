package jp.studycopi

import android.content.Context
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri

object AlarmSounds {
    val choices = listOf("system" to "端末の標準音", "bell" to "ベル", "pulse" to "電子音", "chime" to "チャイム")
    fun valid(key: String) = key in choices.map { it.first } ||
        key.length in 10..2048 && key.startsWith("content://") && Uri.parse(key).authority?.isNotBlank() == true
    fun label(key: String): String = choices.find { it.first == key }?.second ?: "選択した端末の音源"
    fun source(player: MediaPlayer, context: Context, key: String) {
        if (key in setOf("bell", "pulse", "chime")) {
            context.assets.openFd("sounds/$key.wav").use { player.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
        } else {
            val uri = if (key == "system") RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) else Uri.parse(key)
            player.setDataSource(context, uri)
        }
    }
    // Device-local ringtone URIs cannot be transferred to a different phone.
    fun restored(key: String, internal: Boolean) = if (internal || !key.startsWith("content://")) key else "system"
}
