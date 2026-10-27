package com.iurika.iurikaflows

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import kotlin.concurrent.thread

// Palette of the design mockup
const val BG = 0xff0e0e11.toInt()
const val NAV = 0xff16161b.toInt()
const val S1 = 0xff17171c.toInt()
const val S2 = 0xff1c1c22.toInt()
const val S3 = 0xff26262e.toInt()
const val LINE = 0xff24242b.toInt()
const val TX = 0xfff3f3f6.toInt()
const val MU = 0xffa3a3ae.toInt()
const val MU2 = 0xff8a8a96.toInt()
const val AC = 0xffff4d7d.toInt()
const val ON_AC = 0xff2a0712.toInt()
const val AC_SOFT = 0xff2a1520.toInt()
const val AC_LINE = 0xff4a2234.toInt()
const val AC_TX = 0xffff8fad.toInt()
const val AC_CHIP = 0xff3e1a2b.toInt()
const val OK = 0xff7fe0b0.toInt()

fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun csl(c: Int) = ColorStateList.valueOf(c)

fun Activity.rounded(color: Int, radius: Int, stroke: Int = 0) = GradientDrawable().apply {
    cornerRadius = dp(radius).toFloat(); setColor(color); if (stroke != 0) setStroke(dp(1), stroke)
}

fun Activity.tv(s: CharSequence, size: Float = 14f, color: Int = TX, bold: Boolean = false) = TextView(this).apply {
    text = s; textSize = size; setTextColor(color); if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
}

fun Activity.icon(res: Int, color: Int) = ImageView(this).apply { setImageResource(res); imageTintList = csl(color) }

// Round icon button, 44dp by default
fun Activity.iconButton(res: Int, desc: String, bg: Int = S2, onClick: () -> Unit) = icon(res, TX).apply {
    scaleType = ImageView.ScaleType.CENTER; background = if (bg != 0) rounded(bg, 24) else null
    contentDescription = desc; setOnClickListener { onClick() }
}

fun Activity.vbox(vararg views: View) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; views.forEach(::addView) }

fun Activity.hbox(vararg views: View) = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; views.forEach(::addView) }

fun Activity.divided(l: LinearLayout, color: Int = LINE) = l.apply {
    dividerDrawable = GradientDrawable().apply { setColor(color); setSize(dp(1), dp(1)) }
    showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
}

fun View.sized(w: Int, h: Int, end: Int = 0) = apply { layoutParams = LinearLayout.LayoutParams(w, h).apply { marginEnd = end } }

fun View.grow(end: Int = 0) = apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = end } }

fun Activity.browse(url: String) = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

fun Activity.storeIcon() = ImageView(this).apply { setImageResource(R.drawable.ic_launcher) }

// Installed app's real icon, else an accent tile with the first letter
fun Activity.iconView(app: App, size: Int): View {
    if (app.isSelf) return storeIcon()
    val icon = app.pkg?.takeIf { app.installed != null }?.let { runCatching { packageManager.getApplicationIcon(it) }.getOrNull() }
    return if (icon != null) ImageView(this).apply { setImageDrawable(icon) }
    else tv(app.repo.take(1).uppercase(), size / 2.4f, ON_AC, true).apply { gravity = Gravity.CENTER; background = rounded(AC, size * 27 / 100) }
}

// Install (outlined) / Update (filled) / Open (tonal) / download percent, same everywhere
fun Activity.actionButton(app: App, big: Boolean = false) = tv(Store.label(app), if (big) 15f else 13f, bold = true).apply {
    val busy = app.repo in Store.progress
    val r = if (big) 25 else 20
    gravity = Gravity.CENTER; minHeight = dp(if (big) 50 else 40); setPadding(dp(18), 0, dp(18), 0)
    background = when {
        busy -> rounded(S3, r)
        app.installed == null -> rounded(0, r, AC).also { setTextColor(AC_TX) }
        app.hasUpdate -> rounded(AC, r).also { setTextColor(ON_AC) }
        else -> rounded(S3, r)
    }
    isEnabled = !busy
    setOnClickListener { Store.act(this@actionButton, app) }
}

fun Activity.progressBar(app: App) = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
    progress = Store.progress[app.repo] ?: 0; progressTintList = csl(AC); progressBackgroundTintList = csl(0xff2a2a32.toInt())
}

fun Activity.sizeOf(app: App): String = Formatter.formatShortFileSize(this, app.size)

