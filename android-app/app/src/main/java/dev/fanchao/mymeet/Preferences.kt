package dev.fanchao.mymeet

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.fanchao.mymeet.call.CallSettings
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class Preferences(appContext: Context, json: Json) {
    private val prefs = appContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    private val notificationFlow = MutableSharedFlow<String>(extraBufferCapacity = 100)

    val lastUsedCallSettings: JsonPreferenceValue<List<CallSettings>> = JsonPreferenceValue(
        prefs = prefs,
        json = json,
        key = "lastUsedCallSettings",
        defaultValue = { emptyList() },
        notificationFlow = notificationFlow,
        serializer = ListSerializer(CallSettings.serializer()),
    )
}

class JsonPreferenceValue<T: Any>(
    private val prefs: SharedPreferences,
    private val json: Json,
    private val key: String,
    private val defaultValue: () -> T,
    private val notificationFlow: MutableSharedFlow<String>,
    private val serializer: KSerializer<T>,
) {
    private fun loadValue(): T = prefs.getString(key, null)
        ?.let { json.decodeFromString(serializer, it) }
        ?: defaultValue()

    @OptIn(DelicateCoroutinesApi::class)
    val valueFlow: StateFlow<T> = notificationFlow
        .filter { it == key }
        .map { loadValue() }
        .stateIn(GlobalScope, SharingStarted.Lazily, loadValue())

    var value: T get() = valueFlow.value
        set(value) {
            if (value != valueFlow.value) {
                prefs.edit {
                    putString(key, json.encodeToString(serializer, value))
                }

                notificationFlow.tryEmit(key)
            }
        }
}