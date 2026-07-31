package com.example.simplephotouploader

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        PilotDatabase.get(this)
        PhotoHistoryStore.migrateFromLegacyPrefsIfNeeded(this)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
