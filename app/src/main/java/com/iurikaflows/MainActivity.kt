package com.iurikaflows

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

private const val PINK = 0xffff315c.toInt()
private const val GREY = 0xff9a9a9a.toInt()

class MainActivity : Activity() {
    companion object {
        var current: MainActivity? = null
    }

    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var updateAll: Button
    private var apps = listOf<App>()
    private val busy = mutableMapOf<String, Button>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 0)
        UpdateJob.schedule(this)

        val title = TextView(this).apply { text = "iurikaflows"; textSize = 26f; setTypeface(null, Typeface.BOLD) }
        val profile = TextView(this).apply {
            text = PROFILE.removePrefix("https://"); setTextColor(PINK)
            setOnClickListener { open(PROFILE) }
        }
        updateAll = Button(this).apply { text = "Update all"; visibility = View.GONE; setOnClickListener { apps.filter { it.hasUpdate }.forEach(::install) } }
        val reload = Button(this).apply { text = "↻"; setOnClickListener { refresh() } }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(title); addView(profile)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(updateAll); addView(reload)
        }
        status = TextView(this).apply { setTextColor(GREY); setPadding(0, dp(12), 0, 0) }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(16))
            addView(header); addView(status); addView(list)
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        current = this
        refresh()
    }

    override fun onPause() {
        super.onPause()
        current = null
    }

    fun refresh() {
        status.text = "Loading…"
        thread {
            val result = runCatching { Store.fetch(this) }
            runOnUiThread {
                result.onSuccess { apps = it; render() }
                    .onFailure { status.text = "Can't reach GitHub: ${it.message}" }
            }
        }
    }

    private fun render() {
        val updates = apps.count { it.hasUpdate }
        status.text = if (updates > 0) "$updates update(s) available" else "${apps.size} apps · all up to date"
        updateAll.visibility = if (updates > 0) View.VISIBLE else View.GONE
        list.removeAllViews()
        busy.clear()
        apps.sortedByDescending { it.hasUpdate }.forEach { list.addView(row(it)) }
    }

    private fun row(app: App): View {
        val icon = ImageView(this).apply {
            val drawable = app.installed?.let { runCatching { packageManager.getApplicationIcon(app.pkg!!) }.getOrNull() }
            if (drawable != null) setImageDrawable(drawable)
            else background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(PINK) }
        }
        val name = TextView(this).apply { text = app.repo; textSize = 17f; setTypeface(null, Typeface.BOLD) }
        val desc = TextView(this).apply { text = app.description; setTextColor(GREY); maxLines = 2 }
        val version = TextView(this).apply {
            textSize = 12f
            text = when {
                app.hasUpdate -> "${app.installed} → ${app.tag}"
                app.installed != null -> "Installed ${app.installed}"
                else -> app.tag
            }
            if (app.hasUpdate) setTextColor(PINK)
        }
        val button = Button(this).apply {
            text = when {
                app.apkUrl == null -> "View"
                app.hasUpdate -> "Update"
                app.installed != null -> "Open"
                else -> "Install"
            }
            setOnClickListener {
                when (text) {
                    "View" -> open(app.pageUrl)
                    "Open" -> packageManager.getLaunchIntentForPackage(app.pkg!!)?.let(::startActivity)
                    else -> install(app)
                }
            }
        }
        busy[app.repo] = button
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            addView(icon, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(14) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(name); addView(desc); addView(version)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(button)
            setOnClickListener { details(app) }
        }
    }

    private fun details(app: App) {
        AlertDialog.Builder(this)
            .setTitle("${app.repo} ${app.tag}")
            .setMessage(app.notes.ifBlank { app.description })
            .setPositiveButton("GitHub") { _, _ -> open(app.pageUrl) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun install(app: App) {
        if (app.apkUrl == null) return
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow iurikaflows to install apps, then come back", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val button = busy[app.repo]
        button?.isEnabled = false
        thread {
            val result = runCatching { Store.install(this, app) { p -> runOnUiThread { button?.text = "$p%" } } }
            runOnUiThread {
                result.onFailure { Toast.makeText(this, "${app.repo}: ${it.message}", Toast.LENGTH_LONG).show(); render() }
            }
        }
    }

    private fun open(url: String) = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
