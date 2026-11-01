package org.xlinux

import android.content.Context
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.*
import java.net.HttpURLConnection
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.net.URL
import org.json.JSONObject
import java.security.DigestInputStream
import java.security.MessageDigest

/** Real Parrot OS (official parrotsec/core image) userspace executed rootless through PRoot. No Termux, no root. */
class LinuxRuntime(private val ctx: Context) {
    private val image = "parrotsec/core"   // official Parrot OS Core image (Debian 13 based)
    private val tag = "latest"
    private val accIndex = "application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json"
    private val accMan = "application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json"
    val root = File(ctx.filesDir, "rootfs")
    private val lib = ctx.applicationInfo.nativeLibraryDir
    private var proc: Process? = null

    fun installed() = File(root, ".xl-ok").exists()
    fun proot() = File(lib, "libproot.so").exists()

    private fun open(url: String, token: String?, accept: String?): HttpURLConnection {
        var u = url; var auth = token
        for (i in 0..5) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false; c.connectTimeout = 20000; c.readTimeout = 60000
            if (auth != null) c.setRequestProperty("Authorization", "Bearer $auth")
            if (accept != null) c.setRequestProperty("Accept", accept)
            val code = c.responseCode
            if (code in 300..399) { u = URL(URL(u), c.getHeaderField("Location")).toString(); auth = null; c.disconnect(); continue }
            if (code != 200) throw IOException("HTTP $code for $u")
            return c
        }
        throw IOException("Too many redirects")
    }

    /** Pulls the official Parrot OS Core image layers from Docker Hub. Every layer is verified against its SHA-256 digest. */
    fun install(log: (String) -> Unit) {
        if (!proot()) throw IOException("libproot.so missing in APK (run scripts/fetch-proot.sh before building)")
        if (ctx.filesDir.usableSpace < 2L * 1024 * 1024 * 1024) throw IOException("Need at least 2 GB of free storage")
        log("Contacting registry…")
        val tok = JSONObject(open("https://auth.docker.io/token?service=registry.docker.io&scope=repository:$image:pull", null, null).inputStream.bufferedReader().readText()).getString("token")
        val reg = "https://registry-1.docker.io/v2/$image"
        var man = JSONObject(open("$reg/manifests/$tag", tok, accIndex).inputStream.bufferedReader().readText())
        if (man.has("manifests")) {
            val arr = man.getJSONArray("manifests"); var dg: String? = null
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                if (m.optJSONObject("platform")?.optString("architecture") == "arm64") { dg = m.getString("digest"); break }
            }
            if (dg == null) throw IOException("No arm64 build of $image:$tag")
            man = JSONObject(open("$reg/manifests/$dg", tok, accMan).inputStream.bufferedReader().readText())
        }
        val layers = man.getJSONArray("layers")
        root.mkdirs()
        for (i in 0 until layers.length()) {
            val l = layers.getJSONObject(i)
            val digest = l.getString("digest")
            if (l.optString("mediaType").contains("zstd")) throw IOException("zstd layers are not supported yet")
            log("Layer ${i + 1}/${layers.length()} …")
            val tmp = File(ctx.cacheDir, "layer$i.tgz")
            val md = MessageDigest.getInstance("SHA-256")
            DigestInputStream(open("$reg/blobs/$digest", tok, null).inputStream, md).use { ins -> tmp.outputStream().use { o -> ins.copyTo(o) } }
            val got = "sha256:" + md.digest().joinToString("") { "%02x".format(it) }
            if (got != digest) { tmp.delete(); throw IOException("SHA-256 mismatch on layer ${i + 1}") }
            extract(tmp); tmp.delete()
        }
        File(root, "etc/resolv.conf").apply { delete(); writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n") }
        File(root, "etc/apt/apt.conf.d").mkdirs()
        File(root, "etc/apt/apt.conf.d/01-xlinux").writeText("APT::Sandbox::User \"root\";\n")   // apt sandbox user cannot be set under PRoot
        File(root, "tmp").mkdirs()
        File(root, ".xl-ok").writeText("$image:$tag")
        log("Parrot OS installed.")
    }

    private fun extract(file: File) {
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(file.inputStream()))).use { t ->
            var e = t.nextTarEntry
            while (e != null) {
                val name = e.name.removePrefix("./")
                val f = File(root, name)
                if (name.isNotEmpty() && !f.canonicalFile.path.startsWith(root.canonicalPath)) throw IOException("Bad path ${e.name}")
                val base = f.name
                when {
                    name.isEmpty() -> {}
                    base == ".wh..wh..opq" -> f.parentFile?.listFiles()?.forEach { it.deleteRecursively() }
                    base.startsWith(".wh.") -> File(f.parentFile, base.removePrefix(".wh.")).deleteRecursively()
                    e.isDirectory -> { f.mkdirs(); Os.chmod(f.path, (e.mode and 0x1ff) or 0x1c0) }
                    e.isSymbolicLink -> { f.parentFile?.mkdirs(); f.delete(); Os.symlink(e.linkName, f.path) }
                    e.isLink -> { f.parentFile?.mkdirs(); f.delete(); File(root, e.linkName.removePrefix("./")).copyTo(f, true) }
                    e.isFile -> { f.parentFile?.mkdirs(); f.delete(); f.outputStream().use { t.copyTo(it) }; Os.chmod(f.path, (e.mode and 0x1ff) or 0x180) }
                }
                e = t.nextTarEntry
            }
        }
    }

    var shellPref = "auto"

    fun setDns(list: String): Boolean {
        val ips = list.trim().split(Regex("\\s+")).filter { Regex("^[0-9a-fA-F:.]{3,45}$").matches(it) }
        if (ips.isEmpty() || !installed()) return false
        File(root, "etc/resolv.conf").apply { delete(); writeText(ips.joinToString("") { "nameserver $it\n" }) }
        return true
    }
    fun getDns(): String = runCatching { File(root, "etc/resolv.conf").readLines().filter { it.startsWith("nameserver ") }.joinToString(" ") { it.removePrefix("nameserver ") } }.getOrDefault("")

    /** Non-destructive repair: rewrites DNS, apt sandbox setting and /tmp. Never touches user files. */
    fun repair(): Boolean {
        if (!installed()) return false
        setDns(getDns().ifEmpty { "1.1.1.1 8.8.8.8" })
        File(root, "etc/apt/apt.conf.d").mkdirs()
        File(root, "etc/apt/apt.conf.d/01-xlinux").writeText("APT::Sandbox::User \"root\";\n")
        File(root, "tmp").mkdirs()
        return true
    }
    fun reset() { stop(); nuke(root) }

    companion object {
        /** Deletes a tree WITHOUT following symlinks (rootfs contains absolute links). */
        fun nuke(f: File) {
            val p = f.toPath()
            if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return
            Files.walkFileTree(p, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, a: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
                override fun postVisitDirectory(dir: Path, e: java.io.IOException?): FileVisitResult { Files.delete(dir); return FileVisitResult.CONTINUE }
            })
        }
        fun size(f: File): Long {
            var n = 0L
            Files.walk(f.toPath()).use { st -> st.forEach { if (Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS)) n += runCatching { Files.size(it) }.getOrDefault(0L) } }
            return n
        }
    }

    private fun cmd(vararg inner: String): ProcessBuilder {
        File(ctx.cacheDir, "proot-tmp").mkdirs()
        val args = mutableListOf("$lib/libproot.so", "--kill-on-exit", "--link2symlink", "-0", "-r", root.path,
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-w", "/root",
            "/usr/bin/env", "-i", "HOME=/root", "TERM=xterm-256color",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        args += inner
        val pb = ProcessBuilder(args).redirectErrorStream(true)
        pb.environment().apply {
            put("PROOT_LOADER", "$lib/libproot-loader.so")
            put("PROOT_TMP_DIR", File(ctx.cacheDir, "proot-tmp").path)
            put("LD_LIBRARY_PATH", lib)
        }
        return pb
    }

    /** Starts one long-lived real shell inside the Linux environment. */
    fun startShell(onOut: (String) -> Unit) {
        stop()
        val sh = when (shellPref) { "bash" -> "/bin/bash"; "sh" -> "/bin/sh"; else -> if (File(root, "bin/bash").exists()) "/bin/bash" else "/bin/sh" }
        val p = cmd(sh).start().also { proc = it }
        Thread {
            val r = p.inputStream.bufferedReader()
            val buf = CharArray(4096)
            while (true) { val n = r.read(buf); if (n < 0) break; onOut(String(buf, 0, n)) }
            onOut("\n[shell exited: ${runCatching { p.waitFor() }.getOrNull()}]\n")
        }.start()
    }

    fun send(line: String) { proc?.outputStream?.apply { write((line + "\n").toByteArray()); flush() } }
    fun interrupt() { proc?.destroy() }   // stop the shell and its children (real kill)
    fun stop() { proc?.destroy(); proc = null }
}
