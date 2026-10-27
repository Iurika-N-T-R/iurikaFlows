package com.iurika.iurikaflows

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch

// Play Store style app page: header, facts, install button, what's new + changelog, about, infos
class DetailActivity : Activity() {
    private lateinit var app: App
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private var changelog = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = Store.apps.firstOrNull { it.repo == intent.getStringExtra("repo") } ?: return finish()
        @Suppress("DEPRECATION") window.statusBarColor = BG
        @Suppress("DEPRECATION") window.navigationBarColor = BG
        content = vbox()
        scroll = ScrollView(this).apply { setBackgroundColor(BG); fitsSystemWindows = true; isVerticalScrollBarEnabled = false; addView(content) }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        Store.onChange = ::render
        Store.resolveInstalled(this, listOf(app))
        render()
    }

    override fun onPause() {
        super.onPause()
        Store.onChange = null
    }

    private fun add(v: View, l: Int = 20, t: Int = 0, r: Int = 20, b: Int = 0) =
        content.addView(v, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) })

    private fun h2(title: String, extra: String = "") =
        add(hbox(tv(title, 18f, bold = true).grow(), tv(extra, 12f, MU)), b = 10)

    private fun info(label: String, value: View) =
        hbox(tv(label, 14f, MU).grow(dp(12)), value).apply { minimumHeight = dp(48); setPadding(0, dp(12), 0, dp(12)) }

    private fun render() {
        val y = scroll.scrollY
        content.removeAllViews()
        val busy = app.repo in Store.progress
        val repoUrl = "$PROFILE/${app.repo}"

        add(hbox(
            iconButton(R.drawable.ic_back, "Retour", 0) { finish() }.sized(dp(48), dp(48)),
            View(this).grow(),
            iconButton(R.drawable.ic_share, "Partager", 0) {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, repoUrl), null))
            }.sized(dp(48), dp(48)),
        ), l = 8, t = 10, r = 8)

        add(hbox(
            iconView(app, 84).sized(dp(84), dp(84), dp(18)),
            vbox(
                tv(app.repo, 26f, bold = true),
                tv(OWNER, 14f, AC_TX, true).apply { setPadding(0, dp(4), 0, 0); setOnClickListener { browse(PROFILE) } },
                tv("Open source · ${app.latest.date}", 12f, MU).apply { setPadding(0, dp(4), 0, 0) },
            ).grow(),
        ), t = 4, b = 20)

        fun fact(v: String, label: String) = vbox(tv(v, 15f, bold = true), tv(label, 11f, MU)).apply { for (i in 0 until childCount) (getChildAt(i) as android.widget.TextView).gravity = Gravity.CENTER }
        add(divided(LinearLayout(this).apply {
            listOf(fact(app.tag, "Version"), fact(sizeOf(app), "Taille"), fact(app.installed ?: "—", "Installée"), fact("${app.releases.size}", "Releases"))
                .forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
            background = rounded(S1, 18); setPadding(0, dp(14), 0, dp(14))
        }, 0xff2a2a32.toInt()), b = 18)

        add(LinearLayout(this).apply {
            if (app.installed != null && app.hasUpdate && !busy) addView(tv("Ouvrir", 15f, bold = true).apply {
                gravity = Gravity.CENTER; background = rounded(0, 25, 0xff3a3a44.toInt())
                setOnClickListener { packageManager.getLaunchIntentForPackage(app.pkg!!)?.let(::startActivity) }
            }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(10) })
            addView(actionButton(app, true), LinearLayout.LayoutParams(0, dp(50), 1.4f))
        }, b = 10)
        if (busy) add(progressBar(app), b = 10)
        add(tv("${app.installed?.let { "Installée : $it" } ?: "Non installée"} · signature vérifiée par Android", 12f, MU).apply { gravity = Gravity.CENTER }, b = 24)

        h2("Nouveautés", app.tag)
        add(vbox(
            tv(app.latest.notes.ifBlank { "Pas de notes" }, 14f, 0xffd4d4dc.toInt()).apply { setLineSpacing(0f, 1.4f) },
            *(if (changelog) app.releases.drop(1).flatMap { r ->
                listOf(tv("${r.tag}  ·  ${r.date}", 14f, bold = true).apply { setPadding(0, dp(16), 0, dp(4)) },
                    tv(r.notes.ifBlank { "Pas de notes" }, 14f, 0xffd4d4dc.toInt()).apply { setLineSpacing(0f, 1.4f) })
            } else emptyList()).toTypedArray(),
            *(if (app.releases.size > 1) listOf(tv(if (changelog) "Masquer le changelog" else "Voir le changelog complet", 13f, AC_TX, true).apply {
                setPadding(0, dp(10), 0, 0); setOnClickListener { changelog = !changelog; render() }
            }) else emptyList()).toTypedArray(),
        ).apply { background = rounded(S1, 20); setPadding(dp(16), dp(16), dp(16), dp(16)) }, b = 26)

        if (app.description.isNotBlank()) {
            h2("À propos")
            add(tv(app.description, 14f, 0xffc9c9d2.toInt()).apply { setLineSpacing(0f, 1.4f) }, b = 26)
        }

        h2("Infos")
        val s = Store.settings(this)
        val auto = Switch(this).apply {
            isChecked = s.getBoolean("auto_${app.repo}", true)
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(TX, 0xff9c9ca8.toInt()))
            trackTintList = ColorStateList(states, intArrayOf(AC, 0xff55555f.toInt()))
            setOnCheckedChangeListener { _, on -> s.edit().putBoolean("auto_${app.repo}", on).apply() }
        }
        add(divided(vbox(
            info("Dépôt", tv("GitHub ↗", 14f, AC_TX, true)).apply { setOnClickListener { browse(repoUrl) } },
            info("Package", tv(app.pkg ?: "—", 14f, bold = true)),
            info("Mise à jour auto", auto).apply { setOnClickListener { auto.toggle() } },
            info("Versions précédentes", tv("Releases ↗", 14f, AC_TX, true)).apply { setOnClickListener { browse("$repoUrl/releases") } },
            *(if (app.installed != null && !busy && !app.isSelf) listOf(info("Désinstaller", tv("Supprimer ›", 14f, AC_TX, true)).apply {
                setOnClickListener { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}"))) }
            }) else emptyList()).toTypedArray(),
        ), 0xff22222a.toInt()), b = 36)

        scroll.post { scroll.scrollTo(0, y) }
    }
}
