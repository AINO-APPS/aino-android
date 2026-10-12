package app.aino.mobile.core.push

import android.content.Context
import app.aino.mobile.core.AppContainer
import app.aino.mobile.core.avatar.AvatarMemberPreview
import app.aino.mobile.core.avatar.GroupAvatarMember
import app.aino.mobile.core.avatar.GroupAvatarMetadata
import app.aino.mobile.core.network.ApiRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun parseGroupMemberPreviews(serialized: String, enforcePushBudget: Boolean = true): List<GroupAvatarMember> {
    if (enforcePushBudget) require(serialized.toByteArray(Charsets.UTF_8).size <= 2048) { "Group member previews exceed byte budget" }
    val raw = Json.parseToJsonElement(serialized) as? JsonArray ?: error("Group previews must be an array")
    require(raw.size <= 4 && raw.all { member ->
        member is JsonObject && member.keys == setOf("name", "avatar") &&
            member.values.all { it == JsonNull || (it is JsonPrimitive && it.isString) }
    }) { "Invalid group member shape" }
    val members = Json.decodeFromString<List<AvatarMemberPreview>>(serialized)
    require(members.size <= 4 && (!enforcePushBudget || members.all {
        (it.name == null || it.name.length <= 200) && (it.avatar == null || it.avatar.toByteArray(Charsets.UTF_8).size <= 2048)
    })) { "Invalid group member previews" }
    return members.map { GroupAvatarMember(it.name, it.avatar) }
}

internal fun pushGroupAvatarMetadata(data: Map<String, String>, fallback: GroupAvatarMetadata? = null) =
    GroupAvatarMetadata(
        photoUrl = if ("groupAvatar" in data) data["groupAvatar"]?.takeIf(String::isNotBlank) else fallback?.photoUrl,
        members = data["groupMemberPreviews"]?.let { runCatching { parseGroupMemberPreviews(it) }.getOrNull() }
            ?: fallback?.members.orEmpty(),
    )

internal fun cachedGroupAvatarMetadata(body: String, conversationId: Long): GroupAvatarMetadata? {
    val rows = Json.parseToJsonElement(body) as JsonArray
    val row = rows.firstOrNull { it.jsonObject["id"]?.jsonPrimitive?.content == conversationId.toString() }?.jsonObject ?: return null
    // HTTP/cache metadata is not constrained by FCM's small data budget.
    val members = row["group_member_previews"]?.let {
        runCatching { parseGroupMemberPreviews(it.toString(), enforcePushBudget = false) }.getOrNull()
    } ?: row["group_member_avatars"]?.let { avatars ->
        (avatars as? JsonArray)?.take(4)?.map { GroupAvatarMember(null, it.jsonPrimitive.contentOrNull) }
    }.orEmpty()
    return GroupAvatarMetadata(row["group_avatar"]?.jsonPrimitive?.contentOrNull, members)
}

/** Disk cache and authenticated API only, off the posting thread; never import feature DTOs. */
internal suspend fun loadNotificationGroupAvatar(context: Context, conversationId: Long, account: String?): GroupAvatarMetadata? {
    if (account == null || notificationAccount(context) != account) return null
    val container = AppContainer.get(context)
    val cached = if (container.responses.scope == account.replace(':', '_')) container.responses.get("chat/conversations") else null
    cached?.let { runCatching { cachedGroupAvatarMetadata(String(it, Charsets.UTF_8), conversationId) }.getOrNull()?.let { metadata -> return metadata } }
    if (notificationAccount(context) != account) return null
    return cachedGroupAvatarMetadata(container.api.execute(ApiRequest(path = "chat/conversations")).bodyAsString(), conversationId)
}
