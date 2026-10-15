package app.aino.mobile.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.aino.mobile.R
import app.aino.mobile.core.settings.ChatPreferences
import app.aino.mobile.core.settings.ChatPrefs

/** One AINO tone, the same id the web/desktop `sounds.ts` presets use. */
data class SoundPreset(val id: String, val label: String, val res: Int?)

/**
 * The five sound slots shared with web/desktop (`profile/notification-prefs`).
 * Tones are `scripts/render-tones.mjs` renders of the desktop synths; `none` is silent.
 */
enum class SoundCategory(
    val syncKey: String,
    val title: String,
    val defaultId: String,
    val pickerType: Int,
    val presets: List<SoundPreset>,
) {
    Ringtone("ringtone", "Incoming call ringtone", "classic", RingtoneManager.TYPE_RINGTONE, listOf(
        SoundPreset("classic", "Classic", R.raw.ringtone_classic),
        SoundPreset("calm", "Calm", R.raw.ringtone_calm),
        SoundPreset("dynamic", "Dynamic", R.raw.ringtone_dynamic),
        SoundPreset("urgent", "Urgent", R.raw.ringtone_urgent),
        SoundPreset("boop", "Boop", R.raw.ringtone_boop),
        SoundPreset("marimba", "Marimba", R.raw.ringtone_marimba),
        SoundPreset("crystal", "Crystal", R.raw.ringtone_crystal),
        SoundPreset("vapor", "Vapor", R.raw.ringtone_vapor),
        SoundPreset("none", "None (silent)", null),
    )),
    Outgoing("outgoingTone", "Outgoing call ring", "ringback", RingtoneManager.TYPE_RINGTONE, listOf(
        SoundPreset("ringback", "Ringback (classic)", R.raw.tone_outgoing_ringback),
        SoundPreset("pulse", "Pulse", R.raw.tone_outgoing_pulse),
        SoundPreset("soft", "Soft", R.raw.tone_outgoing_soft),
        SoundPreset("echo", "Echo", R.raw.tone_outgoing_echo),
        SoundPreset("drift", "Drift", R.raw.tone_outgoing_drift),
        SoundPreset("none", "None (silent)", null),
    )),
    Message("messageTone", "Message sound", "ding", RingtoneManager.TYPE_NOTIFICATION, listOf(
        SoundPreset("ding", "Ding", R.raw.tone_message_ding),
        SoundPreset("pop", "Pop", R.raw.tone_message_pop),
        SoundPreset("chime", "Chime", R.raw.tone_message_chime),
        SoundPreset("knock", "Knock", R.raw.tone_message_knock),
        SoundPreset("subtle", "Subtle", R.raw.tone_message_subtle),
        SoundPreset("glassy", "Glassy", R.raw.tone_message_glassy),
        SoundPreset("ripple", "Ripple", R.raw.tone_message_ripple),
        SoundPreset("none", "None (silent)", null),
    )),
    Mention("mentionTone", "Mention sound", "mention", RingtoneManager.TYPE_NOTIFICATION, listOf(
        SoundPreset("mention", "Mention", R.raw.tone_mention_mention),
        SoundPreset("chime", "Chime", R.raw.tone_mention_chime),
        SoundPreset("urgent", "Urgent", R.raw.tone_mention_urgent),
        SoundPreset("spark", "Spark", R.raw.tone_mention_spark),
        SoundPreset("none", "None (silent)", null),
    )),
    Reaction("reactionTone", "Reaction sound", "subtle", RingtoneManager.TYPE_NOTIFICATION, listOf(
        SoundPreset("subtle", "Subtle", R.raw.tone_reaction_subtle),
        SoundPreset("pop", "Pop", R.raw.tone_reaction_pop),
        SoundPreset("click", "Click", R.raw.tone_reaction_click),
        SoundPreset("none", "None (silent)", null),
    ));

    /** Unknown ids (e.g. a tone added on a newer desktop) fall back to the default. */
    fun preset(id: String?): SoundPreset = presets.firstOrNull { it.id == id } ?: presets.first { it.id == defaultId }

    fun syncedId(prefs: ChatPrefs): String = when (this) {
        Ringtone -> prefs.ringtone
        Outgoing -> prefs.outgoingTone
        Message -> prefs.messageTone
        Mention -> prefs.mentionTone
        Reaction -> prefs.reactionTone
    }

    fun withSyncedId(prefs: ChatPrefs, id: String): ChatPrefs = when (this) {
        Ringtone -> prefs.copy(ringtone = id)
        Outgoing -> prefs.copy(outgoingTone = id)
        Message -> prefs.copy(messageTone = id)
        Mention -> prefs.copy(mentionTone = id)
        Reaction -> prefs.copy(reactionTone = id)
    }
}

