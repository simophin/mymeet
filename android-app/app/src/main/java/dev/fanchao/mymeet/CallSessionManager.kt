package dev.fanchao.mymeet

import android.util.Log
import dev.fanchao.mymeet.proto.Messages
import io.getstream.webrtc.android.ktx.createSessionDescription
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.selects.select
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

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
        val streams: List<MediaStream>,
        val iceConnectionState: PeerConnection.IceConnectionState,
    )

    private class PeerConnectionData(
        var state: MemberState,
        val conn: PeerConnection,
        val events: ReceiveChannel<PeerConnectionEvent>
    )

    private sealed interface PeerConnectionEvent {
        data class TrackAdded(val stream: MediaStream) : PeerConnectionEvent
        data class TrackRemoved(val stream: MediaStream) : PeerConnectionEvent
        data class NewIceCandidate(val iceCandidate: IceCandidate) : PeerConnectionEvent
        data class IceCandidateStateChanged(val state: PeerConnection.IceConnectionState) :
            PeerConnectionEvent
    }

    private sealed interface Operation {
        data class AddTrack(val stream: MediaStreamTrack, val callback: SendChannel<Result<Unit>>) :
            Operation

    }

    private val operations = Channel<Operation>(capacity = 25)

    private val mediaConstraints = MediaConstraints().apply {
        mandatory.addAll(
            listOf(
                MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"),
                MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true")
            )
        )
    }

    val memberStates = flow {
        coroutineScope {
            val peerConnections = mutableMapOf<String, PeerConnectionData>()
            val localTracks = mutableListOf<MediaStreamTrack>()

            val (commandSender, messageReceiver, connState) = createWebSocketConnection(
                url = url, room = room, userId = userId, userName = userName, client = client
            )

            val connStateReceiver = connState.produceIn(this)

            try {
                while (true) {
                    select {
                        // Listen for all events coming from peer connection
                        for (conn in peerConnections.values) {
                            conn.events.onReceive { event ->
                                Log.d(TAG, "Received peer connection event $event")
                                when (event) {
                                    is PeerConnectionEvent.TrackAdded -> {
                                        conn.state =
                                            conn.state.copy(streams = conn.state.streams + event.stream)
                                        emit(peerConnections.collectMemberStates())
                                    }

                                    is PeerConnectionEvent.TrackRemoved -> {
                                        conn.state =
                                            conn.state.copy(streams = conn.state.streams - event.stream)
                                        emit(peerConnections.collectMemberStates())
                                    }

                                    is PeerConnectionEvent.IceCandidateStateChanged -> {
                                        conn.state =
                                            conn.state.copy(iceConnectionState = event.state)
                                        emit(peerConnections.collectMemberStates())
                                    }

                                    is PeerConnectionEvent.NewIceCandidate -> {
                                        commandSender.send(
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
                                }
                            }
                        }

                        // Listen for any incoming messages
                        messageReceiver.onReceive { m ->
                            Log.d(TAG, "Received incoming $m")
                            when (m.contentCase) {
                                Messages.ClientMessage.ContentCase.STATE_UPDATE -> {
                                    // Remove peer connections that are no longer present
                                    val toRemove =
                                        peerConnections.keys - m.stateUpdate.membersMap.keys
                                    for (userId in toRemove) {
                                        peerConnections.remove(userId)?.let { conn ->
                                            Log.d(TAG, "Removing $conn")
                                            conn.conn.dispose()
                                        }
                                    }

                                    // Add new peer connections
                                    val toAdd =
                                        m.stateUpdate.membersMap.keys - peerConnections.keys - userId
                                    for (newUserId in toAdd) {
                                        val shouldCreateOffer = newUserId > userId
                                        val (conn, initialOffer) =
                                            createPeerConnectionData(newUserId, localTracks, shouldCreateOffer)
                                        peerConnections[newUserId] = conn

                                        if (initialOffer != null) {
                                            commandSender.send(
                                                Messages.Command.newBuilder()
                                                    .setToUser(conn.state.userId)
                                                    .setOffer(initialOffer.description)
                                                    .build()
                                            )
                                        }
                                    }

                                    emit(peerConnections.collectMemberStates())
                                }

                                Messages.ClientMessage.ContentCase.OFFER -> {
                                    peerConnections[m.fromUser]?.conn?.apply {
                                        setRemote(SessionDescription.Type.OFFER, m.offer)

                                        val answer = createSessionDescription {
                                            createAnswer(it, mediaConstraints)
                                        }.getOrThrow()

                                        setLocal(answer)

                                        commandSender.send(
                                            Messages.Command.newBuilder()
                                                .setAnswer(answer.description)
                                                .setToUser(m.fromUser)
                                                .build()
                                        )
                                    }
                                }

                                Messages.ClientMessage.ContentCase.ANSWER -> {
                                    peerConnections[m.fromUser]?.conn?.setRemote(
                                        type = SessionDescription.Type.ANSWER,
                                        sdp = m.offer
                                    )
                                }

                                Messages.ClientMessage.ContentCase.ICE_CANDIDATE -> {
                                    peerConnections[m.fromUser]?.conn?.addIceCandidate(
                                        IceCandidate(
                                            m.iceCandidate.sdpMid,
                                            m.iceCandidate.sdpMlineIndex,
                                            m.iceCandidate.candidate
                                        )
                                    )
                                }

                                else -> {}
                            }
                        }

                        // Listen for any local operations
                        operations.onReceive { op ->
                            Log.d(TAG, "Received operation $op")
                            when (op) {
                                is Operation.AddTrack -> {
                                    val result = runCatching {
                                        for (conn in peerConnections.values) {
                                            conn.conn.addTrack(op.stream)
                                        }
                                    }

                                    op.callback.send(result)
                                }
                            }
                        }

                        // Listen for websocket state
                        connStateReceiver.onReceive { state ->
                            Log.d(TAG, "WebSocket connection state: $state")
                        }
                    }
                }
            } finally {
                for (conn in peerConnections.values) {
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
                    events.trySendBlocking(PeerConnectionEvent.NewIceCandidate(candidate))
                }

                override fun onAddStream(stream: MediaStream) {
                    events.trySendBlocking(PeerConnectionEvent.TrackAdded(stream))
                }

                override fun onRemoveStream(stream: MediaStream) {
                    events.trySendBlocking(PeerConnectionEvent.TrackRemoved(stream))
                }

                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                    events.trySendBlocking(PeerConnectionEvent.IceCandidateStateChanged(newState))
                }

                override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate?>?) {}
                override fun onDataChannel(dataChannel: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
            })!!

        for (track in localTracks) {
            conn.addTrack(track)
        }

        val offer = if (createLocalOffer) createSessionDescription { observer ->
            conn.createOffer(observer, mediaConstraints)
        }.getOrThrow().also {
            conn.setLocal(it)
        } else {
            null
        }

        return PeerConnectionData(
            state = MemberState(
                userId = userId,
                streams = emptyList(),
                iceConnectionState = PeerConnection.IceConnectionState.NEW
            ),
            conn = conn,
            events = events,
        ) to offer
    }

    suspend fun setLocalVideoTrack(track: VideoTrack) {
        val callback = Channel<Result<Unit>>()
        operations.send(Operation.AddTrack(track, callback))
        callback.receive().getOrThrow()
    }

    private fun Map<String, PeerConnectionData>.collectMemberStates(): Map<String, MemberState> {
        return this.mapValues { it.value.state }
    }

}