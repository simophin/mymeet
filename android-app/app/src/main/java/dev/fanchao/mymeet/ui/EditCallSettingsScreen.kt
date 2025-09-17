package dev.fanchao.mymeet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.fanchao.mymeet.App.Companion.appInstance
import dev.fanchao.mymeet.call.CallSettings
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data object EditCallSettingsRoute

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
fun EditCallSettingsScreen(
    editing: CallSettings? = null,
    onSaved: (CallSettings) -> Unit = {},
) {
    var serverUrl by remember { mutableStateOf(editing?.serverUrl.orEmpty()) }
    var userName by remember { mutableStateOf(editing?.userName.orEmpty()) }
    var room by remember { mutableStateOf(editing?.room.orEmpty()) }
    val userId = remember { editing?.userId ?: UUID.randomUUID().toString() }

    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val title = if (editing == null) "New call" else "Edit call settings"
                    Text(text = title)
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = serverUrl, onValueChange = { serverUrl = it },
                label = { Text("Server URL") },
                placeholder = { Text("ws://, wss://") }
            )

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = room,
                onValueChange = { room = it },
                label = { Text("Room") }
            )

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = userName,
                onValueChange = { userName = it },
                label = { Text("User name") }
            )

            FilledTonalButton(onClick = {
                val newSettings = CallSettings(
                    serverUrl = serverUrl,
                    userName = userName,
                    room = room,
                    userId = userId
                )

                val lastUsed = context.appInstance.preferences.lastUsedCallSettings
                    .value
                    .toMutableList()
                
                val existingIndex = editing?.let(lastUsed::indexOf) ?: -1
                if (existingIndex >= 0) {
                    lastUsed[existingIndex] = newSettings
                } else {
                    lastUsed.add(newSettings)
                }

                context.appInstance.preferences.lastUsedCallSettings.value = lastUsed

                onSaved(newSettings)
            }) {
                Text("Save")
            }
        }
    }
}