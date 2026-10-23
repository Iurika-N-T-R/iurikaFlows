package com.iurikaflows

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

// Play Store style app page: header, install button, about, what's new, full changelog
class DetailActivity : Activity() {
    private lateinit var app: App
    private lateinit var header: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = Store.apps.firstOrNull { it.repo == intent.getStringExtra("repo") } ?: return finish()

        header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            addView(header)
            if (app.description.isNotBlank()) {
                addView(heading("About this app"))
                addView(body(app.description))
            }
            addView(heading("What's new"))
            addView(entry(app.latest))
            if (app.releases.size > 1) {
                addView(heading("Changelog"))
                app.releases.drop(1).forEach { addView(entry(it)) }
            }
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        Store.onChange = ::renderTop
        Store.resolveInstalled(this, listOf(app))
        renderTop()
    }

    override fun onPause() {
        super.onPause()
        Store.onChange = null
    }

    private fun renderTop() {
        header.removeAllViews()
        header.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(iconView(app, 72), LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginEnd = dp(16) })
            addView(LinearLayout(this@DetailActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@DetailActivity).apply { text = app.repo; textSize = 24f; setTypeface(null, Typeface.BOLD) })
                addView(TextView(this@DetailActivity).apply { text = OWNER; setTextColor(PINK) })
            })
        })
        header.addView(TextView(this).apply {
            setTextColor(GREY); setPadding(0, dp(16), 0, dp(16))
            text = listOfNotNull(app.tag, sizeOf(app), app.latest.date, app.installed?.let { "installed $it" }).joinToString("  ·  ")
        })
        val buttons = LinearLayout(this)
        if (app.installed != null && app.repo !in Store.progress) {
            buttons.addView(Button(this).apply {
                text = "Uninstall"
                setOnClickListener { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}"))) }
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(12) })
        }
        buttons.addView(actionButton(app), LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(buttons)
        Store.progress[app.repo]?.let { p ->
            header.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { progress = p })
        }
    }

    private fun heading(s: String) = TextView(this).apply {
        text = s; textSize = 18f; setTypeface(null, Typeface.BOLD); setPadding(0, dp(28), 0, dp(8))
    }

    private fun body(s: String) = TextView(this).apply { text = s; setTextColor(0xffd0d0d0.toInt()); setLineSpacing(0f, 1.2f) }

    private fun entry(r: Release) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(16))
        addView(TextView(this@DetailActivity).apply { text = "${r.tag}  ·  ${r.date}"; setTypeface(null, Typeface.BOLD) })
        addView(body(r.notes.ifBlank { "No notes" }))
    }
}
