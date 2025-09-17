package dev.fanchao.mymeet

import android.app.Application
import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.serialization.json.Json
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.Loggable
import org.webrtc.Logging
import org.webrtc.PeerConnectionFactory

class App : Application() {

    val preferences: Preferences by lazy {
        Preferences(appContext = this, json = Json {
            isLenient = true

        })
    }

    val eglBase: EglBase by lazy {
        EglBase.create()
    }

    val peerConnectionFactory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this)
                .setInjectableLogger(
                    { message, severity, tag -> Log.d("WebRTC", "$tag: $message") },
                    Logging.Severity.LS_INFO
                )
                .createInitializationOptions()
        )

        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    val ktorClient: HttpClient by lazy {
        HttpClient(CIO) {
            install(WebSockets)
        }
    }

    override fun onCreate() {
        super.onCreate()
    }

    companion object {
        val Context.appInstance: App
            get() = (applicationContext as App)
    }
}