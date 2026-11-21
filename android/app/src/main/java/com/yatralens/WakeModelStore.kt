package com.yatralens

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Unpacks the bundled, pinned Apache-2.0 Vosk English model into private app storage. */
object WakeModelStore {
    private const val ASSET = "wake_en_us_vosk_0_15.zip"
    private const val SHA256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
    private const val ROOT = "vosk-model-small-en-us-0.15/"

    @Synchronized fun modelPath(context: Context): File {
        val base = File(context.filesDir, "wake-model-v1")
        val ready = File(base, ".ready")
        if (ready.isFile && ready.readText() == SHA256 && File(base, "am/final.mdl").isFile &&
            File(base, "graph/HCLr.fst").isFile) return base
        val hash = MessageDigest.getInstance("SHA-256")
        context.assets.open(ASSET).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                hash.update(buffer, 0, n)
            }
        }
        check(hash.digest().joinToString("") { "%02x".format(it) } == SHA256) {
            "Bundled wake model fingerprint mismatch"
        }
        val staging = File(context.filesDir, "wake-model-v1-staging")
        if (staging.exists()) staging.deleteRecursively()
        check(staging.mkdirs()) { "Could not prepare wake model storage" }
        try {
            ZipInputStream(context.assets.open(ASSET)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    check(entry.name.startsWith(ROOT)) { "Invalid wake model entry" }
                    val relative = entry.name.removePrefix(ROOT)
                    if (relative.isNotBlank()) {
                        val target = File(staging, relative)
                        check(target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                            "Invalid wake model path"
                        }
                        if (entry.isDirectory) check(target.mkdirs() || target.isDirectory)
                        else {
                            check(target.parentFile?.mkdirs() == true || target.parentFile?.isDirectory == true)
                            target.outputStream().use { zip.copyTo(it) }
                        }
                    }
                    zip.closeEntry()
                }
            }
            check(File(staging, "am/final.mdl").isFile && File(staging, "graph/HCLr.fst").isFile)
            if (base.exists()) base.deleteRecursively()
            check(staging.renameTo(base)) { "Could not activate wake model" }
            ready.writeText(SHA256)
            return base
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
    }
}
