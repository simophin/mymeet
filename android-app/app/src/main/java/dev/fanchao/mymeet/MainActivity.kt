package dev.fanchao.mymeet

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.fanchao.mymeet.App.Companion.appInstance
import dev.fanchao.mymeet.ui.CallScreen
import dev.fanchao.mymeet.ui.CallScreenRoute
import dev.fanchao.mymeet.ui.EnterRoomRoute
import dev.fanchao.mymeet.ui.theme.MyMeetTheme
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val requiredPermissions = listOf(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
        )

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissions(missingPermissions.toTypedArray(), 1)
        }

        val userId = appInstance.preferences.userId ?: run {
            UUID.randomUUID().toString().also {
                appInstance.preferences.userId = it
            }
        }

        val callSessionManager = CallSessionManager(
            userId = userId,
            url = "ws://172.19.132.15:3000",
            userName = android.os.Build.MODEL,
            client = appInstance.ktorClient,
            room = "test room",
            peerConnectionFactory = appInstance.peerConnectionFactory,
            iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.infra.net:3478").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun.miwifi.com:3478").createIceServer(),
            ),
            scope = lifecycleScope
        )

        enableEdgeToEdge()
        setContent {
            val navController = rememberNavController()

            MyMeetTheme {
                NavHost(navController = navController, startDestination = CallScreenRoute(
                    room = "test",
                    userId = UUID.randomUUID().toString(),
                    userName = "test",
                    serverUrl = "ws://172.19.132.15:3000"
                )) {
                    composable<EnterRoomRoute> {

                    }

                    composable<CallScreenRoute> {
                        val route = it.toRoute<CallScreenRoute>()

                        CallScreen(
                            callSessionManager = callSessionManager,
                            peerConnectionFactory = appInstance.peerConnectionFactory,
                        )
                    }
                }
            }
        }
    }
}
