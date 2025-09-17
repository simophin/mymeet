package dev.fanchao.mymeet

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver

sealed interface PeerConnectionEvent {
    data class TrackAdded(val receiver: RtpReceiver) : PeerConnectionEvent
    data class TrackRemoved(val receiver: RtpReceiver) : PeerConnectionEvent
    data class NewIceCandidate(val iceCandidate: IceCandidate) : PeerConnectionEvent
    data class IceCandidateStateChanged(val state: PeerConnection.IceConnectionState) :
        PeerConnectionEvent

    data object RenegotiationNeeded : PeerConnectionEvent
}

private const val TAG = "PeerConnectionEvent"

fun createPeerConnectionEventStream(): Pair<PeerConnection.Observer, ReceiveChannel<PeerConnectionEvent>> {
    val events = Channel<PeerConnectionEvent>(capacity = 100)

    return object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            Log.d(TAG, "WEBRTC: onIceCandidate: $candidate")
            events.trySend(PeerConnectionEvent.NewIceCandidate(candidate))
        }

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
            Log.d(TAG, "WEBRTC: onIceConnectionChange: $newState")
            events.trySend(PeerConnectionEvent.IceCandidateStateChanged(newState))
        }

        override fun onSignalingChange(newState: PeerConnection.SignalingState?) {
            Log.d(TAG, "WEBRTC: onSignalingChange: $newState")
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
            Log.d(TAG, "WEBRTC: onIceGatheringChange: $newState")
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate?>?) {}
        override fun onDataChannel(dataChannel: DataChannel?) {}
        override fun onRenegotiationNeeded() {
            Log.d(TAG, "WEBRTC: onRenegotiationNeeded")
            events.trySend(PeerConnectionEvent.RenegotiationNeeded)
        }

        override fun onAddTrack(
            receiver: RtpReceiver,
            mediaStreams: Array<out MediaStream?>?
        ) {
            Log.d(TAG, "WEBRTC: onAddTrack: $receiver, $mediaStreams")
            events.trySend(PeerConnectionEvent.TrackAdded(receiver))
        }

        override fun onRemoveTrack(receiver: RtpReceiver) {
            Log.d(TAG, "WEBRTC: onRemoveTrack: $receiver")
            events.trySend(PeerConnectionEvent.TrackRemoved(receiver))
        }

        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}

    } to events
}

