package dev.fanchao.mymeet

import io.getstream.webrtc.android.ktx.suspendSdpObserver
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription

suspend fun PeerConnection.setRemote(type: SessionDescription.Type, sdp: String) {
    suspendSdpObserver { observer ->
        setRemoteDescription(observer, SessionDescription(type, sdp))
    }
}

suspend fun PeerConnection.setLocal(desc: SessionDescription) {
    suspendSdpObserver { observer ->
        setLocalDescription(observer, desc)
    }
}