package app.aino.mobile.core.db

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.sessionScopeDataStore by preferencesDataStore(name = "aino_session_scope")

/** Stores identifiers only; authentication material remains in Android Keystore. */
class SessionScopeStore(private val context: Context) {
    suspend fun save(scope: CacheScope) {
        context.sessionScopeDataStore.edit { values ->
            values[TENANT_ID] = scope.tenantId
            values[USER_ID] = scope.userId
        }
    }

    suspend fun read(): CacheScope? {
        val values = context.sessionScopeDataStore.data.first()
        val tenantId = values[TENANT_ID] ?: return null
        val userId = values[USER_ID] ?: return null
        return runCatching { CacheScope(tenantId, userId) }.getOrNull()
    }

    suspend fun clear() {
        context.sessionScopeDataStore.edit { it.clear() }
    }

    private companion object {
        val TENANT_ID = longPreferencesKey("tenant_id")
        val USER_ID = longPreferencesKey("user_id")
    }
}