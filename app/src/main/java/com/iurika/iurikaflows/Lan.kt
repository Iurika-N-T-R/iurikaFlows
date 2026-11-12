package com.iurika.iurikaflows

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

// LAN sync of Réglages → GitHub token + Repos suivis between my devices, like OnlyFlows.
// Pairing: one device shows a 6-digit code, the other types it and finds it by sweeping the Wi-Fi /24;
// both then share a random key that every later exchange must carry. Last edit wins, for the (token, follow) pair.
// ponytail: plain TCP, one JSON line each way; the key crosses the LAN once, in clear, at pairing. Add TLS/PAKE if that matters.
object Lan {
    private const val PORT = 47893
    private const val CODE_TTL = 5 * 60_000L
    private var started = false
    private val syncing = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    @Volatile var code: String? = null; private set
    private var codeUntil = 0L
    private var misses = 0

    // Main thread: remote settings landed or a device paired with this one
    var onApplied: (() -> Unit)? = null

    private fun s(ctx: Context) = Store.settings(ctx)
    fun key(ctx: Context): String? = s(ctx).getString("lanKey", null)
    fun lastSeen(ctx: Context) = s(ctx).getLong("lanSeen", 0)
    fun unpair(ctx: Context) = s(ctx).edit().remove("lanKey").remove("lanSeen").apply()

    // Called on every local change of token / follow, so it wins over older copies
    fun edited(ctx: Context) = s(ctx).edit().putLong("syncAt", System.currentTimeMillis()).apply()

    private fun state(ctx: Context) = s(ctx).let {
        JSONObject().put("token", it.getString("token", "")).put("follow", it.getString("follow", "")).put("at", it.getLong("syncAt", 0))
    }

    private fun apply(ctx: Context, o: JSONObject) {
        if (o.optLong("at") <= s(ctx).getLong("syncAt", 0)) return
        s(ctx).edit().putString("token", o.optString("token")).putString("follow", o.optString("follow")).putLong("syncAt", o.getLong("at")).apply()
        main.post { onApplied?.invoke() }
    }

    fun newCode(): String = synchronized(this) {
        misses = 0; codeUntil = System.currentTimeMillis() + CODE_TTL
        (100000 + SecureRandom().nextInt(900000)).toString().also { code = it }
    }

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        thread(isDaemon = true, name = "lan") {
            val server = runCatching { ServerSocket(PORT) }.getOrNull() ?: return@thread
            while (true) runCatching { server.accept().use { serve(app, it) } }
        }
    }

    private fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    private fun serve(ctx: Context, sock: Socket) {
        sock.soTimeout = 3000
        val req = JSONObject(sock.getInputStream().bufferedReader().readLine() ?: return)
        val key = key(ctx)
        val out = when {
            req.has("pair") -> synchronized(this) {
                val c = code
                if (c != null && System.currentTimeMillis() < codeUntil && same(req.getString("pair"), c)) {
                    code = null
                    val k = key ?: ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
                    s(ctx).edit().putString("lanKey", k).putLong("lanSeen", System.currentTimeMillis()).apply()
                    main.post { onApplied?.invoke() }
                    JSONObject().put("key", k).put("state", state(ctx))
                } else {
                    // 5 wrong guesses burn the code: 6 digits can't be brute-forced in 5 tries
                    if (c != null && ++misses >= 5) code = null
                    JSONObject()
                }
            }
            key != null && same(req.optString("key"), key) -> {
                apply(ctx, req.getJSONObject("state"))
                s(ctx).edit().putLong("lanSeen", System.currentTimeMillis()).apply()
                JSONObject().put("state", state(ctx))
            }
            else -> JSONObject()
        }
        sock.getOutputStream().apply { write("$out\n".toByteArray()); flush() }
    }

    private fun ask(ip: String, req: JSONObject): JSONObject? = runCatching {
        Socket().use { sock ->
            sock.connect(InetSocketAddress(ip, PORT), 400); sock.soTimeout = 3000
            sock.getOutputStream().apply { write("$req\n".toByteArray()); flush() }
            JSONObject(sock.getInputStream().bufferedReader().readLine())
        }
    }.getOrNull()

    // Every other address of my private /24 networks
    // ponytail: /24 sweep, no mDNS; enough for a home Wi-Fi, add NsdManager if a bigger network ever needs it
    private fun sweep(req: JSONObject): List<JSONObject> {
        val mine = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.map { it.hostAddress!! }
        val ips = mine.flatMap { me -> (1..254).map { "${me.substringBeforeLast('.')}.$it" } }.distinct() - mine.toSet()
        val pool = Executors.newFixedThreadPool(64)
        try {
            return pool.invokeAll(ips.map { ip -> Callable { ask(ip, req) } }).mapNotNull { it.get() }
        } finally { pool.shutdown() }
    }

    // Background thread. Joins whichever device shows [code]; its token/follow merge with mine. False = nobody accepted.
    fun pair(ctx: Context, code: String): Boolean {
        val r = sweep(JSONObject().put("pair", code)).firstOrNull { it.has("key") } ?: return false
        val theirs = r.getJSONObject("state")
        val s = s(ctx)
        val follow = (s.getString("follow", "")!!.lines() + theirs.optString("follow").lines()).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        s.edit().putString("lanKey", r.getString("key"))
            .putString("token", s.getString("token", "")!!.ifBlank { theirs.optString("token") })
            .putString("follow", follow.joinToString("\n")).apply()
        edited(ctx)
        main.post { onApplied?.invoke() }
        sync(ctx)
        return true
    }

    // Background thread; no-op unless paired. Sends mine to every paired device, keeps whichever is newest.
    fun sync(ctx: Context): Int {
        val key = key(ctx) ?: return 0
        if (!syncing.compareAndSet(false, true)) return 0
        try {
            val peers = sweep(JSONObject().put("key", key).put("state", state(ctx))).mapNotNull { it.optJSONObject("state") }
            peers.forEach { apply(ctx, it) }
            if (peers.isNotEmpty()) s(ctx).edit().putLong("lanSeen", System.currentTimeMillis()).apply()
            return peers.size
        } finally { syncing.set(false) }
    }
}
