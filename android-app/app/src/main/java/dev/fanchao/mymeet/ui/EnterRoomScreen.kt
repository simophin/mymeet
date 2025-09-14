package dev.fanchao.mymeet.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.fanchao.mymeet.Preferences
import kotlinx.serialization.Serializable

@Serializable
data object EnterRoomRoute

@Composable
fun EnterRoomScreen(
    navigateToRoom: (String) -> Unit,
    preferences: Preferences,
) {

}

@Preview
@Composable
private fun EnterRoom() {
    
}