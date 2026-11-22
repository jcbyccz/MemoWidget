package com.cczsx.notepad

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.cczsx.notepad.widget.MemoWidgetProvider

/**
 * 应用级生命周期：只做一件事——整个应用退到后台（用户要回桌面了）时补推一次小部件。
 *
 * 为什么要这一下：本机 MIUI Home 在小部件不可见时不会应用 RemoteViews 推送，
 * 保存后立即推送的那一次常被“吞掉”，用户回到桌面仍是旧内容，必须拖动改尺寸触发
 * 重载（宿主收到 options 变化才重新加载）。等应用整体进入后台时再推一次，正好落在
 * 宿主可见的时刻，无需用户操作即可刷新。
 */
class NotepadApp : Application() {

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            /** 处于 started 状态的页面数：降到 0 表示应用整体进入后台 */
            private var started = 0

            override fun onActivityStarted(activity: Activity) {
                started++
            }

            override fun onActivityStopped(activity: Activity) {
                started--
                if (started <= 0) {
                    started = 0
                    MemoWidgetProvider.onAppBackground(this@NotepadApp)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}