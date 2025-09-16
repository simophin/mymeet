package dev.fanchao.mymeet

import io.getstream.webrtc.android.ktx.createSessionDescription
import io.getstream.webrtc.android.ktx.suspendSdpObserver
import org.webrtc.MediaConstraints
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

suspend fun PeerConnection.suspendCreateOffer(constraints: MediaConstraints): SessionDescription {
    return createSessionDescription { observer ->
        createOffer(observer, constraints)
    }.getOrThrow()
}

suspend fun PeerConnection.suspendCreateAnswer(constraints: MediaConstraints): SessionDescription {
    return createSessionDescription { observer ->
        createAnswer(observer, constraints)
    }.getOrThrow()
}