package com.iurika.iurikaflows

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.net.http.HttpResponseCache
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread

// Every public, non-fork repo of this account whose latest release has an APK shows up in the store
const val OWNER = "Iurika-N-T-R"
const val PROFILE = "https://github.com/$OWNER"

// An app's category comes from its repo's GitHub topics: the first category with a matching topic wins, else "Autres".
// ponytail: hardcoded keywords; a "category-x" topic convention if this list keeps growing
val CATEGORIES = listOf(
    "Art" to setOf("art", "digital-art", "artists", "reference-board", "drawing", "painting"),
    "Médias" to setOf("media", "photo", "photo-gallery", "gallery", "video", "video-player", "music", "audio"),
    "Productivité" to setOf("productivity", "task-manager", "todo-app", "notes", "calendar"),
    "Outils" to setOf("tool", "tools", "utility", "randomizer", "converter", "store"),
    "Jeux" to setOf("game", "games"),
)
const val OTHER = "Autres"

fun category(topics: Collection<String>) = CATEGORIES.firstOrNull { (_, keys) -> topics.any { it in keys } }?.first ?: OTHER

class Release(val tag: String, val date: String, val notes: String)

class App(val repo: String, val description: String, val category: String, val releases: List<Release>, val apkUrl: String, val size: Long) {
    val latest get() = releases[0]
    val tag get() = latest.tag
    var pkg: String? = null
    var installed: String? = null
    val hasUpdate get() = installed?.let { isNewer(tag, it) } == true
    // The store lists its own repo too; it's shown apart, as "the store itself"
    val isSelf get() = repo.equals("iurikaflows", true)
}

// Numeric compare of "v1.2.10" against "1.2.9"; anything non-numeric is ignored
fun isNewer(tag: String, installed: String): Boolean {
    fun nums(v: String) = Regex("\\d+").findAll(v).map { it.value.toLong() }.toList()
    val a = nums(tag)
    val b = nums(installed)
    for (i in 0 until maxOf(a.size, b.size)) {
        val d = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
        if (d != 0) return d > 0
    }
    return false
}

// ponytail: just enough markdown stripping for release notes; a real renderer if notes get fancy
fun plain(md: String) = md.replace("\r", "").lines().joinToString("\n") {
    it.replace(Regex("^#+\\s*"), "").replace(Regex("^\\s*[-*]\\s+"), "• ")
}.replace("**", "").replace("`", "").trim()

private fun release(r: JSONObject) = Release(r.getString("tag_name"), r.optString("published_at").take(10), plain(r.optString("body").takeUnless { it == "null" }.orEmpty()))

object Store {
    var apps = listOf<App>()
    // repo -> download percent, while an install is running
    val progress: MutableMap<String, Int> = ConcurrentHashMap()
    // Set by the visible screen, called on the main thread whenever apps or progress change
    var onChange: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    fun changed() {
        main.post { onChange?.invoke() }
    }

