package com.deepseek.mobilechat.wiring

import com.deepseek.mobilechat.application.ClockPort
import com.deepseek.mobilechat.application.IdPort
import java.util.UUID

class SystemClockPort : ClockPort {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

class UuidIdPort : IdPort {
    override fun newId(): String = UUID.randomUUID().toString()
}
