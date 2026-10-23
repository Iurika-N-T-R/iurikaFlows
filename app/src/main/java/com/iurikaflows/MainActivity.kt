package com.iurikaflows

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

const val PINK = 0xffff315c.toInt()
const val GREY = 0xff9a9a9a.toInt()

fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Activity.rounded(color: Int, radius: Int) = GradientDrawable().apply { cornerRadius = dp(radius).toFloat(); setColor(color) }

// Installed app's real icon, else a pink tile with the first letter
fun Activity.iconView(app: App, size: Int): View {
    val icon = app.pkg?.takeIf { app.installed != null }?.let { runCatching { packageManager.getApplicationIcon(it) }.getOrNull() }
    return if (icon != null) ImageView(this).apply { setImageDrawable(icon) }
    else TextView(this).apply {
        text = app.repo.take(1).uppercase(); gravity = Gravity.CENTER; textSize = size / 2.2f
        setTypeface(null, Typeface.BOLD); background = rounded(PINK, size / 4)
    }
}

// Install / Update / Open / download percent, same on the list and the details screen
fun Activity.actionButton(app: App) = Button(this).apply {
    text = Store.label(app)
    isEnabled = app.repo !in Store.progress
    setTextColor(0xffffffff.toInt())
    background = rounded(if (isEnabled) PINK else 0xff5a2030.toInt(), 20)
    setOnClickListener { Store.act(this@actionButton, app) }
}

fun Activity.sizeOf(app: App): String = Formatter.formatShortFileSize(this, app.size)

class MainActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var updateAll: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 0)
        UpdateJob.schedule(this)

        val title = TextView(this).apply { text = "iurikaflows"; textSize = 26f; setTypeface(null, Typeface.BOLD) }
        val profile = TextView(this).apply {
            text = PROFILE.removePrefix("https://"); setTextColor(PINK)
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PROFILE))) }
        }
        updateAll = Button(this).apply {
            text = "Update all"; visibility = View.GONE
            setOnClickListener { Store.apps.filter { it.hasUpdate }.forEach { Store.act(this@MainActivity, it) } }
        }
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
        refresh()
    }

    override fun onResume() {
        super.onResume()
        Store.onChange = ::render
        // Back from an app or the system installer: versions may have changed
        Store.resolveInstalled(this, Store.apps)
        render()
    }

    override fun onPause() {
        super.onPause()
        Store.onChange = null
    }

    private fun refresh() {
        status.text = "Loading…"
        thread {
            val result = runCatching { Store.fetch(this) }
            runOnUiThread {
                result.onSuccess { Store.apps = it; render() }
                    .onFailure { status.text = "Can't reach GitHub: ${it.message}" }
            }
        }
    }

    private fun render() {
        val apps = Store.apps
        if (apps.isEmpty()) return
        val updates = apps.count { it.hasUpdate }
        status.text = if (updates > 0) "$updates update(s) available" else "${apps.size} apps · all up to date"
        updateAll.visibility = if (updates > 0) View.VISIBLE else View.GONE
        list.removeAllViews()
        apps.sortedByDescending { it.hasUpdate }.forEach { list.addView(row(it)) }
    }

    private fun row(app: App): View {
        val name = TextView(this).apply { text = app.repo; textSize = 17f; setTypeface(null, Typeface.BOLD) }
        val desc = TextView(this).apply { text = app.description; setTextColor(GREY); maxLines = 2 }
        val version = TextView(this).apply {
            textSize = 12f
            text = when {
                app.hasUpdate -> "${app.installed} → ${app.tag} · ${sizeOf(app)}"
                app.installed != null -> "Installed ${app.installed}"
                else -> "${app.tag} · ${sizeOf(app)}"
            }
            if (app.hasUpdate) setTextColor(PINK)
        }
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            addView(iconView(app, 52), LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(14) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(name); addView(desc); addView(version)
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            addView(actionButton(app))
            setOnClickListener { startActivity(Intent(this@MainActivity, DetailActivity::class.java).putExtra("repo", app.repo)) }
        }
    }
}
