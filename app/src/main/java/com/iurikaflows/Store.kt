package com.iurikaflows

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer

// Every public, non-fork repo of this account with a GitHub release shows up in the store
const val OWNER = "Iurika-N-T-R"
const val PROFILE = "https://github.com/$OWNER"

class App(val repo: String, val description: String, val tag: String, val notes: String, val pageUrl: String, val apkUrl: String?) {
    var pkg: String? = null
    var installed: String? = null
    val hasUpdate get() = installed?.let { isNewer(tag, it) } == true
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

object Store {
    // ponytail: unauthenticated API = 60 requests/hour, one per repo per refresh; add a token if the repo count grows
    private fun get(url: String) = (URL(url).openConnection() as HttpURLConnection).run {
        setRequestProperty("Accept", "application/vnd.github+json")
        inputStream.bufferedReader().use { it.readText() }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("packages", Context.MODE_PRIVATE)

    fun remember(ctx: Context, repo: String, pkg: String) = prefs(ctx).edit().putString(repo, pkg).apply()

    fun fetch(ctx: Context): List<App> {
        val repos = JSONArray(get("https://api.github.com/users/$OWNER/repos?per_page=100&sort=pushed"))
        val apps = (0 until repos.length()).map(repos::getJSONObject)
            .filter { !it.getBoolean("fork") && !it.getBoolean("archived") }
            .mapNotNull { r ->
                val name = r.getString("name")
                val rel = try {
                    JSONObject(get("https://api.github.com/repos/$OWNER/$name/releases/latest"))
                } catch (e: FileNotFoundException) {
                    return@mapNotNull null // no release yet
                }
                val assets = rel.getJSONArray("assets")
                val apk = (0 until assets.length()).map { assets.getJSONObject(it).getString("browser_download_url") }.firstOrNull { it.endsWith(".apk") }
                App(name, r.optString("description").takeUnless { it == "null" }.orEmpty(), rel.getString("tag_name"),
                    rel.optString("body"), rel.getString("html_url"), apk)
            }
        resolveInstalled(ctx, apps)
        return apps
    }

    // Package names aren't in the release, so: the one we installed it as, else an installed app whose label matches the repo name
    private fun resolveInstalled(ctx: Context, apps: List<App>) {
        val pm = ctx.packageManager
        fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("[^A-Za-z0-9]"), "").lowercase()
        val byLabel by lazy {
            pm.getInstalledApplications(0).filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                .associate { norm(it.loadLabel(pm).toString()) to it.packageName }
        }
        for (app in apps) {
            app.pkg = prefs(ctx).getString(app.repo, null) ?: byLabel[norm(app.repo)]
            app.installed = app.pkg?.let {
                try { pm.getPackageInfo(it, 0).versionName ?: "?" } catch (e: PackageManager.NameNotFoundException) { null }
            }
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
            nm.createNotificationChannel(NotificationChannel("updates", "Updates", NotificationManager.IMPORTANCE_DEFAULT))
            Notification.Builder(ctx, "updates")
        } else @Suppress("DEPRECATION") Notification.Builder(ctx)
        val pending = PendingIntent.getActivity(ctx, id, tap, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(id, b.setSmallIcon(R.drawable.ic_launcher).setContentTitle(title).setContentText(text)
            .setContentIntent(pending).setAutoCancel(true).build())
    }
}
