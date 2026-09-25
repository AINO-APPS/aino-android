package app.aino.mobile.core.designsystem.tokens

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.themeDataStore by preferencesDataStore(name = "aino_theme")

/**
 * Persisted dark/light override (P1.8). Mirrors the web's `data-theme`
 * attribute semantics: dark is the default; a stored override is applied
 * without an app restart via [WebTheme].
 */
class ThemePreferenceStore(private val context: Context) {
    private val darkKey = booleanPreferencesKey("dark_theme")

    /** Emits the effective dark flag. Default is dark (`true`) when unset. */
    val isDark: Flow<Boolean> = context.themeDataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { prefs -> prefs[darkKey] ?: true }

    suspend fun setDark(dark: Boolean) {
        context.themeDataStore.edit { it[darkKey] = dark }
    }
}
