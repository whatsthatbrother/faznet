package com.faznet.tv.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration

/** A group of the home screen's side list (or a category of the library). */
data class GroupItem(val key: String, val title: String, val count: Int)

fun Context.isTv(): Boolean =
    (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        packageManager.hasSystemFeature("android.software.leanback")
