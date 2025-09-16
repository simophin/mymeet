package dev.fanchao.mymeet

import android.util.Log
import dev.fanchao.mymeet.proto.Messages
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.selects.selectUnbiased
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import kotlin.collections.plus

private const val TAG = "CallSessionManager"

class CallSessionManager(
    private val url: String,
    private val userId: String,
    private val userName: String,
    private val room: String,
    private val client: HttpClient,
    private val peerConnectionFactory: PeerConnectionFactory,
    private val iceServers: List<PeerConnection.IceServer>,
    scope: CoroutineScope,
) {
    data class MemberState(
        val userId: String,
        val tracks: List<MediaStreamTrack>,
        val iceConnectionState: PeerConnection.IceConnectionState,
    )

    private class PeerConnectionData(
        var state: MemberState,
        val conn: PeerConnection,
        val events: ReceiveChannel<PeerConnectionEvent>
    )

    private sealed interface PeerConnectionEvent {
        data class TrackAdded(val receiver: RtpReceiver) : PeerConnectionEvent
        data class TrackRemoved(val receiver: RtpReceiver) : PeerConnectionEvent
        data class NewIceCandidate(val iceCandidate: IceCandidate) : PeerConnectionEvent
        data class IceCandidateStateChanged(val state: PeerConnection.IceConnectionState) :
            PeerConnectionEvent

        data object RenegotiationNeeded : PeerConnectionEvent
    }

    private sealed interface Operation {
        data class AddTrack(val track: MediaStreamTrack, val callback: SendChannel<Result<Unit>>) :
            Operation

    }

    private val operations = Channel<Operation>(capacity = 25)

    private val mediaConstraints = MediaConstraints()

    private class States(
        val commandSender: SendChannel<Messages.Command>,
        val peerConnections: MutableMap<String, PeerConnectionData> = mutableMapOf(),
        val localTracks: MutableList<MediaStreamTrack> = mutableListOf(),
    )

    val memberStates = flow {
        coroutineScope {
            val (commandSender, messageReceiver, connState) = createWebSocketConnection(
                url = url, room = room, userId = userId, userName = userName, client = client
            )

            val states = States(commandSender = commandSender)

            val connStateReceiver = connState.produceIn(this)

            try {
                while (true) {
                    selectUnbiased {
                        // Listen for all events coming from peer connection
                        for (conn in states.peerConnections.values) {
                            conn.events.onReceive { event ->
                                handlePeerConnectionEvent(
                                    conn = conn,
                                    states = states,
                                    event = event
                                )
                            }
                        }

                        // Listen for any incoming messages
                        messageReceiver.onReceive { m ->
                            handleIncomingMessage(states, m)
                        }

                        // Listen for any local operations
                        operations.onReceive { op ->
                            handleLocalOperation(states, op)
                        }

                        // Listen for websocket state
                        connStateReceiver.onReceive { state ->
                            Log.d(TAG, "WebSocket connection state: $state")
                        }
                    }
                }
            } finally {
                for (conn in states.peerConnections.values) {
                    conn.conn.dispose()
                }

                commandSender.close()
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private suspend fun createPeerConnectionData(
        userId: String,
        localTracks: List<MediaStreamTrack>,
        createLocalOffer: Boolean
    ): Pair<PeerConnectionData, SessionDescription?> {
        val events = Channel<PeerConnectionEvent>(capacity = 100)
        val conn = peerConnectionFactory.createPeerConnection(
            iceServers,
            object : PeerConnection.Observer {
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

            })!!

        for (track in localTracks) {
            conn.addTrack(track)
            Log.d(TAG, "Adding local track $track")
        }

        val offer = if (createLocalOffer)
            conn.suspendCreateOffer(mediaConstraints).also { conn.setLocal(it) }
        else null

        return PeerConnectionData(
            state = MemberState(
                userId = userId,
                tracks = emptyList(),
                iceConnectionState = PeerConnection.IceConnectionState.NEW
            ),
            conn = conn,
            events = events,
        ) to offer
    }

    private suspend fun FlowCollector<Map<String, MemberState>>.handlePeerConnectionEvent(
        conn: PeerConnectionData,
        states: States,
        event: PeerConnectionEvent) {
        Log.d(TAG, "Received peer connection event $event")
        when (event) {
            is PeerConnectionEvent.TrackAdded -> {
                conn.state =
                    conn.state.copy(tracks = conn.state.tracks + event.receiver.track()!!)
                emit(states.peerConnections.collectMemberStates())
            }

            is PeerConnectionEvent.TrackRemoved -> {
                conn.state =
                    conn.state.copy(tracks = conn.state.tracks - event.receiver.track()!!)
                emit(states.peerConnections.collectMemberStates())
            }

            is PeerConnectionEvent.IceCandidateStateChanged -> {
                conn.state =
                    conn.state.copy(iceConnectionState = event.state)
                emit(states.peerConnections.collectMemberStates())
            }

            is PeerConnectionEvent.NewIceCandidate -> {
                states.commandSender.send(
                    Messages.Command.newBuilder()
                        .setIceCandidate(
                            Messages.IceCandidate.newBuilder()
                                .setCandidate(event.iceCandidate.sdp)
                                .setSdpMid(event.iceCandidate.sdpMid)
                                .setSdpMlineIndex(event.iceCandidate.sdpMLineIndex)
                        )
                        .setToUser(conn.state.userId)
                        .build()
                )
            }

            is PeerConnectionEvent.RenegotiationNeeded -> {
                val message = if (areWeOfferer(conn.state.userId)) {
                    Messages.Command.newBuilder()
                        .setOffer(
                            conn.conn.suspendCreateOffer(
                                mediaConstraints
                            ).description
                        )
                } else {
                    Messages.Command.newBuilder().setNegotiationNeeded(true)
                }.setToUser(conn.state.userId).build()

                states.commandSender.send(message)
            }
        }
    }

    private suspend fun FlowCollector<Map<String, MemberState>>.handleIncomingMessage(
        states: States,
        m: Messages.ClientMessage
    ) {
        Log.d(TAG, "Received incoming $m")
        when (m.contentCase) {
            Messages.ClientMessage.ContentCase.STATE_UPDATE -> {
                // Remove peer connections that are no longer present
                val toRemove =
                    states.peerConnections.keys - m.stateUpdate.membersMap.keys
                for (userId in toRemove) {
                    states.peerConnections.remove(userId)?.let { conn ->
                        Log.d(TAG, "Removing $conn")
                        conn.conn.dispose()
                    }
                }

                // Add new peer connections
                val toAdd =
                    m.stateUpdate.membersMap.keys - states.peerConnections.keys - userId
                for (newUserId in toAdd) {
                    val createOffer = areWeOfferer(newUserId)
                    val (conn, initialOffer) =
                        createPeerConnectionData(newUserId, states.localTracks, createOffer)
                    states.peerConnections[newUserId] = conn
                    Log.d(
                        TAG,
                        "Creating new PeerConnection to $newUserId, hasOffer = ${initialOffer != null}"
                    )

                    if (initialOffer != null) {
                        states.commandSender.send(
                            Messages.Command.newBuilder()
                                .setToUser(newUserId)
                                .setOffer(initialOffer.description)
                                .build()
                        )
                    }
                }

                emit(states.peerConnections.collectMemberStates())
            }

            Messages.ClientMessage.ContentCase.OFFER -> {
                states.peerConnections[m.fromUser]?.conn?.apply {
                    setRemote(SessionDescription.Type.OFFER, m.offer)

                    val answer = suspendCreateAnswer(mediaConstraints)
                    setLocal(answer)

                    states.commandSender.send(
                        Messages.Command.newBuilder()
                            .setAnswer(answer.description)
                            .setToUser(m.fromUser)
                            .build()
                    )
                }
            }

            Messages.ClientMessage.ContentCase.ANSWER -> {
                states.peerConnections[m.fromUser]?.conn?.setRemote(
                    type = SessionDescription.Type.ANSWER,
                    sdp = m.answer
                )
            }

            Messages.ClientMessage.ContentCase.ICE_CANDIDATE -> {
                states.peerConnections[m.fromUser]?.conn?.addIceCandidate(
                    IceCandidate(
                        m.iceCandidate.sdpMid,
                        m.iceCandidate.sdpMlineIndex,
                        m.iceCandidate.candidate
                    )
                )
            }

            Messages.ClientMessage.ContentCase.NEGOTIATION_NEEDED -> {
                if (areWeOfferer(m.fromUser)) {
                    states.peerConnections[m.fromUser]?.conn?.apply {
                        val newOffer = suspendCreateOffer(mediaConstraints)
                        setLocal(newOffer)
                        states.commandSender.send(
                            Messages.Command.newBuilder()
                                .setOffer(newOffer.description)
                                .setToUser(m.fromUser)
                                .build()
                        )
                    }
                }
            }

            Messages.ClientMessage.ContentCase.CONTENT_NOT_SET -> {}
        }

    }

    private suspend fun FlowCollector<Map<String, MemberState>>.handleLocalOperation(
        states: States,
        op: Operation
    ) {
        Log.d(TAG, "Received operation $op")
        when (op) {
            is Operation.AddTrack -> {
                val result = runCatching {
                    for (conn in states.peerConnections.values) {
                        conn.conn.addTrack(op.track)
                    }
                }.onFailure {
                    Log.e(TAG, "Failed to add track", it)
                }

                states.localTracks += op.track
                op.callback.send(result)
            }
        }
    }

    suspend fun setLocalVideoTrack(track: VideoTrack) {
        val callback = Channel<Result<Unit>>()
        operations.send(Operation.AddTrack(track, callback))
        callback.receive().getOrThrow()
    }

    private fun areWeOfferer(remoteUserId: String): Boolean = userId > remoteUserId

    private fun Map<String, PeerConnectionData>.collectMemberStates(): Map<String, MemberState> {
        return this.mapValues { it.value.state }
    }

}