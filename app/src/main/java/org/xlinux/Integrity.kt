package org.xlinux

import android.content.Context
import java.security.MessageDigest

/** Cornerstone guard: if assets/cornerstone.txt is changed in any way, the app refuses to run. */
object Integrity {
    private const val SHA = "a6a1391554823d452ef8206970b34955c33a100744ae03dbe15679660007152f"
    private var last = 0L
    private var res = true

    fun text(ctx: Context): String = ctx.assets.open("cornerstone.txt").use { String(it.readBytes(), Charsets.UTF_8) }

    fun ok(ctx: Context): Boolean = try {
        val b = ctx.assets.open("cornerstone.txt").use { it.readBytes() }
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) } == SHA
    } catch (e: Exception) { false }

    @Synchronized fun okCached(ctx: Context): Boolean {
        val n = System.currentTimeMillis()
        if (res && n - last > 3000) { res = ok(ctx); last = n }
        return res
    }
}
