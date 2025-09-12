package dev.fanchao.mymeet

import android.app.Application
import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.serialization.json.Json

class App : Application() {

    val preferences: Preferences by lazy {
        Preferences(appContext = this, json = Json {
            isLenient = true

        })
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