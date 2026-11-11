package com.evofactory.hal

import android.app.Application

/** Starts the log first, for the launcher and the Home button helper alike */
class HalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.start(this)
    }
}
