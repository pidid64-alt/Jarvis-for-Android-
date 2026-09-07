package kz.jarvis.app

import android.app.Application
import android.app.Activity
import android.os.Bundle

class App : Application() {

    companion object {
        /** Открыто ли сейчас окно приложения (важно для фоновой службы). */
        @Volatile
        var uiVisible: Boolean = false
            private set
    }

    private var started = 0

    override fun onCreate() {
        super.onCreate()
        Notify.createChannels(this)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started++
                uiVisible = true
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0) uiVisible = false
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
