package org.cortex.terminal

import android.app.Activity
import android.app.Application
import android.os.Bundle

class CortexApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LocaleHelper.applySaved(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
            }

            override fun onActivityStopped(activity: Activity) {
                if (startedActivities > 0) startedActivities--
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        // The Ubuntu system is checked and configured by MainActivity, off the main thread: this
        // runs for every start of the process (the service, the documents provider...) and must
        // stay fast.
    }

    companion object {
        @Volatile
        private var startedActivities = 0

        /** True while any Cortex screen is visible; used to skip notifications the user would just see. */
        val isInForeground: Boolean
            get() = startedActivities > 0
    }
}
