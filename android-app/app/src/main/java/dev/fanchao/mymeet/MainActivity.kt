package dev.fanchao.mymeet

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.navigation.NavOptions
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.fanchao.mymeet.App.Companion.appInstance
import dev.fanchao.mymeet.ui.CallScreen
import dev.fanchao.mymeet.ui.CallScreenRoute
import dev.fanchao.mymeet.ui.EditCallSettingsRoute
import dev.fanchao.mymeet.ui.EditCallSettingsScreen
import dev.fanchao.mymeet.ui.theme.MyMeetTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val requiredPermissions = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
        )

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissions(missingPermissions.toTypedArray(), 1)
        }


        enableEdgeToEdge()
        setContent {
            val navController = rememberNavController()

            val startDestination = remember {
                appInstance.preferences.lastUsedCallSettings.value.lastOrNull()?.let(::CallScreenRoute)
                    ?: EditCallSettingsRoute
            }

            MyMeetTheme {
                NavHost(navController = navController, startDestination = startDestination) {
                    composable<EditCallSettingsRoute> {
                        EditCallSettingsScreen(
                            editing = null,
                            onSaved = { settings ->
                                navController.navigate(
                                    route = CallScreenRoute(settings),
                                    navOptions = NavOptions.Builder()
                                        .setPopUpTo(it, inclusive = true)
                                        .build()
                                )
                            }
                        )
                    }

                    composable<CallScreenRoute> {
                        val route = it.toRoute<CallScreenRoute>()
                        CallScreen(route.toCallSettings())
                    }
                }
            }
        }
    }
}
