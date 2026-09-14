package app.aino.mobile.feature.profile

enum class ProfileTab { Account, Search }

/**
 * `validateUsername` on the server (`server/utils/validation`) is mirrored here
 * so an invalid edit never reaches the network. The rules the route enforces are
 * a non-empty name capped at 100 characters and a username that is unique and
 * well-formed; uniqueness can only be decided server-side, so only the format
 * checks are performed locally.
 */
fun validateProfileEdit(fullName: String, username: String): String? {
    val name = fullName.trim()
    val handle = username.trim()
    if (name.isEmpty() || handle.isEmpty()) return "Name and username are required"
    if (name.length > 100) return "Full name must be 100 characters or less"
    if (handle.length < 3 || handle.length > 30) return "Username must be between 3 and 30 characters"
    if (!Regex("^[a-zA-Z0-9._-]+$").matches(handle)) {
        return "Username may only contain letters, numbers, dots, underscores and hyphens"
    }
    return null
}

/** Matches the server's email regex on `PUT /api/profile/email`. */
fun validateEmail(email: String): String? {
    val value = email.trim()
    if (value.isEmpty()) return "Email is required"
    if (!Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(value)) return "Invalid email address"
    return null
}

/**
 * The search service returns empty results below two characters rather than an
 * error, so the client suppresses the request entirely instead of spending a
 * round-trip on a guaranteed-empty response.
 */
const val SEARCH_MIN_LENGTH = 2

fun searchable(term: String): Boolean = term.trim().length >= SEARCH_MIN_LENGTH

/** The service truncates the term to 100 characters; do the same before sending. */
fun normalizeSearchTerm(term: String): String = term.trim().take(100)

/** Strips the `ts_headline` markup the task snippet can carry. */
fun plainSnippet(snippet: String?): String =
    snippet?.replace(Regex("</?b>"), "")?.replace(Regex("<[^>]*>"), "")?.trim().orEmpty()

fun roleLabel(role: String): String = when (role) {
    "super_admin" -> "Super admin"
    "hr_admin" -> "HR admin"
    "platform_admin" -> "Platform admin"
    "team_lead" -> "Team lead"
    else -> role.replace('_', ' ').replaceFirstChar(Char::uppercase)
}
