package dev.fanchao.mymeet

import android.app.Application
import android.content.Context
import kotlinx.serialization.json.Json

class App : Application() {

    val preferences: Preferences by lazy {
        Preferences(appContext = this, json = Json {
            isLenient = true

        })
    }

    override fun onCreate() {
        super.onCreate()
    }

    companion object {
        val Context.appInstance: App
            get() = (applicationContext as App)
    }
}