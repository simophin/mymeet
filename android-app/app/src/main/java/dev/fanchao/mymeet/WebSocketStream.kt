package dev.fanchao.mymeet

import android.util.Log
import dev.fanchao.mymeet.call.CallSettings
import dev.fanchao.mymeet.proto.Messages
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.selects.selectUnbiased

sealed interface WebSocketConnState {
    data class Connecting(val lastError: Throwable? = null) : WebSocketConnState
    data object Connected : WebSocketConnState
}

private const val TAG = "WebSocketStream"

fun CoroutineScope.createWebSocketConnection(
    callSettings: CallSettings,
    client: HttpClient,
): Triple<SendChannel<Messages.ServerMessage>, ReceiveChannel<Messages.ClientMessage>, StateFlow<WebSocketConnState>> {
    val serverMessagesChannel = Channel<Messages.ServerMessage>(capacity = 32)
    val clientMessagesChannel = Channel<Messages.ClientMessage>(capacity = 32)

    val states = channelFlow {
        while (true) {
            try {
                connect(
                    client = client,
                    settings = callSettings,
                    severMessageChannel = serverMessagesChannel,
                    clientMessagesChannel = clientMessagesChannel
                ) {
                    send(WebSocketConnState.Connected)
                }

                return@channelFlow
            } catch (e: CancellationException) {
                serverMessagesChannel.close()
                clientMessagesChannel.close()
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Connection error", e)
                send(WebSocketConnState.Connecting(e))
                delay(2000)
            }
        }
    }.stateIn(this, SharingStarted.Eagerly, WebSocketConnState.Connecting())

    return Triple(serverMessagesChannel, clientMessagesChannel, states)
}

private suspend fun connect(
    client: HttpClient,
    settings: CallSettings,
    severMessageChannel: ReceiveChannel<Messages.ServerMessage>,
    clientMessagesChannel: SendChannel<Messages.ClientMessage>,
    connectedCallback: suspend () -> Unit
) {
    Log.d(TAG, "Connecting to ${settings.serverUrl}")
    client.webSocket(
        urlString = URLBuilder(settings.serverUrl)
            .appendPathSegments("rooms", settings.room)
            .toString(),
        request = {
            method = HttpMethod.Get
            headers["X-User-Id"] = settings.userId
            headers["X-User-Name"] = settings.userName
        }
    ) {
        Log.d(TAG, "Connected to ${settings.serverUrl}")
        connectedCallback()

        var running = true

        while (running) {
            selectUnbiased {
                severMessageChannel.onReceiveCatching { cmd ->
                    if (cmd.isSuccess) {
                        Log.d(TAG, "About to send ws command: ${cmd.getOrThrow()}")
                        send(Frame.Binary(true, cmd.getOrThrow().toByteArray()))
                    } else {
                        Log.d(TAG, "Command channel closed")
                        close()
                        running = false
                    }
                }

                incoming.onReceive { frame ->
                    val message = (frame as? Frame.Binary)?.data?.let {
                        runCatching {
                            Messages.ClientMessage.parseFrom(it)
                        }.onFailure { e ->
                            Log.e(TAG, "Failed to parse message", e)
                        }.getOrNull()
                    }

                    if (message != null) {
                        Log.d(TAG, "Received ws message $message")
                        clientMessagesChannel.send(message)
                    }
                }
            }
        }
    }

}
