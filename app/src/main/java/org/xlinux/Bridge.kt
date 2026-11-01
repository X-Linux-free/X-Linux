package org.xlinux

import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/** Connects the touch UI (assets/index.html) to the real Parrot OS runtime. Exposed to JS as `XL`. */
class Bridge(private val a: Activity, private val rt: LinuxRuntime, private val web: WebView) {
    private val ok: Boolean get() = Integrity.okCached(a)
    private val home: File get() = File(rt.root, "root").apply { mkdirs() }
    private fun js(code: String) = a.runOnUiThread { web.evaluateJavascript(code, null) }
    private fun res(rel: String): File {
        val f = File(home, rel).canonicalFile
        if (f.path != home.canonicalPath && !f.path.startsWith(home.canonicalPath + "/")) throw SecurityException("path")
        return f
    }

    @JavascriptInterface fun st(): String {
        if (!ok) { rt.stop(); a.runOnUiThread { a.finishAndRemoveTask() }; return JSONObject().put("locked", true).toString() }
        val am = a.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val d = a.filesDir
        return JSONObject().put("installed", rt.installed()).put("proot", rt.proot())
            .put("memTotal", mi.totalMem).put("memAvail", mi.availMem)
            .put("storFree", d.usableSpace).put("storTotal", d.totalSpace).toString()
    }

    @JavascriptInterface fun install() {
        if (!ok) return
        Thread {
            try { rt.install { js("xlLog(${JSONObject.quote(it)})") }; js("xlDone(true)") }
            catch (e: Exception) { js("xlLog(${JSONObject.quote("ERROR: " + e.message)})"); js("xlDone(false)") }
        }.start()
    }
    @JavascriptInterface fun shell() { if (ok && rt.installed()) rt.startShell { js("xlOut(${JSONObject.quote(it)})") } }
    @JavascriptInterface fun send(s: String) { if (ok) rt.send(s) }
    @JavascriptInterface fun stop() = rt.interrupt()

    @JavascriptInterface fun bins(): String {
        val s = sortedSetOf<String>()
        for (d in listOf("usr/bin", "usr/local/bin", "usr/sbin")) File(rt.root, d).list()?.forEach { s.add(it) }
        return JSONArray(s.toList()).toString()
    }

