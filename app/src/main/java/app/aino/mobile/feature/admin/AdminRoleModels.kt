package app.aino.mobile.feature.admin

import app.aino.mobile.core.common.LenientIntNullableSerializer
import app.aino.mobile.core.common.LenientIntSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `GET /admin/role-requests` row. `approvals` is `{ role: { status, by, at } }`. */
@Serializable
data class RoleRequest(
    val id: Long,
    @SerialName("target_user_id") val targetUserId: Long? = null,
    @SerialName("requested_by") val requestedBy: Long? = null,
    @SerialName("from_role") val fromRole: String? = null,
    @SerialName("to_role") val toRole: String? = null,
    val status: String = "pending",
    val reason: String? = null,
    @SerialName("reject_reason") val rejectReason: String? = null,
    val approvals: JsonObject? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("target_name") val targetName: String? = null,
    @SerialName("target_username") val targetUsername: String? = null,
    @SerialName("requester_name") val requesterName: String? = null,
    @SerialName("current_role") val currentRole: String? = null,
    @SerialName("requested_role") val requestedRole: String? = null,
) {
    val shownFrom: String get() = currentRole ?: fromRole.orEmpty()
    val shownTo: String get() = requestedRole ?: toRole.orEmpty()

    /** Ordered `(role, status)` pairs of the approval chain. */
    val chain: List<Pair<String, String>>
        get() = approvals?.entries?.map { (role, v) ->
            role to (((v as? JsonObject)?.get("status") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: "pending")
        }.orEmpty()
}

@Serializable
data class ApproveResult(val message: String? = null, @SerialName("fully_approved") val fullyApproved: Boolean? = null)

/** `POST /admin/users/import` result. */
@Serializable
data class ImportResult(
    @Serializable(LenientIntSerializer::class) val imported: Int = 0,
    val failed: List<ImportFailure> = emptyList(),
    val details: List<ImportedUser> = emptyList(),
)

@Serializable
data class ImportFailure(@Serializable(LenientIntNullableSerializer::class) val row: Int? = null, val error: String = "")

@Serializable
data class ImportedUser(
    val username: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("initial_password") val initialPassword: String? = null,
)

/** One row of the JSON import body (`users: [...]`). */
data class ImportRow(
    val username: String,
    val fullName: String,
    val email: String,
    val role: String,
    val password: String? = null,
    val departmentName: String? = null,
    val teamName: String? = null,
    val managerUsername: String? = null,
)

/** `GET /org/roles` role. */
@Serializable
data class OrgRole(
    @SerialName("role_key") val roleKey: String,
    val label: String = "",
    val description: String? = null,
    val color: String? = null,
    @SerialName("permission_level") @Serializable(LenientIntSerializer::class) val permissionLevel: Int = 1,
    @SerialName("is_system") val isSystem: Boolean = false,
    val customised: Boolean = false,
    @SerialName("user_count") @Serializable(LenientIntNullableSerializer::class) val userCount: Int? = null,
)

@Serializable
data class OrgRoles(val defaults: List<OrgRole> = emptyList(), val roles: List<OrgRole> = emptyList())

/** Role editor draft (`OrgRoleLabels.tsx`). */
data class RoleDraft(
    val roleKey: String = "",
    val label: String = "",
    val description: String = "",
    val color: String = "#6366f1",
    val permissionLevel: Int = 1,
    val isNew: Boolean = true,
)
