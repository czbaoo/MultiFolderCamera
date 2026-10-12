package com.example.multifoldercamera.util

import android.content.Context

/** 简单的本地设置存储。 */
object PrefsHelper {

    private const val PREFS = "settings"
    private const val KEY_SYSTEM_CAMERA = "use_system_camera"

    /** 是否使用系统相机拍照（false = 使用内置相机连续拍摄）。 */
    fun useSystemCamera(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SYSTEM_CAMERA, false)

    fun setUseSystemCamera(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SYSTEM_CAMERA, value).apply()
    }
}
