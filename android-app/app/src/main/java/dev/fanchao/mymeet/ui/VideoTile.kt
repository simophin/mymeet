package dev.fanchao.mymeet.ui

import androidx.compose.runtime.Composable
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory

@Composable
fun VideoTile(
    userId: String,
    factory: PeerConnectionFactory,
    iceServers: List<PeerConnection.IceServer>,
) {

}