    // Unauthenticated API = 60 requests/hour. The HTTP cache revalidates with ETags, and GitHub doesn't count 304s,
    // so only repos that actually changed cost a request. GitHub also says max-age=60: [force] skips that minute.
    private fun get(ctx: Context, url: String, force: Boolean): String {
        if (HttpResponseCache.getInstalled() == null) HttpResponseCache.install(File(ctx.cacheDir, "http"), 5L shl 20)
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        if (force) c.setRequestProperty("Cache-Control", "max-age=0") // still revalidates with the ETag, so 304s stay free
        settings(ctx).getString("token", "")!!.trim().takeIf { it.isNotEmpty() }?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        if (c.responseCode == 403 || c.responseCode == 429) {
            val reset = c.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()?.let { java.text.DateFormat.getTimeInstance().format(it * 1000) }
            throw java.io.IOException("limite de l'API GitHub atteinte, réessaie après ${reset ?: "un moment"}")
        }
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("packages", Context.MODE_PRIVATE)

    fun remember(ctx: Context, repo: String, pkg: String) = prefs(ctx).edit().putString(repo, pkg).apply()

    // autoStore, autoApps, wifi, pre, freq (hours), token, auto_<repo>, checked (last successful fetch)
    fun settings(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun autoUpdates(ctx: Context, app: App) = settings(ctx).let {
        it.getBoolean(if (app.isSelf) "autoStore" else "autoApps", true) && it.getBoolean("auto_${app.repo}", true)
    }

    /** Hours between automatic checks, the same setting for the background job and for opening the app. */
    fun freqMs(ctx: Context) = settings(ctx).getInt("freq", 6) * 3600_000L

    fun due(ctx: Context) = System.currentTimeMillis() - settings(ctx).getLong("checked", 0) >= freqMs(ctx)

    // The last list fetched, so a fresh start shows it at once instead of an empty "Chargement…"
    private fun saved(ctx: Context) = File(ctx.filesDir, "apps.json")

    fun save(ctx: Context, apps: List<App>) = runCatching {
        val json = JSONArray(apps.map { a ->
            JSONObject().put("repo", a.repo).put("description", a.description).put("category", a.category)
                .put("apkUrl", a.apkUrl).put("size", a.size)
                .put("releases", JSONArray(a.releases.map { JSONObject().put("tag", it.tag).put("date", it.date).put("notes", it.notes) }))
        })
        val tmp = File(ctx.filesDir, "apps.json.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(saved(ctx))
    }

    fun load(ctx: Context): List<App> = runCatching {
        val json = JSONArray(saved(ctx).readText())
        (0 until json.length()).map(json::getJSONObject).map { o ->
            val rels = o.getJSONArray("releases").let { r -> (0 until r.length()).map(r::getJSONObject).map { Release(it.getString("tag"), it.getString("date"), it.getString("notes")) } }
            App(o.getString("repo"), o.getString("description"), o.getString("category"), rels, o.getString("apkUrl"), o.getLong("size"))
        }.filter { it.releases.isNotEmpty() }.also { resolveInstalled(ctx, it) }
    }.getOrDefault(emptyList())

    fun fetch(ctx: Context, force: Boolean = false): List<App> {
        val pre = settings(ctx).getBoolean("pre", false)
        val repos = JSONArray(get(ctx, "https://api.github.com/users/$OWNER/repos?per_page=100&sort=pushed", force))
        // one releases request per repo, several at a time: a cold start waited for each in turn
        val pool = Executors.newFixedThreadPool(6)
        val apps = try { (0 until repos.length()).map(repos::getJSONObject)
            .filter { !it.getBoolean("fork") && !it.getBoolean("archived") }
            .map { r -> pool.submit(Callable { appOf(ctx, r, pre, force) }) }
            .mapNotNull { it.get() }
        } finally { pool.shutdown() }
        resolveInstalled(ctx, apps)
        save(ctx, apps)
        settings(ctx).edit().putLong("checked", System.currentTimeMillis()).apply()
        return apps
    }

    private fun appOf(ctx: Context, r: JSONObject, pre: Boolean, force: Boolean): App? {
        val name = r.getString("name")
        // One request gives the latest release and the changelog; pre-releases are skipped
        val list = JSONArray(get(ctx, "https://api.github.com/repos/$OWNER/$name/releases?per_page=30", force))
        val rels = (0 until list.length()).map(list::getJSONObject).filter { pre || !it.optBoolean("prerelease") }
        val assets = rels.firstOrNull()?.getJSONArray("assets") ?: return null // no release yet
        val apk = (0 until assets.length()).map(assets::getJSONObject)
            .firstOrNull { it.getString("name").endsWith(".apk") } ?: return null
        val topics = r.optJSONArray("topics")?.let { t -> (0 until t.length()).map(t::getString) }.orEmpty()
        return App(name, r.optString("description").takeUnless { it == "null" }.orEmpty(), category(topics), rels.map(::release),
            apk.getString("browser_download_url"), apk.getLong("size"))
    }

    // Package names aren't in the release, so: the one we installed it as, else an installed app whose label matches the repo name
    fun resolveInstalled(ctx: Context, apps: List<App>) {
        val pm = ctx.packageManager
        fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("[^A-Za-z0-9]"), "").lowercase()
        val byLabel by lazy {
            pm.getInstalledApplications(0).filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                .associate { norm(it.loadLabel(pm).toString()) to it.packageName }
        }
        fun version(pkg: String) = try { pm.getPackageInfo(pkg, 0).versionName ?: "?" } catch (e: PackageManager.NameNotFoundException) { null }
        for (app in apps) {
            // the remembered package may be gone (uninstalled, or the app changed package): fall back to the label
            val saved = prefs(ctx).getString(app.repo, null)
            app.pkg = saved?.takeIf { version(it) != null } ?: byLabel[norm(app.repo)] ?: saved
            app.installed = app.pkg?.let(::version)
        }
    }

    fun label(app: App) = progress[app.repo]?.let { "$it %" } ?: when {
        app.hasUpdate -> "Mettre à jour"
        app.installed != null -> "Ouvrir"
        else -> "Installer"
    }

    // What the main button does, on the list and on the details screen
    fun act(activity: Activity, app: App) {
        if (app.repo in progress) return
        if (app.installed != null && !app.hasUpdate) {
            activity.packageManager.getLaunchIntentForPackage(app.pkg!!)?.let(activity::startActivity)
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, "Autorise iurikaFlows à installer des apps, puis reviens", Toast.LENGTH_LONG).show()
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            return
        }
        val ctx = activity.applicationContext
        progress[app.repo] = 0
        changed()
        thread {
            try {
                install(ctx, app) { p -> if (progress.put(app.repo, p) != p) changed() }
            } catch (e: Exception) {
                main.post { Toast.makeText(ctx, "${app.repo}: ${e.message}", Toast.LENGTH_LONG).show() }
                finished(ctx, app.repo)
            }
        }
    }

    // Install ended (any outcome): drop progress and re-read installed versions
    fun finished(ctx: Context, repo: String) {
        main.post {
            progress.remove(repo)
            resolveInstalled(ctx, apps)
            onChange?.invoke()
        }
    }

    // Streams the APK straight into an install session. Apps this store installed update silently on Android 12+.
    fun install(ctx: Context, app: App, progress: (Int) -> Unit = {}) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(params)
        try {
            pi.openSession(id).use { s ->
                val c = URL(app.apkUrl).openConnection() as HttpURLConnection
                c.useCaches = false
                val total = c.contentLengthLong
                c.inputStream.use { input ->
                    s.openWrite("apk", 0, total).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) progress((done * 100 / total).toInt())
                        }
                        s.fsync(out)
                    }
                }
                val intent = Intent(ctx, InstallReceiver::class.java).putExtra("repo", app.repo)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                s.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
            }
        } catch (e: Exception) {
            pi.abandonSession(id)
            throw e
        }
    }

    fun notify(ctx: Context, id: Int, title: String, text: String, tap: Intent) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val b = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("updates", "Mises à jour", NotificationManager.IMPORTANCE_DEFAULT))
            Notification.Builder(ctx, "updates")
        } else @Suppress("DEPRECATION") Notification.Builder(ctx)
        val pending = PendingIntent.getActivity(ctx, id, tap, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(id, b.setSmallIcon(R.drawable.ic_launcher).setContentTitle(title).setContentText(text)
            .setContentIntent(pending).setAutoCancel(true).build())
    }
}
