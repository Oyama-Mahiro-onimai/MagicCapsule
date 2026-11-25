package com.app.magiccapsule

import android.app.Application
import com.app.magiccapsule.crash.AppLogger
import com.app.magiccapsule.crash.GlobalCrashHandler

class MagicCapsuleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        GlobalCrashHandler.install(this)
        AppLogger.init(this)
    }
}
