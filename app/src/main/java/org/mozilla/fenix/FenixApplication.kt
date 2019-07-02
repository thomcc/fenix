/* This Source Code Form is subject to the terms of the Mozilla Public
   License, v. 2.0. If a copy of the MPL was not distributed with this
   file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix

import android.annotation.SuppressLint
import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import io.reactivex.plugins.RxJavaPlugins
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import mozilla.appservices.Megazord
import mozilla.appservices.httpconfig.RustHttpConfig
import mozilla.components.lib.fetch.httpurlconnection.HttpURLConnectionClient
import mozilla.components.service.fretboard.Fretboard
import mozilla.components.service.fretboard.source.kinto.KintoExperimentSource
import mozilla.components.service.fretboard.storage.flatfile.FlatFileExperimentStorage
import mozilla.components.support.base.log.Log
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.base.log.sink.AndroidLogSink
import mozilla.components.support.ktx.android.content.isMainProcess
import mozilla.components.support.ktx.android.content.runOnlyInMainProcess
import mozilla.components.support.rustlog.RustLog
import org.mozilla.fenix.components.Components
import org.mozilla.fenix.utils.Settings
import java.io.File

@SuppressLint("Registered")
@Suppress("TooManyFunctions")
open class FenixApplication : Application() {
    lateinit var fretboard: Fretboard
    lateinit var experimentLoader: Deferred<Boolean>
    var experimentLoaderComplete: Boolean = false

    open val components by lazy { Components(this) }

    override fun onCreate() {
        super.onCreate()

        setupApplication()
    }

    open fun setupApplication() {
        // loadExperiments does things that run in parallel with the rest of setup.
        // Call the function as early as possible so there's maximum overlap.
        experimentLoader = loadExperiments()

        setDayNightTheme()

        setupMegazord()
        setupLogging()
        registerRxExceptionHandling()
        setupCrashReporting()

        if (!isMainProcess()) {
            // If this is not the main process then do not continue with the initialization here. Everything that
            // follows only needs to be done in our app's main process and should not be done in other processes like
            // a GeckoView child process or the crash handling process. Most importantly we never want to end up in a
            // situation where we create a GeckoRuntime from the Gecko child process (
            return
        }

        setupLeakCanary()
        if (Settings.getInstance(this).isTelemetryEnabled) {
            components.analytics.metrics.start()
        }
    }

    private fun registerRxExceptionHandling() {
        RxJavaPlugins.setErrorHandler {
            it.cause?.run {
                throw this
            } ?: throw it
        }
    }

    /**
     * Wait until all experiments are loaded
     *
     * This function will cause the caller to block until the experiments are loaded.
     * It could be used in any number of reasons, but the most likely scenario is that
     * a calling function needs to access the loaded experiments and wants to
     * make sure that the experiments are loaded from the server before doing so.
     *
     * Because this function is synchronized, it can only be accessed by one thread
     * at a time. Anyone trying to check the loaded status will wait if someone is
     * already waiting. This is okay because the thread waiting for access to the
     * function will immediately see that the loader is complete upon gaining the
     * opportunity to run the function.
     */
    @Synchronized
    public fun waitForExperimentsToLoad() {

        // Do we know that we are already complete?
        if (!experimentLoaderComplete) {
            // No? Have we completed since the last call?
            if (!experimentLoader.isCompleted) {
                // No? Well, let's wait.
                runBlocking {
                    experimentLoader.await()
                }
            }
            // Set this so we don't have to wait on the next call.
            experimentLoaderComplete = true
        }
    }

    protected open fun setupLeakCanary() {
        // no-op, LeakCanary is disabled by default
    }

    open fun toggleLeakCanary(newValue: Boolean) {
        // no-op, LeakCanary is disabled by default
    }

    private fun setupLogging() {
        // We want the log messages of all builds to go to Android logcat
        Log.addSink(AndroidLogSink())
        // We want rust logging to go through the log sinks.
        // This has to happen after initializing the megazord.
        RustLog.enable()
    }

    private fun loadExperiments(): Deferred<Boolean> {
        return GlobalScope.async(Dispatchers.IO) {
            val experimentsFile = File(filesDir, EXPERIMENTS_JSON_FILENAME)
            val experimentSource = KintoExperimentSource(
                EXPERIMENTS_BASE_URL,
                EXPERIMENTS_BUCKET_NAME,
                EXPERIMENTS_COLLECTION_NAME,
                // TODO Switch back to components.core.client (see https://github.com/mozilla-mobile/fenix/issues/1329)
                HttpURLConnectionClient()
            )
            // TODO add ValueProvider to keep clientID in sync with Glean when ready
            fretboard = Fretboard(experimentSource, FlatFileExperimentStorage(experimentsFile))
            fretboard.loadExperiments()
            Logger.debug("Bucket is ${fretboard.getUserBucket(this@FenixApplication)}")
            Logger.debug("Experiments active: ${fretboard.getExperimentsMap(this@FenixApplication)}")
            fretboard.updateExperiments()
            return@async true
        }
    }

    private fun setupCrashReporting() {
        components
            .analytics
            .crashReporter
            .install(this)
    }

    /**
     * Initiate Megazord sequence! Megazord Battle Mode!
     *
     * TODO(thom) rewrite, possibly add a link to an in-depth doc in a-s repo
     *
     * Mozilla Application Services publishes many native (Rust) code libraries that stand alone: each published Android
     * ARchive (AAR) contains managed code (classes.jar) and multiple .so library files (one for each supported
     * architecture). That means consuming multiple such libraries entails at least two .so libraries, and each of those
     * libraries includes the entire Rust standard library as well as (potentially many) duplicated dependencies. To
     * save space and allow cross-component native-code Link Time Optimization (LTO, i.e., inlining, dead code
     * elimination, etc).
     * Application Services also publishes composite libraries -- so called megazord libraries or just megazords -- that
     * compose multiple Rust components into a single optimized .so library file.
     */
    private fun setupMegazord() {
        Megazord.init()
        RustHttpConfig.setClient(lazy { components.core.client })
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        runOnlyInMainProcess {
            components.core.sessionManager.onLowMemory()
        }
    }

    @SuppressLint("WrongConstant")
    // Suppressing erroneous lint warning about using MODE_NIGHT_AUTO_BATTERY, a likely library bug
    private fun setDayNightTheme() {
        when {
            Settings.getInstance(this).shouldUseLightTheme -> {
                AppCompatDelegate.setDefaultNightMode(
                    AppCompatDelegate.MODE_NIGHT_NO
                )
            }
            Settings.getInstance(this).shouldUseDarkTheme -> {
                AppCompatDelegate.setDefaultNightMode(
                    AppCompatDelegate.MODE_NIGHT_YES
                )
            }
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P &&
                    Settings.getInstance(this).shouldUseAutoBatteryTheme -> {
                AppCompatDelegate.setDefaultNightMode(
                    AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY
                )
            }
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P &&
                    Settings.getInstance(this).shouldFollowDeviceTheme -> {
                AppCompatDelegate.setDefaultNightMode(
                    AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                )
            }
            // First run of app no default set, set the default to Follow System for 28+ and Normal Mode otherwise
            else -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    AppCompatDelegate.setDefaultNightMode(
                        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    )
                    Settings.getInstance(this).setFollowDeviceTheme(true)
                } else {
                    AppCompatDelegate.setDefaultNightMode(
                        AppCompatDelegate.MODE_NIGHT_NO
                    )
                    Settings.getInstance(this).setLightTheme(true)
                }
            }
        }
    }
}
