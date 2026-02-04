package com.example.visionassist

import android.app.Application
import android.util.Log

/**
 * Application class for Vision Assist
 * Initializes global resources and configurations
 */
class VisionAssistApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "VisionAssist Application initialized")
    }

    companion object {
        private const val TAG = "VisionAssistApp"
        
        @Volatile
        private var instance: VisionAssistApp? = null

        fun getInstance(): VisionAssistApp {
            return instance ?: throw IllegalStateException("Application not initialized")
        }
    }
}
