package dev.fanchao.mymeet

import android.util.Log
import dev.fanchao.mymeet.call.CallSettings
import dev.fanchao.mymeet.proto.Messages
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.selectUnbiased
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SessionDescription

private const val TAG = "CallSessionManager"

class CallSessionManager(
    private val settings: CallSettings,
    private val client: HttpClient,
    private val peerConnectionFactory: PeerConnectionFactory,
    private val iceServers: List<PeerConnection.IceServer>,
    scope: CoroutineScope,
) {
    data class MemberState(
        val userName: String,
        val tracks: List<MediaStreamTrack>,
        val iceConnectionState: PeerConnection.IceConnectionState,
    )

    private class PeerConnectionData(
        var states: MutableStateFlow<MemberState>,
        val incomingCommands: SendChannel<Messages.Command>,
        val operationSender: SendChannel<Operation>,
        val job: Job,
    )

    private sealed interface Operation {
        data class SetLocalStream(val stream: MediaStream) : Operation
    }

    private val operations = Channel<Operation>(capacity = 25)

    private val mediaConstraints = MediaConstraints().apply {
//        mandatory.add(MediaConstraints.KeyValuePair("audio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("video", "true"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val memberStates: StateFlow<Map<String, MemberState>> = flow {
        coroutineScope {
            val (commandSender, messageReceiver, connState) = createWebSocketConnection(
                callSettings = settings,
                client = client
            )

            val peerConnections: MutableMap<String, PeerConnectionData> = mutableMapOf()
            var localStream: MediaStream? = null
            val connStateReceiver = connState.produceIn(this)

            try {
                while (true) {
                    selectUnbiased {
                        // Listen for any incoming messages
                        messageReceiver.onReceive { m ->
                            when (m.contentCase) {
                                Messages.ClientMessage.ContentCase.STATE_UPDATE -> {
                                    // Remove peer connections that are no longer present
                                    (peerConnections.keys - m.stateUpdate.membersMap.keys).forEach { toRemove ->
                                        peerConnections.remove(toRemove)?.let { conn ->
                                            Log.d(TAG, "Removing connection to $toRemove")
                                            conn.job.cancel()
                                        }
                                    }

                                    // Add new peer connections
                                    (m.stateUpdate.membersMap.keys - peerConnections.keys - settings.userId)
                                        .forEach { newUserId ->
                                            val states = MutableStateFlow(
                                                MemberState(
                                                    userName = "",
                                                    tracks = emptyList(),
                                                    iceConnectionState = PeerConnection.IceConnectionState.NEW,
                                                )
                                            )

                                            val operationChannel = Channel<Operation>()
                                            val incomingMessages = Channel<Messages.Command>()

                                            val job = launch {
                                                serveConnection(
                                                    remoteUserId = newUserId,
                                                    states = states,
                                                    initialLocalStream = localStream,
                                                    operationReceiver = operationChannel,
                                                    commandsReceiver = incomingMessages,
                                                    commandSender = commandSender,
                                                )
                                            }

                                            peerConnections[newUserId] = PeerConnectionData(
                                                states = states,
                                                incomingCommands = incomingMessages,
                                                job = job,
                                                operationSender = operationChannel,
                                            )
                                        }

                                    // Update user names if applicable
                                    m.stateUpdate.membersMap.forEach { (id, data) ->
                                        if (data.hasName()) {
                                            peerConnections[id]?.states?.update {
                                                it.copy(userName = data.name)
                                            }
                                        }
                                    }

                                    // Emit state updates
                                    emit(peerConnections.toMap())
                                }

                                Messages.ClientMessage.ContentCase.COMMAND -> {
                                    if (m.hasFromUser()) {
                                        peerConnections[m.fromUser]?.incomingCommands?.send(
                                            m.command
                                        )
                                    }
                                }

                                Messages.ClientMessage.ContentCase.CONTENT_NOT_SET -> {
                                    Log.w(TAG, "Received unknown message")
                                }
                            }
                        }


                        // Listen for websocket state
                        connStateReceiver.onReceive { state ->
                            Log.d(TAG, "WebSocket connection state: $state")
                        }

                        // Listen for operations
                        operations.onReceive { op ->
                            when (op) {
                                is Operation.SetLocalStream -> {
                                    localStream = op.stream

                                    peerConnections.forEach { (_, conn) ->
                                        conn.operationSender.send(op)
                                    }
                                }
                            }
                        }
                    }
                }
            } finally {
                for (c in peerConnections) {
                    c.value.job.cancel()
                }

                commandSender.close()
                localStream?.dispose()
            }
        }
    }.flatMapLatest { peerConnections ->
        if (peerConnections.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(
                peerConnections
                    .asSequence()
                    .map { (id, data) -> data.states.map { states -> id to states } }
                    .asIterable()
            ) {
                it.associate { (id, state) -> id to state }
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())


    suspend fun setLocalStream(stream: MediaStream) {
        operations.send(Operation.SetLocalStream(stream))
    }

    private suspend fun serveConnection(
        remoteUserId: String,
        states: MutableStateFlow<MemberState>,
        initialLocalStream: MediaStream?,
        operationReceiver: ReceiveChannel<Operation>,
        commandsReceiver: ReceiveChannel<Messages.Command>,
        commandSender: SendChannel<Messages.ServerMessage>,
    ) = coroutineScope {
        val polite = settings.userId > remoteUserId
        Log.d(TAG, "$remoteUserId: Connection started, polite = $polite")
        val (observer, eventsReceiver) = createPeerConnectionEventStream()
        val conn = peerConnectionFactory.createPeerConnection(iceServers, observer)!!

        try {
            initialLocalStream?.let(conn::setLocalStream)

            while (true) {
                selectUnbiased {
                    eventsReceiver.onReceive { evt ->
                        Log.d(TAG, "$remoteUserId: Received event $evt")
                        when (evt) {
                            is PeerConnectionEvent.IceCandidateStateChanged -> {}
                            is PeerConnectionEvent.NewIceCandidate -> {
                                commandSender.send(
                                    Messages.ServerMessage.newBuilder()
                                        .setCommand(Messages.Command.newBuilder()
                                            .setIceCandidate(evt.iceCandidate.toProtoBuf())
                                        )
                                        .setToUser(remoteUserId)
                                        .build()
                                )
                            }

                            PeerConnectionEvent.RenegotiationNeeded -> {
                                val offer = conn.suspendCreateOffer(mediaConstraints)

                                if (conn.signalingState() != PeerConnection.SignalingState.STABLE) {
                                    Log.d(TAG, "${remoteUserId}: Ignoring renegotiation")
                                    return@onReceive
                                }

                                conn.setLocal(offer)
                                commandSender.send(
                                    Messages.ServerMessage.newBuilder()
                                        .setCommand(Messages.Command.newBuilder()
                                            .setDescription(offer.toProtoBuf())
                                        )
                                        .setToUser(remoteUserId)
                                        .build()
                                )
                            }

                            is PeerConnectionEvent.TrackAdded -> {
                                states.update { it.copy(tracks = it.tracks + evt.receiver.track()!!) }
                            }

                            is PeerConnectionEvent.TrackRemoved -> {
                                states.update { it.copy(tracks = it.tracks - evt.receiver.track()!!) }
                            }
                        }
                    }

                    commandsReceiver.onReceive { cmd ->
                        Log.d(TAG, "$remoteUserId: Received command $cmd")
                        when (cmd.contentCase) {
                            Messages.Command.ContentCase.DESCRIPTION -> {
                                val offerCollision = cmd.description.type == Messages.SdpType.OFFER &&
                                        conn.signalingState() != PeerConnection.SignalingState.STABLE

                                val ignoreOffer = !polite && offerCollision
                                if (ignoreOffer) {
                                    Log.d(TAG, "$remoteUserId: Ignoring offer")
                                    return@onReceive
                                }

                                if (offerCollision) {
                                    conn.setLocal(SessionDescription(SessionDescription.Type.ROLLBACK, ""))
                                }

                                conn.setRemote(cmd.description.toWebRtc())

                                if (cmd.description.type == Messages.SdpType.OFFER) {
                                    conn.setLocal(conn.suspendCreateAnswer(mediaConstraints))
                                    commandSender.send(
                                        Messages.ServerMessage.newBuilder()
                                            .setCommand(Messages.Command.newBuilder()
                                                .setDescription(conn.localDescription.toProtoBuf())
                                            )
                                            .setToUser(remoteUserId)
                                            .build()
                                    )
                                }
                            }

                            Messages.Command.ContentCase.ICE_CANDIDATE -> {
                                conn.addIceCandidate(cmd.iceCandidate.toWebRtc())
                            }

                            Messages.Command.ContentCase.CONTENT_NOT_SET -> {}
                        }
                    }

                    operationReceiver.onReceive { op ->
                        Log.d(TAG, "$remoteUserId: Received operation $op")
                        when (op) {
                            is Operation.SetLocalStream -> conn.setLocalStream(op.stream)
                        }
                    }
                }
            }


        } finally {
            Log.d(TAG, "$remoteUserId: Disposing connection")
            conn.dispose()
        }
    }
}