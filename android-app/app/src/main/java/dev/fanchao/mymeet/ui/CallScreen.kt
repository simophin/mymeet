package dev.fanchao.mymeet.ui

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
    val states = callSessionManager.memberStates.collectAsState()

    val eglBaseContext = remember {
        EglBase.create()
    }

    val context = LocalContext.current

    val localVideoTrack = remember(Unit) {
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
            eglBaseContext.eglBaseContext
        )

        val capturer = Camera2Capturer(context, cameraName, null)
        val source = peerConnectionFactory.createVideoSource(capturer.isScreencast)

        capturer.initialize(
            surfaceTextureHelper,
            context.applicationContext,
            source.capturerObserver
        )
        capturer.startCapture(480, 720, 30)

        peerConnectionFactory.createVideoTrack(
            "video_track_${UUID.randomUUID()}",
            source
        )
    }

    LaunchedEffect(localVideoTrack) {
        callSessionManager.setLocalVideoTrack(localVideoTrack)
    }

    DisposableEffect(localVideoTrack) {
        onDispose {
            localVideoTrack.dispose()
        }
    }

    val allVideoTracks = remember(states) {
        (sequenceOf(localVideoTrack) + states.value.values
            .asSequence()
            .flatMap { it.tracks.asSequence() })
            .filterIsInstance<VideoTrack>()
            .toList()
    }

    Scaffold { paddings ->
        BoxWithConstraints(modifier = Modifier.padding(paddings)) {
            val numColumns = floor(maxWidth / 240.dp).toInt().coerceAtLeast(1)
            val numRows = floor(maxHeight / 240.dp).toInt().coerceAtLeast(1)


            repeat(numRows) { i ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    repeat(numColumns) { j ->
                        val videoTrack = allVideoTracks.getOrNull(i * numColumns + j)
                        if (videoTrack != null) {
                            VideoRenderer(
                                modifier = Modifier.weight(1f),
                                videoTrack = videoTrack,
                                eglBaseContext = eglBaseContext.eglBaseContext,
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