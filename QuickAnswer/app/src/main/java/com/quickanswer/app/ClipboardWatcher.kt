package com.quickanswer.app

import android.app.Activity
import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * 剪贴板持续识别：进程启动即注册，取代原先「开放平台」页里必须手点的「识别剪贴板」按钮。
 *
 * 受 Android 10+ 系统限制——只有「当前获焦的应用」才能读取剪贴板，后台服务读不到。
 * 因此本监听在两个时机生效：
 *  1. 剪贴板变化回调（[ClipboardManager.OnPrimaryClipChangedListener]）：App 前台获焦期间
 *     复制到新内容时实时识别；
 *  2. 界面回到前台（[Application.ActivityLifecycleCallbacks.onActivityResumed]）主动补读一次，
 *     覆盖「先复制 Key、再打开 App」。
 * 悬浮球覆盖在其它应用之上时无法读取，属系统行为，非本应用可控。
 *
 * 识别复用 [Vendors]：像 API Key 交由 [Listener.onApiKey]，厂商控制台链接交由
 * [Listener.onVendorLink]。已处理内容会去重，避免同一份内容重复触发。
 */
object ClipboardWatcher {

    /** 识别结果回调：由前台界面（如开放平台页）注册后处理，自己决定弹窗/跳转。 */
    interface Listener {
        /** 复制内容像 API Key；[vendor] 为可推断的来源厂商，可能为空。 */
        fun onApiKey(text: String, vendor: Vendor?)

        /** 复制内容是某厂商的控制台链接。 */
        fun onVendorLink(vendor: Vendor)
    }

    private val listeners = mutableListOf<Listener>()
    private var started = false

    /** 最近一次已被前台界面消费的内容，用于去重，兼作忽略本应用自写剪贴板（如复制答案）。 */
    private var lastHandled: String? = null

    /** 最近一次已做过「无界面时轻提示」的内容，避免反复弹 Toast。 */
    private var lastHinted: String? = null

    /** 在 [App.onCreate] 调用一次：注册系统剪贴板回调与界面前台回调。 */
    fun start(app: Application) {
        if (started) return
        started = true

        (app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.addPrimaryClipChangedListener { handle(app) }

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = handle(app)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /** 标记这段文本是本应用自己写入剪贴板的，随后触发的剪贴板回调应忽略它。 */
    fun markSelfWrite(text: String) {
        val t = text.trim()
        lastHandled = t
        lastHinted = t
    }

    /** 读取剪贴板 → 识别 → 分发；识别不到、为空或与上次相同则忽略。 */
    private fun handle(context: Context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = cm.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(context)?.toString()?.trim().orEmpty()
        if (text.isBlank()) return

        val isKey = Vendors.looksLikeKey(text)
        val vendorLink = if (isKey) null else Vendors.matchByUrlText(text)
        if (!isKey && vendorLink == null) return

        if (listeners.isEmpty()) {
            // 无界面在处理（例如停留在首页）：仅对 API Key 轻提示一次，引导去「开放平台」保存。
            // 注意此处不置 lastHandled——之后打开开放平台页仍应能自动弹出保存框。
            if (isKey && text != lastHinted) {
                lastHinted = text
                toast(context, context.getString(R.string.browser_clip_detected))
            }
            return
        }

        if (text == lastHandled) return
        lastHandled = text

        if (isKey) {
            listeners.toList().forEach { it.onApiKey(text, Vendors.matchByKey(text)) }
        } else {
            listeners.toList().forEach { it.onVendorLink(vendorLink!!) }
        }
    }

    private fun toast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
