package com.imageorga.app

import android.app.Application
import com.imageorga.app.data.Settings
import com.imageorga.app.index.Notifications
import com.imageorga.app.index.Scheduler

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        com.imageorga.app.ml.Diagnostics.init(this)
        Notifications.createChannel(this)
        Scheduler.applyAutoIndex(this)
    }
}
