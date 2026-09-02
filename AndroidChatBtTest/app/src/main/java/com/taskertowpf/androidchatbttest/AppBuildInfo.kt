package com.taskertowpf.androidchatbttest

object AppBuildInfo {
    val versionLabel: String
        get() = "v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE}, build ${BuildConfig.BUILD_TIME})"
}