/** What a slot plays: an AINO tone (synced across devices) or a device-only choice. */
sealed interface SoundChoice {
    data class Preset(val id: String) : SoundChoice
    data object SystemDefault : SoundChoice
    data class Device(val uri: Uri) : SoundChoice
}

/**
 * Resolves the sound for each slot. AINO tones come from the synced prefs; "System
 * default" and "Choose from device…" are kept per device (Signal keeps sounds per
 * device) and win until an AINO tone is picked again.
 */
object NotificationSounds {
    private const val FILE = "aino_notification_sounds"
    private const val LEGACY_RINGTONE_URI = "ringtoneUri"
    private const val SYSTEM = "system"
    private fun key(category: SoundCategory) = "override.${category.syncKey}"
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    internal fun parseOverride(stored: String?): SoundChoice? = when {
        stored.isNullOrBlank() -> null
        stored == SYSTEM -> SoundChoice.SystemDefault
        else -> SoundChoice.Device(Uri.parse(stored))
    }

    fun override(context: Context, category: SoundCategory): SoundChoice? {
        val p = prefs(context)
        // Builds before 0.25.2 stored only the device ringtone picked in Android's picker.
        if (category == SoundCategory.Ringtone && !p.contains(key(category))) {
            p.getString(LEGACY_RINGTONE_URI, null)?.let { legacy ->
                p.edit().putString(key(category), legacy).remove(LEGACY_RINGTONE_URI).apply()
            }
        }
        return parseOverride(p.getString(key(category), null))
    }

    /** `null` clears the device choice so the synced AINO tone applies. */
    fun setOverride(context: Context, category: SoundCategory, choice: SoundChoice?) {
        val value = when (choice) {
            null, is SoundChoice.Preset -> null
            SoundChoice.SystemDefault -> SYSTEM
            is SoundChoice.Device -> choice.uri.toString()
        }
        prefs(context).edit().apply { if (value == null) remove(key(category)) else putString(key(category), value) }.apply()
    }

    internal fun choice(override: SoundChoice?, syncedId: String, category: SoundCategory): SoundChoice =
        override ?: SoundChoice.Preset(category.preset(syncedId).id)

    fun choice(context: Context, category: SoundCategory): SoundChoice =
        choice(override(context, category), category.syncedId(ChatPreferences.get(context)), category)

    fun presetUri(context: Context, preset: SoundPreset): Uri? = preset.res?.let {
        // By resource id: playback never depends on a name lookup. Ids can change between
        // builds, but the URI is part of each channel's sound key, so a renumbered tone
        // simply moves its channel to the next version on the first launch after an update.
        Uri.parse("android.resource://${context.packageName}/$it")
    }

    fun systemDefaultUri(category: SoundCategory): Uri =
        if (category.pickerType == RingtoneManager.TYPE_RINGTONE) Settings.System.DEFAULT_RINGTONE_URI
        else Settings.System.DEFAULT_NOTIFICATION_URI

    /** The sound to play, or `null` for silent. */
    fun uri(context: Context, category: SoundCategory, choice: SoundChoice = choice(context, category)): Uri? = when (choice) {
        is SoundChoice.Preset -> presetUri(context, category.preset(choice.id))
        SoundChoice.SystemDefault -> systemDefaultUri(category)
        is SoundChoice.Device -> choice.uri
    }

    fun title(context: Context, category: SoundCategory, choice: SoundChoice = choice(context, category)): String = when (choice) {
        is SoundChoice.Preset -> category.preset(choice.id).label
        SoundChoice.SystemDefault -> "System default"
        is SoundChoice.Device -> NotificationSettingsLinks.soundTitle(context, choice.uri) ?: "Device sound"
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}

/**
 * Notification channels whose sound follows a [SoundCategory]. A channel's sound is
 * fixed once created, so a new sound means a new channel id: the old channel is
 * copied (importance, vibration, lights, lock screen, badge, DND) under the next
 * version and deleted, as Signal's `NotificationChannels.updateMessageChannel` does.
 * Version 0 keeps each channel's original id.
 */
object SoundChannels {
    private const val FILE = "aino_sound_channels"
    private const val CONVERSATION_IMPORTANCE = "conversationImportance."

    internal data class Spec(val baseId: String, val category: SoundCategory, val ringtoneUsage: Boolean = false)

    @Volatile private var versions: Map<String, Int>? = null

