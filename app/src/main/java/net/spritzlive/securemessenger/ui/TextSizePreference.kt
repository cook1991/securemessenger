package net.spritzlive.securemessenger.ui

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.spritzlive.securemessenger.ui.theme.SENIOR_FONT_SCALE

private val Context.dataStore by preferencesDataStore(name = "securemessenger_prefs")
private val FONT_SCALE_KEY = floatPreferencesKey("font_scale")

object TextSizePreference {
    fun flow(context: Context): Flow<Float> =
        context.dataStore.data.map { it[FONT_SCALE_KEY] ?: SENIOR_FONT_SCALE }

    suspend fun set(context: Context, scale: Float) {
        context.dataStore.edit { it[FONT_SCALE_KEY] = scale }
    }
}