fun Activity.metaLine(app: App) = when {
    app.repo in Store.progress -> tv("Téléchargement…", 12f, AC_TX, true)
    app.hasUpdate -> tv("${app.installed} → ${app.tag}", 12f, AC_TX, true)
    app.installed != null -> tv("Installée · ${app.installed}", 12f, MU2)
    else -> tv("Non installée · ${sizeOf(app)}", 12f, MU2)
}

fun Activity.openDetail(app: App) = startActivity(Intent(this, DetailActivity::class.java).putExtra("repo", app.repo))

class MainActivity : Activity() {
    private var tab = 0
    private var query = ""
    private var cat: String? = null // null = every category
    private var loading = false
    private var error: String? = null
    private val expanded = mutableSetOf<String>()
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var nav: LinearLayout

    private val self get() = Store.apps.firstOrNull { it.isSelf }
    private fun others() = Store.apps.filter { !it.isSelf }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 0)
        UpdateJob.schedule(this)
        @Suppress("DEPRECATION") window.statusBarColor = BG
        @Suppress("DEPRECATION") window.navigationBarColor = NAV

        content = vbox()
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(content) }
        nav = LinearLayout(this).apply { setBackgroundColor(NAV) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(BG); fitsSystemWindows = true
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(View(this@MainActivity).apply { setBackgroundColor(LINE) }, -1, dp(1))
            addView(nav, -1, dp(76))
        })
    }

    override fun onResume() {
        super.onResume()
        Store.onChange = ::render
        // Back from an app or the system installer: versions may have changed
        Store.resolveInstalled(this, Store.apps)
        render()
        // Back after a while (the activity often survives in the background): check again without being asked
        if (Store.apps.isEmpty() || System.currentTimeMillis() - Store.settings(this).getLong("checked", 0) > 5 * 60_000) refresh()
    }

    override fun onPause() {
        super.onPause()
        Store.onChange = null
    }

    @Deprecated("Activity API")
    override fun onBackPressed() {
        if (tab != 0) { tab = 0; render() } else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun refresh(force: Boolean = false) {
        if (loading) return
        loading = true; error = null; render()
        thread {
            val result = runCatching { Store.fetch(this, force) }
            runOnUiThread {
                loading = false
                result.onSuccess { Store.apps = it }.onFailure { error = "GitHub injoignable : ${it.message}" }
                render()
            }
        }
    }

    private fun render() {
        val y = scroll.scrollY
        content.removeAllViews()
        when (tab) { 0 -> home(); 1 -> updates(); 2 -> installed(); else -> settings() }
        scroll.post { scroll.scrollTo(0, y) }
        renderNav()
    }

    private fun add(v: View, l: Int = 20, t: Int = 0, r: Int = 20, b: Int = 0) =
        content.addView(v, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) })

    private fun h1(title: String, sub: String, extra: View? = null) {
        val head = hbox(vbox(tv(title, 26f, bold = true), tv(sub, 13f, MU).apply { setPadding(0, dp(4), 0, 0) }).grow())
        extra?.let(head::addView)
        add(head, t = 22, b = 4)
    }

    private fun h2(title: String, extra: View? = null, t: Int = 20) {
        val head = hbox(tv(title, 18f, bold = true).grow())
        extra?.let(head::addView)
        add(head, t = t, b = 6)
    }

    // Hard refresh: skips GitHub's 60 s cache; spins while a check runs
    private fun reloadButton() = iconButton(R.drawable.ic_refresh, "Vérifier maintenant") { refresh(force = true) }.apply {
        if (loading) startAnimation(RotateAnimation(0f, 360f, Animation.RELATIVE_TO_SELF, .5f, Animation.RELATIVE_TO_SELF, .5f)
            .apply { duration = 800; repeatCount = Animation.INFINITE; interpolator = LinearInterpolator() })
    }

    private fun empty(s: String) = tv(s, 14f, MU).apply { gravity = Gravity.CENTER; setPadding(0, dp(40), 0, dp(40)) }

    // Loading / error line, while there is nothing else to show
    private fun status() {
        if (loading && Store.apps.isEmpty()) add(empty("Chargement…"))
        else if (error != null) add(tv(error!!, 13f, AC_TX).apply { setPadding(0, dp(12), 0, dp(12)) })
    }

    private fun row(app: App) = hbox(
        iconView(app, 60).sized(dp(60), dp(60), dp(14)),
        vbox(
            tv(app.repo, 15f, bold = true),
            tv(app.description, 13f, MU).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; visibility = if (app.description.isBlank()) View.GONE else View.VISIBLE },
            metaLine(app).apply { setPadding(0, dp(4), 0, 0) },
        ).grow(dp(10)),
        actionButton(app),
    ).apply { setPadding(dp(8), dp(12), dp(8), dp(12)); setOnClickListener { openDetail(app) } }

    // ---- Découvrir ----

    private fun home() {
        add(hbox(
            storeIcon().sized(dp(38), dp(38), dp(12)),
            tv("iurikaFlows", 22f, bold = true).grow(),
            reloadButton().sized(dp(44), dp(44), dp(8)),
            iconButton(R.drawable.ic_github, "Profil GitHub") { browse(PROFILE) }.sized(dp(44), dp(44)),
        ), t = 20, b = 12)
        val list = vbox()
        add(EditText(this).apply {
            setText(query); hint = "Rechercher dans mes apps"; setHintTextColor(MU2); setTextColor(TX); textSize = 15f
            setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT
            background = rounded(S2, 26); minHeight = dp(52); setPadding(dp(18), 0, dp(18), 0)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablePadding = dp(12); compoundDrawableTintList = csl(MU)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(e: Editable) { query = e.toString(); fillList(list) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }, b = 16)
        val cats = (CATEGORIES.map { it.first } + OTHER).filter { c -> others().any { it.category == c } }
        if (cats.size > 1) {
            val chips = hbox()
            (listOf<String?>(null) + cats).forEach { c ->
                val on = c == cat
                chips.addView(tv(c ?: "Toutes", 13f, if (on) AC_TX else TX, on).apply {
                    gravity = Gravity.CENTER; minHeight = dp(36); setPadding(dp(16), 0, dp(16), 0)
                    background = if (on) rounded(AC_CHIP, 18) else rounded(S2, 18)
                    setOnClickListener { cat = c; render() }
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            }
            add(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, b = 16)
        }
        self?.takeIf { it.hasUpdate }?.let { add(banner(it), b = 18) }
        status()
        val updN = others().count { it.hasUpdate }
        h2("Mes apps", tv("${others().size} apps · " + if (updN > 0) "$updN mise${if (updN > 1) "s" else ""} à jour" else "à jour", 13f, MU), t = 0)
        add(list, l = 12, r = 12, b = 24)
        fillList(list)
    }

    private fun fillList(list: LinearLayout) {
        list.removeAllViews()
        val q = query.trim().lowercase()
        val apps = others().filter { (cat == null || it.category == cat) && (q.isEmpty() || "${it.repo} ${it.description} ${it.category}".lowercase().contains(q)) }
        if (apps.isEmpty() && Store.apps.isNotEmpty()) list.addView(empty("Aucune app ne correspond."))
        // grouped by category, in the order of CATEGORIES; a header only when several show
        val groups = apps.groupBy { it.category }.toList().sortedBy { (c, _) -> CATEGORIES.indexOfFirst { it.first == c }.let { if (it < 0) 99 else it } }
        for ((c, group) in groups) {
            if (groups.size > 1) list.addView(tv(c.uppercase(), 12f, AC_TX, true).apply { letterSpacing = .08f; setPadding(dp(8), dp(14), 0, dp(2)) })
            group.sortedByDescending { it.hasUpdate }.forEach { list.addView(row(it)) }
        }
    }

    private fun banner(app: App) = hbox(
        tv("↓", 18f, AC_TX, true).apply { gravity = Gravity.CENTER; background = rounded(AC_CHIP, 18) }.sized(dp(36), dp(36), dp(12)),
        vbox(tv("iurikaFlows ${app.tag} est disponible", 14f, bold = true), tv("${app.installed} → ${app.tag}", 12f, 0xffd7b3c2.toInt())).grow(dp(10)),
        actionButton(app),
    ).apply { background = rounded(AC_SOFT, 18, AC_LINE); setPadding(dp(14), dp(12), dp(12), dp(12)) }

    // ---- Mises à jour ----

    private fun updates() {
        val checked = Store.settings(this).getLong("checked", 0)
        val sub = when {
            loading -> "Vérification…"
            error != null -> error!!
            checked == 0L -> "GitHub Releases"
            System.currentTimeMillis() - checked < 60_000 -> "Vérifié à l'instant · GitHub Releases"
            else -> "Vérifié ${DateUtils.getRelativeTimeSpanString(checked)} · GitHub Releases"
        }
        h1("Mises à jour", sub, reloadButton().sized(dp(48), dp(48)))

        self?.takeIf { it.hasUpdate || it.repo in Store.progress }?.let { add(storeCard(it), t = 18, b = 8) }

        val pend = others().filter { it.hasUpdate || it.repo in Store.progress }
        val ok = others().filter { it.installed != null && !it.hasUpdate && it.repo !in Store.progress }
        val idle = pend.filter { it.repo !in Store.progress }
        val all = if (idle.size > 1) tv("Tout mettre à jour", 13f, bold = true).apply {
            gravity = Gravity.CENTER; minHeight = dp(40); setPadding(dp(18), 0, dp(18), 0); background = rounded(S3, 20)
            setOnClickListener { idle.forEach { Store.act(this@MainActivity, it) } }
        } else null
        h2("En attente · ${pend.size}", all)
        if (pend.isEmpty()) add(empty(if (Store.apps.isEmpty()) "Rien à afficher pour l'instant." else "Tout est à jour."))
        pend.forEach { add(pendingCard(it), t = 8, b = 4) }

        h2("À jour · ${ok.size}", t = 22)
        add(divided(vbox(*ok.map { app ->
            hbox(
                iconView(app, 48).sized(dp(48), dp(48), dp(14)),
                vbox(tv(app.repo, 15f, bold = true), tv("${app.installed} · ${sizeOf(app)}", 12f, MU)).grow(),
                icon(R.drawable.ic_check, OK).apply { contentDescription = "À jour" },
            ).apply { setPadding(0, dp(12), 0, dp(12)); setOnClickListener { openDetail(app) } }
        }.toTypedArray()), 0xff1e1e25.toInt()), b = 24)
    }

    private fun pendingCard(app: App): View {
        val notes = tv(app.latest.notes.ifBlank { "Pas de notes" }, 13f, 0xffc9c9d2.toInt()).apply {
            setLineSpacing(0f, 1.4f); setPadding(0, dp(8), 0, 0); visibility = if (app.repo in expanded) View.VISIBLE else View.GONE
        }
        val toggle = tv("Nouveautés de la ${app.tag}", 13f, AC_TX, true).apply {
            setPadding(0, dp(12), 0, 0)
            setOnClickListener {
                if (!expanded.add(app.repo)) expanded.remove(app.repo)
                notes.visibility = if (app.repo in expanded) View.VISIBLE else View.GONE
            }
        }
        return vbox(
            hbox(
                iconView(app, 56).sized(dp(56), dp(56), dp(14)),
                vbox(tv(app.repo, 15f, bold = true), tv("${app.installed?.let { "$it → " } ?: "Nouvelle · "}${app.tag} · ${sizeOf(app)}", 12f, MU))
                    .grow(dp(10)).apply { setOnClickListener { openDetail(app) } },
                actionButton(app),
            ),
            *listOfNotNull(if (app.repo in Store.progress) progressBar(app).apply { setPadding(0, dp(10), 0, 0) } else null).toTypedArray(),
            toggle, notes,
        ).apply { background = rounded(S1, 20); setPadding(dp(16), dp(16), dp(16), dp(16)) }
    }

    private fun storeCard(app: App) = vbox(
        hbox(
            storeIcon().sized(dp(52), dp(52), dp(14)),
            vbox(
                tv("LE STORE LUI-MÊME", 11f, AC_TX, true).apply { letterSpacing = .08f },
                tv("iurikaFlows", 16f, bold = true),
                tv("${app.installed} → ${app.tag} · ${sizeOf(app)}", 13f, 0xffd7b3c2.toInt()),
            ).grow(),
        ),
        actionButton(app).apply {
            if (isEnabled) text = "Installer et redémarrer"
            layoutParams = LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(16) }
        },
    ).apply { background = rounded(AC_SOFT, 22, AC_LINE); setPadding(dp(18), dp(18), dp(18), dp(18)) }

    // ---- Installées ----

    private fun installed() {
        val l = others().filter { it.installed != null }
        h1("Installées", "${l.size} apps · ${Formatter.formatShortFileSize(this, l.sumOf { it.size })}")
        status()
        add(vbox(*l.map(::row).toTypedArray()), l = 12, t = 12, r = 12, b = 24)
    }

    // ---- Réglages ----

    private fun settings() {
        val s = Store.settings(this)
        fun section(t: String) = add(tv(t, 12f, AC_TX, true).apply { letterSpacing = .08f }, t = 24, b = 8)
        fun card(vararg rows: View, b: Int = 0) =
            add(divided(vbox(*rows)).apply { background = rounded(S1, 20); setPadding(dp(16), 0, dp(16), 0) }, b = b)
        fun text(title: String, desc: String) = vbox(tv(title, 15f, bold = true), tv(desc, 12f, MU).apply { setPadding(0, dp(3), 0, 0) })
        fun switchRow(key: String, title: String, desc: String, def: Boolean = true, then: () -> Unit = {}): View {
            val sw = Switch(this).apply {
                isChecked = s.getBoolean(key, def)
                val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                thumbTintList = ColorStateList(states, intArrayOf(TX, 0xff9c9ca8.toInt()))
                trackTintList = ColorStateList(states, intArrayOf(AC, 0xff55555f.toInt()))
                setOnCheckedChangeListener { _, on -> s.edit().putBoolean(key, on).apply(); then() }
            }
            return hbox(text(title, desc).grow(dp(12)), sw).apply { setPadding(0, dp(16), 0, dp(16)); setOnClickListener { sw.toggle() } }
        }

        add(tv("Réglages", 26f, bold = true), t = 22, b = 4)
        section("MISES À JOUR")
        val freq = s.getInt("freq", 6)
        val seg = hbox(*listOf(1, 6, 24).map { h ->
            tv("$h h", 13f, if (h == freq) 0xff111114.toInt() else 0xffc9c9d2.toInt(), true).apply {
                gravity = Gravity.CENTER; minHeight = dp(34); setPadding(dp(12), 0, dp(12), 0)
                background = if (h == freq) rounded(TX, 16) else null
                setOnClickListener { s.edit().putInt("freq", h).apply(); UpdateJob.schedule(this@MainActivity, true); render() }
            }
        }.toTypedArray()).apply { background = rounded(S3, 18); setPadding(dp(3), dp(3), dp(3), dp(3)) }
        card(
            switchRow("autoStore", "Auto-update du store", "iurikaFlows se met à jour seul"),
            switchRow("autoApps", "Auto-update des apps", others().take(3).joinToString { it.repo }.ifEmpty { "Les apps installées ici" } + "…"),
            switchRow("wifi", "Wi-Fi uniquement", "Pas de téléchargement en données mobiles") { UpdateJob.schedule(this, true) },
            switchRow("pre", "Inclure les pré-releases", "Versions beta / rc de GitHub", false) { refresh(force = true) },
            hbox(text("Vérifier toutes les", "En arrière-plan").grow(dp(12)), seg).apply { setPadding(0, dp(16), 0, dp(16)) },
        )

        section("SOURCE")
        card(
            hbox(text("Compte GitHub", "Repos avec un APK en release").grow(dp(12)),
                tv(OWNER, 14f, AC_TX, true).apply { setOnClickListener { browse(PROFILE) } }).apply { setPadding(0, dp(16), 0, dp(16)) },
            vbox(
                text("Token GitHub (optionnel)", "Évite la limite de 60 requêtes par heure de l'API"),
                EditText(this).apply {
                    setText(s.getString("token", "")); hint = "ghp_…"; setHintTextColor(MU2); setTextColor(TX); textSize = 14f
                    setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    background = rounded(BG, 14, 0xff34343d.toInt()); setPadding(dp(14), 0, dp(14), 0)
                    layoutParams = LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(10) }
                    addTextChangedListener(object : TextWatcher {
                        override fun afterTextChanged(e: Editable) { s.edit().putString("token", e.toString().trim()).apply() }
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    })
                },
            ).apply { setPadding(0, dp(16), 0, dp(16)) },
        )

        section("À PROPOS")
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        card(hbox(tv("Version du store", 14f, MU).grow(), tv("$version · stable", 14f, bold = true)).apply { setPadding(0, dp(16), 0, dp(16)) }, b = 28)
    }

    // ---- Barre du bas ----

    private fun renderNav() {
        nav.removeAllViews()
        val updates = Store.apps.count { it.hasUpdate }
        listOf(Triple("Découvrir", R.drawable.ic_star, 0), Triple("Mises à jour", R.drawable.ic_refresh, 1),
            Triple("Installées", R.drawable.ic_grid, 2), Triple("Réglages", R.drawable.ic_gear, 3)).forEach { (label, res, i) ->
            val on = tab == i
            val pill = FrameLayout(this).apply {
                background = if (on) rounded(AC_CHIP, 16) else null
                addView(icon(res, if (on) AC_TX else MU), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
                if (i == 1 && updates > 0) addView(tv("$updates", 11f, ON_AC, true).apply {
                    gravity = Gravity.CENTER; minWidth = dp(18); setPadding(dp(5), 0, dp(5), 0); background = rounded(AC, 9)
                }, FrameLayout.LayoutParams(-2, dp(18), Gravity.END or Gravity.TOP).apply { topMargin = dp(1); marginEnd = dp(8) })
            }
            nav.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                addView(pill, LinearLayout.LayoutParams(dp(60), dp(32)))
                addView(tv(label, 12f, if (on) TX else MU, on).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) })
                setOnClickListener { tab = i; scroll.scrollTo(0, 0); render() }
            }, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }
}
