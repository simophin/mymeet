package dev.fanchao.mymeet.ui

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.fanchao.mymeet.App.Companion.appInstance
import dev.fanchao.mymeet.CallSessionManager
import io.getstream.webrtc.android.compose.VideoRenderer
import kotlinx.serialization.Serializable
import org.webrtc.Camera2Capturer
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoTrack
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor

@Serializable
data class CallScreenRoute(
    val room: String,
    val userId: String,
    val userName: String,
    val serverUrl: String
)

@Composable
fun CallScreen(
    callSessionManager: CallSessionManager,
    peerConnectionFactory: PeerConnectionFactory,
) {
    val states by callSessionManager.memberStates.collectAsState()

    val context = LocalContext.current

    val eglBaseContext = remember(context) { context.appInstance.eglBase.eglBaseContext }

    val localStream = remember(Unit) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraName = manager.cameraIdList.let { list ->
            list.asSequence()
                .filter {
                    manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                            CameraCharacteristics.LENS_FACING_FRONT
                }
                .firstOrNull()
                ?: list.firstOrNull()
        }


        val surfaceTextureHelper = SurfaceTextureHelper.create(
            "SurfaceTextureHelperThread",
            eglBaseContext
        )

        val capturer = Camera2Capturer(context, cameraName, null)
        val source = peerConnectionFactory.createVideoSource(capturer.isScreencast)

        capturer.initialize(
            surfaceTextureHelper,
            context.applicationContext,
            source.capturerObserver
        )
        capturer.startCapture(480, 720, 30)

        val videoTrack = peerConnectionFactory.createVideoTrack(
            "video_track_${UUID.randomUUID()}",
            source
        )

        val mediaStream = peerConnectionFactory.createLocalMediaStream("local")
        mediaStream.addTrack(videoTrack)

        mediaStream
    }

    LaunchedEffect(localStream) {
        callSessionManager.setLocalStream(localStream)
    }

    val allVideoTracks = remember(states) {
        (localStream.videoTracks.asSequence() +
                states.values.asSequence().flatMap { it.tracks.asSequence() })
            .filterIsInstance<VideoTrack>()
            .toList()
    }

    Scaffold { paddings ->
        BoxWithConstraints(modifier = Modifier.padding(paddings)) {
            val numColumns = floor(maxWidth / 240.dp).toInt().coerceAtLeast(1)
            val numRows = ceil(allVideoTracks.size / numColumns.toFloat()).toInt().coerceAtLeast(1)

            Column(modifier = Modifier.fillMaxSize()) {
                repeat(numRows) { i ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        repeat(numColumns) { j ->
                            val videoTrack = allVideoTracks.getOrNull(i * numColumns + j)
                            if (videoTrack != null) {
                                VideoRenderer(
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    videoTrack = videoTrack,
                                    eglBaseContext = eglBaseContext,
                                    rendererEvents = remember {
                                        object : RendererCommon.RendererEvents {
                                            override fun onFirstFrameRendered() {
                                            }

                                            override fun onFrameResolutionChanged(
                                                videoWidth: Int,
                                                videoHeight: Int,
                                                rotation: Int
                                            ) {
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}