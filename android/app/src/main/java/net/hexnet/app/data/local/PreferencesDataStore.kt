package net.hexnet.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.hexnet.app.core.model.UserPreferences
import net.hexnet.app.core.model.UserProfile

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "hexnet_preferences")

class PreferencesDataStore(private val context: Context) {
    companion object {
        val KEY_USERNAME = stringPreferencesKey("user_username")
        val KEY_DISPLAY_NAME = stringPreferencesKey("user_display_name")
        val KEY_HEX_ADDRESS = stringPreferencesKey("user_hex_address")
        val KEY_KEY_FINGERPRINT = stringPreferencesKey("user_key_fingerprint")
        val KEY_BIO = stringPreferencesKey("user_bio")
        val KEY_IS_ONBOARDED = booleanPreferencesKey("is_onboarded")

        val KEY_DARK_MODE = booleanPreferencesKey("pref_dark_mode")
        val KEY_RELAY_ENABLED = booleanPreferencesKey("pref_relay_enabled")
        val KEY_MAX_HOPS = intPreferencesKey("pref_max_hops")
        val KEY_NOTIFICATIONS = booleanPreferencesKey("pref_notifications")
    }

    val userProfileFlow: Flow<UserProfile?> = context.dataStore.data.map { prefs ->
        val username = prefs[KEY_USERNAME]
        val hexAddress = prefs[KEY_HEX_ADDRESS]
        if (username != null && hexAddress != null) {
            UserProfile(
                username = username,
                displayName = prefs[KEY_DISPLAY_NAME] ?: username,
                hexAddress = hexAddress,
                keyFingerprint = prefs[KEY_KEY_FINGERPRINT] ?: "HEX-KEY-INIT-00",
                bio = prefs[KEY_BIO] ?: ""
            )
        } else {
            null
        }
    }

    val userPreferencesFlow: Flow<UserPreferences> = context.dataStore.data.map { prefs ->
        UserPreferences(
            darkMode = prefs[KEY_DARK_MODE] ?: true,
            relayEnabled = prefs[KEY_RELAY_ENABLED] ?: true,
            maxHops = prefs[KEY_MAX_HOPS] ?: 5,
            notificationsEnabled = prefs[KEY_NOTIFICATIONS] ?: true
        )
    }

    suspend fun saveProfile(profile: UserProfile) {
        context.dataStore.edit { prefs ->
            prefs[KEY_USERNAME] = profile.username
            prefs[KEY_DISPLAY_NAME] = profile.displayName
            prefs[KEY_HEX_ADDRESS] = profile.hexAddress
            prefs[KEY_KEY_FINGERPRINT] = profile.keyFingerprint
            prefs[KEY_BIO] = profile.bio
            prefs[KEY_IS_ONBOARDED] = true
        }
    }

    suspend fun updatePreferences(preferences: UserPreferences) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DARK_MODE] = preferences.darkMode
            prefs[KEY_RELAY_ENABLED] = preferences.relayEnabled
            prefs[KEY_MAX_HOPS] = preferences.maxHops
            prefs[KEY_NOTIFICATIONS] = preferences.notificationsEnabled
        }
    }

    suspend fun clearIdentity() {
        context.dataStore.edit { it.clear() }
    }
}
