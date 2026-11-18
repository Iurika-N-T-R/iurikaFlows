package com.iurika.iurikaflows

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
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
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

// LAN sync of Réglages → GitHub token + Repos suivis between my devices, like OnlyFlows.
// Pairing: one device shows a 6-digit code, the other types it and finds it by sweeping the Wi-Fi /24;
// both then share a random key. Every later exchange is sealed with it (AES-256-GCM, as in OnlyFlows 0.87): the token never
// crosses the Wi-Fi in clear, and a request that doesn't open with the key is refused. Last edit wins, for the (token, follow) pair.
// The server only listens while paired or while a code is shown.
// ponytail: plain TCP, one JSON line each way; the key crosses the LAN once, in clear, at pairing. Add TLS/PAKE if that matters.
object Lan {
    private const val PORT = 47893
    private const val CODE_TTL = 5 * 60_000L
    private const val MAX_LINE = 64 * 1024
    private val random = SecureRandom()
    @Volatile private var server: ServerSocket? = null
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
    fun unpair(ctx: Context) {
        s(ctx).edit().remove("lanKey").remove("lanSeen").apply()
        if (code == null) stop()
    }

    // AES-256-GCM under the hex key: base64(iv + ciphertext + tag). open = null when sealed with another key or altered.
    private fun cipher(mode: Int, key: String, iv: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(key.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), "AES"), GCMParameterSpec(128, iv))
    }
    fun seal(key: String, o: JSONObject): String {
        val iv = ByteArray(12).also(random::nextBytes)
        return Base64.encodeToString(iv + cipher(Cipher.ENCRYPT_MODE, key, iv).doFinal(o.toString().toByteArray()), Base64.NO_WRAP)
    }
    fun open(key: String, box: String): JSONObject? = runCatching {
        val b = Base64.decode(box, Base64.NO_WRAP)
        JSONObject(String(cipher(Cipher.DECRYPT_MODE, key, b.copyOf(12)).doFinal(b, 12, b.size - 12)))
    }.getOrNull()

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

    fun newCode(ctx: Context): String = synchronized(this) {
        misses = 0; codeUntil = System.currentTimeMillis() + CODE_TTL
        start(ctx, force = true)
        val app = ctx.applicationContext
        main.postDelayed({ synchronized(this) { if (System.currentTimeMillis() >= codeUntil) { code = null; if (key(app) == null) stop() } } }, CODE_TTL)
        (100000 + random.nextInt(900000)).toString().also { code = it }
    }

    // Listens only while paired (or showing a code): nothing is open on the Wi-Fi otherwise
    @Synchronized fun start(ctx: Context, force: Boolean = false) {
        if (server != null || (!force && key(ctx) == null)) return
        val app = ctx.applicationContext
        val sock = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) } }.getOrNull() ?: return
        server = sock
        // one client at a time, 3 s each: a flood can't pile up threads
        thread(isDaemon = true, name = "lan") { while (!sock.isClosed) runCatching { sock.accept().use { serve(app, it) } } }
    }

    @Synchronized private fun stop() { runCatching { server?.close() }; server = null }

    // One line, capped: a peer can't make me buffer an endless line
    private fun readLine(sock: Socket): String? {
        val inp = sock.getInputStream()
        val buf = java.io.ByteArrayOutputStream()
        while (true) {
            val b = inp.read()
            if (b == -1 || b == '\n'.code) return if (b == -1 && buf.size() == 0) null else buf.toString("UTF-8")
            if (buf.size() >= MAX_LINE) return null
            buf.write(b)
        }
    }

    private fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    private fun serve(ctx: Context, sock: Socket) {
        sock.soTimeout = 3000
        val req = JSONObject(readLine(sock) ?: return)
        val key = key(ctx)
        val out = when {
            req.has("pair") -> synchronized(this) {
                val c = code
                if (c != null && System.currentTimeMillis() < codeUntil && same(req.getString("pair"), c)) {
                    code = null
                    val k = key ?: ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
                    s(ctx).edit().putString("lanKey", k).putLong("lanSeen", System.currentTimeMillis()).apply()
                    main.post { onApplied?.invoke() }
                    JSONObject().put("key", k).put("box", seal(k, state(ctx))) // the token, sealed even here
                } else {
                    // 5 wrong guesses burn the code: 6 digits can't be brute-forced in 5 tries
                    if (c != null && ++misses >= 5) { code = null; if (key == null) stop() }
                    JSONObject()
                }
            }
            else -> key?.let { k -> open(k, req.optString("box")) }?.let { theirs ->
                apply(ctx, theirs)
                s(ctx).edit().putLong("lanSeen", System.currentTimeMillis()).apply()
                JSONObject().put("box", seal(key, state(ctx)))
            } ?: JSONObject() // not sealed with my key: refused
        }
        sock.getOutputStream().apply { write("$out\n".toByteArray()); flush() }
    }

    private fun ask(ip: String, req: JSONObject): JSONObject? = runCatching {
        Socket().use { sock ->
            sock.connect(InetSocketAddress(ip, PORT), 400); sock.soTimeout = 3000
            sock.getOutputStream().apply { write("$req\n".toByteArray()); flush() }
            JSONObject(readLine(sock)!!)
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
        val theirs = open(r.getString("key"), r.optString("box")) ?: return false // a device on the old, unsealed protocol
        val s = s(ctx)
        val follow = (s.getString("follow", "")!!.lines() + theirs.optString("follow").lines()).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        s.edit().putString("lanKey", r.getString("key"))
            .putString("token", s.getString("token", "")!!.ifBlank { theirs.optString("token") })
            .putString("follow", follow.joinToString("\n")).apply()
        edited(ctx)
        start(ctx)
        main.post { onApplied?.invoke() }
        sync(ctx)
        return true
    }

    // Background thread; no-op unless paired. Sends mine to every paired device, keeps whichever is newest.
    fun sync(ctx: Context): Int {
        val key = key(ctx) ?: return 0
        if (!syncing.compareAndSet(false, true)) return 0
        try {
            val peers = sweep(JSONObject().put("box", seal(key, state(ctx)))).mapNotNull { open(key, it.optString("box")) }
            peers.forEach { apply(ctx, it) }
            if (peers.isNotEmpty()) s(ctx).edit().putLong("lanSeen", System.currentTimeMillis()).apply()
            return peers.size
        } finally { syncing.set(false) }
    }
}
