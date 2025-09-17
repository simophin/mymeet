package dev.fanchao.mymeet

import android.util.Log
import dev.fanchao.mymeet.proto.Messages
import io.getstream.webrtc.android.ktx.createSessionDescription
import io.getstream.webrtc.android.ktx.suspendSdpObserver
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import kotlin.sequences.forEach

suspend fun PeerConnection.setRemote(desc: SessionDescription) {
    suspendSdpObserver { observer ->
        setRemoteDescription(observer, desc)
    }.getOrThrow()
}

suspend fun PeerConnection.setLocal(desc: SessionDescription) {
    suspendSdpObserver { observer ->
        setLocalDescription(observer, desc)
    }.getOrThrow()
}

suspend fun PeerConnection.setLocal() {
    suspendSdpObserver { observer ->
        setLocalDescription(observer)
    }.getOrThrow()
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

fun PeerConnection.setLocalStream(stream: MediaStream) {
    (stream.audioTracks.asSequence() + stream.videoTracks.asSequence()).forEach { track ->
        addTransceiver(
            track,
            RtpTransceiver.RtpTransceiverInit(
                RtpTransceiver.RtpTransceiverDirection.SEND_RECV,
                listOf(stream.id)
            )
        )
    }
}

fun Messages.IceCandidate.toWebRtc(): IceCandidate {
    return IceCandidate(
        sdpMid,
        sdpMlineIndex,
        sdp
    )
}

fun IceCandidate.toProtoBuf(): Messages.IceCandidate.Builder {
    return Messages.IceCandidate.newBuilder()
        .setSdpMid(sdpMid)
        .setSdpMlineIndex(sdpMLineIndex)
        .setSdp(sdp)
}

fun Messages.SdpDescription.toWebRtc(): SessionDescription {
    return SessionDescription(
        when (type) {
            Messages.SdpType.OFFER -> SessionDescription.Type.OFFER
            Messages.SdpType.ANSWER -> SessionDescription.Type.ANSWER
            Messages.SdpType.PREANSWER -> SessionDescription.Type.PRANSWER
        },
        sdp,
    )
}

fun SessionDescription.toProtoBuf(): Messages.SdpDescription.Builder {
    return Messages.SdpDescription.newBuilder()
        .setSdp(description)
        .setType(when (type) {
            SessionDescription.Type.OFFER -> Messages.SdpType.OFFER
            SessionDescription.Type.PRANSWER -> Messages.SdpType.PREANSWER
            SessionDescription.Type.ANSWER -> Messages.SdpType.ANSWER
            SessionDescription.Type.ROLLBACK -> error("not supported")
        })
}