    // ---- program name + real icon, read from the program's own .desktop file / icon theme ----
    private var deskStamp = -1L
    private var deskMap = HashMap<String, Pair<String, String>>()
    private val metaCache = HashMap<String, Pair<Long, String>>()
    private fun desk(): Map<String, Pair<String, String>> {
        val dir = File(rt.root, "usr/share/applications")
        if (dir.lastModified() == deskStamp) return deskMap
        val m = HashMap<String, Pair<String, String>>()
        dir.listFiles { f -> f.name.endsWith(".desktop") }?.forEach { f ->
            var name = ""; var exec = ""; var icon = ""; var inEntry = false
            for (l in runCatching { f.readLines() }.getOrDefault(emptyList())) {
                if (l.startsWith("[")) { inEntry = l == "[Desktop Entry]"; continue }
                if (!inEntry) continue
                when {
                    l.startsWith("Name=") && name.isEmpty() -> name = l.substring(5)
                    l.startsWith("Exec=") && exec.isEmpty() -> exec = l.substring(5)
                    l.startsWith("Icon=") && icon.isEmpty() -> icon = l.substring(5)
                }
            }
            val tok = exec.trim().split(Regex("\\s+")).firstOrNull { !it.contains("=") && it != "env" }?.trim('"') ?: ""
            val cmd = tok.substringAfterLast('/')
            if (cmd.isNotEmpty()) m.putIfAbsent(cmd, name to icon)
        }
        deskStamp = dir.lastModified(); deskMap = m
        return m
    }
    private fun findIcon(name: String): File? {
        if (name.isEmpty() || name.contains("..")) return null
        val share = File(rt.root, "usr/share")
        if (name.startsWith("/")) {
            val f = File(rt.root, name.removePrefix("/")).canonicalFile
            return f.takeIf { it.isFile && f.path.startsWith(rt.root.canonicalPath) && (it.name.endsWith(".png") || it.name.endsWith(".svg")) }
        }
        for (e in listOf("png", "svg")) File(share, "pixmaps/$name.$e").takeIf { it.isFile }?.let { return it }
        var best: File? = null; var score = -1
        File(share, "icons").walkTopDown().maxDepth(6).onEnter { !Files.isSymbolicLink(it.toPath()) }.forEach { f ->
            if (f.name == "$name.svg" && score < 1000) { best = f; score = 1000 }
            else if (f.name == "$name.png") {
                val px = Regex("(\\d+)x\\d+").find(f.path)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val sc = if (px <= 256) px else 256 - (px - 256) / 10
                if (sc > score) { best = f; score = sc }
            }
        }
        return best
    }
    @JavascriptInterface fun meta(cmd: String): String {
        if (!ok) return "{}"
        val now = System.currentTimeMillis()
        metaCache[cmd]?.let { if (now - it.first < 20000) return it.second }
        val r = try {
            val o = JSONObject()
            desk()[cmd]?.let { d ->
                if (d.first.isNotEmpty()) o.put("n", d.first)
                findIcon(d.second)?.let { f ->
                    if (f.length() < 300_000) o.put("i", "data:" + (if (f.name.endsWith(".svg")) "image/svg+xml" else "image/png") + ";base64," + Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
                }
            }
            o.toString()
        } catch (e: Exception) { "{}" }
        metaCache[cmd] = now to r
        return r
    }

    // ---- files (sandboxed to the Linux home folder) ----
    @JavascriptInterface fun ls(rel: String): String = try {
        val arr = JSONArray()
        res(rel).listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))?.take(500)
            ?.forEach { arr.put(JSONObject().put("n", it.name).put("d", it.isDirectory)) }
        arr.toString()
    } catch (e: Exception) { "[]" }
    @JavascriptInterface fun read(rel: String): String = try { res(rel).let { if (it.isFile && it.length() < 1_000_000) it.readText() else "" } } catch (e: Exception) { "" }
    @JavascriptInterface fun write(rel: String, text: String): Boolean = try { if (!ok) false else { res(rel).apply { parentFile?.mkdirs(); writeText(text) }; true } } catch (e: Exception) { false }
    @JavascriptInterface fun mkdir(rel: String): Boolean = try { ok && res(rel).mkdirs() } catch (e: Exception) { false }
    @JavascriptInterface fun rm(rel: String): Boolean = try { if (!ok) false else { LinuxRuntime.nuke(res(rel)); true } } catch (e: Exception) { false }

    // ---- settings backends ----
    @JavascriptInterface fun setShell(s: String) { rt.shellPref = s }
    @JavascriptInterface fun awake(on: Boolean) = a.runOnUiThread {
        if (on) a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else a.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    @JavascriptInterface fun getDns(): String = rt.getDns()
    @JavascriptInterface fun setDns(s: String): Boolean = ok && rt.setDns(s)
    @JavascriptInterface fun size() { Thread { val n = runCatching { LinuxRuntime.size(rt.root) }.getOrDefault(0L); js("xlSize($n)") }.start() }
    @JavascriptInterface fun clearCache(): Long {
        var n = 0L
        a.cacheDir.listFiles { f -> f.name.startsWith("layer") }?.forEach { n += f(it) }
        return n
    }
    private fun f(x: File): Long { val l = x.length(); return if (x.delete()) l else 0L }
    @JavascriptInterface fun copy(text: String) = a.runOnUiThread {
        (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("X Linux", text))
    }
    @JavascriptInterface fun repair(): Boolean = ok && rt.repair()
    @JavascriptInterface fun reset(): Boolean = try { if (!ok) false else { rt.reset(); true } } catch (e: Exception) { false }
    @JavascriptInterface fun device(): String = JSONObject().put("model", Build.MODEL).put("android", Build.VERSION.RELEASE)
        .put("sdk", Build.VERSION.SDK_INT).put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "?").toString()
    @JavascriptInterface fun credits(): String = if (ok) Integrity.text(a) else ""

    @JavascriptInterface fun dark(on: Boolean) = a.runOnUiThread {
        val c = Color.parseColor(if (on) "#17121f" else "#efe6fb")
        a.window.statusBarColor = c; a.window.navigationBarColor = c
        a.window.decorView.systemUiVisibility = if (on) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or 0x10
        web.setBackgroundColor(c)
    }
}
