package com.deepseek.mobilechat

import android.app.Application
import com.deepseek.mobilechat.wiring.AppContainer

class MobileChatApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Startup does no scanning here on purpose: the conversation engine
        // settles its own interrupted turns when it is first touched, and the
        // legacy `messages` table is no longer read for history. (The previous
        // startup pass ran one query per message of every conversation on every
        // launch — dead work that competed with the UI's own database reads.)
    }
}
