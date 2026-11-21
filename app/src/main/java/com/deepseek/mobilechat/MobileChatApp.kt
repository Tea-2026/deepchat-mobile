package com.deepseek.mobilechat

import android.app.Application
import com.deepseek.mobilechat.wiring.AppContainer

class MobileChatApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // A previous process may have died mid-stream: settle those rows before
        // the user can interact with them (runs off the main thread).
        container.reconcileStalledGenerations()
    }
}