    private fun store(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    internal fun versionedId(baseId: String, version: Int): String = if (version <= 0) baseId else "${baseId}_s$version"

    /** Sound identity stored per channel version; "none" is silent. */
    internal fun soundKey(uri: Uri?): String = uri?.toString() ?: "none"

    /** True when the live channel must be replaced to play [desired]. */
    internal fun needsMigration(applied: String?, desired: String, channelExists: Boolean): Boolean =
        channelExists && applied != desired

    private fun loadVersions(context: Context): Map<String, Int> = versions ?: synchronized(this) {
        versions ?: store(context).all.filterKeys { it.startsWith("version.") }
            .mapNotNull { (k, v) -> (v as? Int)?.let { k.removePrefix("version.") to it } }.toMap()
            .also { versions = it }
    }

    /** Current id for a base channel id; ids without a sound slot are returned as is. */
    fun current(context: Context, baseId: String): String =
        versionedId(baseId, loadVersions(context)[baseId] ?: 0)

    /** Creates or migrates [spec]'s channel so it plays the slot's current sound. Cheap when nothing changed. */
    @Synchronized
    internal fun ensure(context: Context, spec: Spec, template: () -> NotificationChannel) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val prefs = store(context)
        val version = prefs.getInt("version.${spec.baseId}", 0)
        val currentId = versionedId(spec.baseId, version)
        val uri = NotificationSounds.uri(context, spec.category)
        val desired = soundKey(uri)
        val applied = prefs.getString("sound.${spec.baseId}", null)
        val existing = manager.getNotificationChannel(currentId)
        val attributes = AudioAttributes.Builder()
            .setUsage(if (spec.ringtoneUsage) AudioAttributes.USAGE_NOTIFICATION_RINGTONE else AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        if (existing == null) {
            manager.createNotificationChannel(template().also { it.setSound(uri, if (uri == null) null else attributes) })
            prefs.edit().putString("sound.${spec.baseId}", desired).apply()
            return
        }
        if (!needsMigration(applied, desired, channelExists = true)) {
            // Re-creating an existing id only refreshes its name / description.
            val fresh = template()
            if (existing.name != fresh.name || existing.description != fresh.description) manager.createNotificationChannel(
                NotificationChannel(currentId, fresh.name, existing.importance).apply { description = fresh.description },
            )
            return
        }
        val nextId = versionedId(spec.baseId, version + 1)
        manager.deleteNotificationChannel(nextId)
        val copy = NotificationChannel(nextId, existing.name, existing.importance).apply {
            description = existing.description
            group = existing.group
            setShowBadge(existing.canShowBadge())
            enableLights(existing.shouldShowLights())
            lightColor = existing.lightColor
            enableVibration(existing.shouldVibrate())
            existing.vibrationPattern?.let { vibrationPattern = it }
            lockscreenVisibility = existing.lockscreenVisibility
            setBypassDnd(existing.canBypassDnd())
            setSound(uri, if (uri == null) null else attributes)
        }
        manager.createNotificationChannel(copy)
        // Android 11+ conversation channels copy their parent's sound, so they move with it;
        // each keeps the importance the user gave it (e.g. a chat switched off stays off).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val edit = prefs.edit()
            manager.notificationChannels.filter { it.parentChannelId == currentId }.forEach { child ->
                child.conversationId?.let { edit.putInt(CONVERSATION_IMPORTANCE + it, child.importance) }
                manager.deleteNotificationChannel(child.id)
            }
            edit.apply()
        }
        manager.deleteNotificationChannel(currentId)
        prefs.edit().putInt("version.${spec.baseId}", version + 1).putString("sound.${spec.baseId}", desired).commit()
        synchronized(this) { versions = null }
    }

    /** Importance a conversation channel had before its parent moved to a new sound, if any. */
    fun conversationImportance(context: Context, shortcutId: String): Int? =
        store(context).takeIf { it.contains(CONVERSATION_IMPORTANCE + shortcutId) }?.getInt(CONVERSATION_IMPORTANCE + shortcutId, 0)

    /** Conversation channel ids include the parent's id, so a moved parent gets fresh children. */
    fun conversationChannelId(parentChannelId: String, stableSuffix: String): String =
        if (parentChannelId == "aino_messages") "messages_v3_conversation_$stableSuffix"
        else "${parentChannelId}_conversation_$stableSuffix"

    fun clear(context: Context) {
        // Channel versions describe this install's channels, not the account; keep them.
        val prefs = store(context)
        prefs.edit().apply { prefs.all.keys.filter { it.startsWith(CONVERSATION_IMPORTANCE) }.forEach(::remove) }.apply()
    }

    internal fun resetForTest() = synchronized(this) { versions = null }
}
