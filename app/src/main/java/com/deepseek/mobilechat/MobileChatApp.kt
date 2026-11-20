package com.deepseek.mobilechat

import android.app.Application
import com.deepseek.mobilechat.wiring.AppContainer

class MobileChatApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
