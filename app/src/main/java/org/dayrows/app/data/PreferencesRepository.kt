// SPDX-License-Identifier: GPL-3.0-or-later
package org.dayrows.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private val Context.dayRowsDataStore by preferencesDataStore("dayrows")

class PreferencesRepository(context: Context) {
    private val store = context.applicationContext.dayRowsDataStore
    private val stateKey = stringPreferencesKey("configuration_v1")
    val preferences = store.data.catch { if (it is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw it }
        .map { decode(it[stateKey]) }

    suspend fun update(transform: (UserPreferences) -> UserPreferences) {
        store.edit { it[stateKey] = encode(transform(decode(it[stateKey]))) }
    }
    suspend fun discover(calendars: List<DeviceCalendar>) = update { it.discover(calendars) }
    suspend fun select(key: String, selected: Boolean) = change(key) { it.copy(selected = selected) }
    suspend fun label(key: String, label: String) = change(key) { it.copy(label = label) }
    private suspend fun change(key: String, transform: (CalendarPreference) -> CalendarPreference) = update { old ->
        val existing = old.calendars.ifEmpty { listOf(CalendarPreference(key)) }
        old.copy(calendars = if (existing.any { it.key == key }) existing.map { if (it.key == key) transform(it) else it }
                            else existing + transform(CalendarPreference(key)))
    }
    suspend fun move(key: String, otherKey: String) = update { it.move(key, otherKey) }

    private fun decode(text: String?): UserPreferences = try {
        val json = JSONObject(text ?: "{}")
        val items = json.optJSONArray("calendars") ?: JSONArray()
        UserPreferences(List(items.length()) { i -> items.getJSONObject(i).let {
            CalendarPreference(it.getString("key"), it.optBoolean("selected"), it.optString("label"))
        } }, json.optInt("days", 2).takeIf { it in listOf(1, 2, 7) } ?: 2, json.optBoolean("configured"))
    } catch (_: org.json.JSONException) { UserPreferences() }

    private fun encode(value: UserPreferences) = JSONObject().apply {
        put("days", value.dayCount); put("configured", value.configured)
        put("calendars", JSONArray().apply { value.calendars.forEach { item ->
            put(JSONObject().put("key", item.key).put("selected", item.selected).put("label", item.label))
        } })
    }.toString()
}
