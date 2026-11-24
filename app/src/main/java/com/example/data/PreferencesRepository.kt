package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.ThemePreference
import com.example.model.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.anishDataStore: DataStore<Preferences> by preferencesDataStore(name = "anish_ai_preferences")

class PreferencesRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
    }

    val userPreferences: Flow<UserPreferences> = context.anishDataStore.data.map { prefs ->
        UserPreferences(
            themePreference = ThemePreference.fromKey(prefs[Keys.THEME_MODE]),
            hapticsEnabled = prefs[Keys.HAPTICS_ENABLED] ?: true
        )
    }

    suspend fun setThemePreference(preference: ThemePreference) {
        context.anishDataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = preference.storageKey
        }
    }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        context.anishDataStore.edit { prefs ->
            prefs[Keys.HAPTICS_ENABLED] = enabled
        }
    }
}
