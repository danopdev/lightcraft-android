package ai.storyteller.lightcraft.engine

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Installs the AI mask model files, without extracting arbitrary paths. */
internal object AiMaskModelArchive {
    private const val WEIGHTS = "model.safetensors"
    private const val WEIGHTS_BYTES = 3_439_938_512L
    private const val WEIGHTS_SHA256 = "6d06f0a5f84e435071fe6603e61d0b4cc7b40e0d39d487cfd4d67d8cc11cc14a"
    private const val TOKENIZER_FILE_LIMIT = 16L * 1024 * 1024
    private const val EXTRA_FILES_LIMIT = 32L * 1024 * 1024
    private val requiredFiles = setOf(WEIGHTS, "vocab.json", "merges.txt")

    fun install(input: InputStream, target: File) = install(input, target, WEIGHTS_BYTES, WEIGHTS_SHA256)

    internal fun install(
        input: InputStream,
        target: File,
        expectedWeightsBytes: Long,
        expectedWeightsSha256: String = WEIGHTS_SHA256,
    ) {
        installStaged(target) { staging ->
            val copied = mutableSetOf<String>()
            var commonParent: String? = null
            var expandedBytes = 0L
            val expandedLimit = expectedWeightsBytes + EXTRA_FILES_LIMIT
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val path = entry.name.replace('\\', '/')
                    if (path.startsWith("/") || path.matches(Regex("^[A-Za-z]:.*")) || path.split('/').any { it == ".." }) {
                        throw IOException("The ZIP contains an unsafe path")
                    }
                    val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
                    val name = parts.lastOrNull()
                    val wanted = !entry.isDirectory && name != null && name in requiredFiles
                    val output = if (wanted) {
                        val fileName = requireNotNull(name)
                        val folder = parts.dropLast(1).joinToString("/")
                        if (commonParent != null && commonParent != folder) throw IOException("The three AI Mask files must be in the same folder in the ZIP")
                        commonParent = folder
                        if (!copied.add(fileName)) throw IOException("The ZIP contains more than one $fileName")
                        val limit = if (fileName == WEIGHTS) expectedWeightsBytes else TOKENIZER_FILE_LIMIT
                        if (entry.size > limit) throw IOException("$fileName is larger than expected")
                        FileOutputStream(File(staging, fileName))
                    } else {
                        null
                    }
                    val weightsDigest = if (wanted && name == WEIGHTS) MessageDigest.getInstance("SHA-256") else null
                    output.use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var fileBytes = 0L
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            fileBytes += count
                            expandedBytes += count
                            if (expandedBytes > expandedLimit) throw IOException("The ZIP expands beyond the AI Mask model files")
                            if (output != null) {
                                val fileName = requireNotNull(name)
                                val limit = if (fileName == WEIGHTS) expectedWeightsBytes else TOKENIZER_FILE_LIMIT
                                if (fileBytes > limit) throw IOException("$fileName is larger than expected")
                                out?.write(buffer, 0, count)
                                weightsDigest?.update(buffer, 0, count)
                            }
                        }
                        if (output != null && entry.size >= 0 && fileBytes != entry.size) {
                            throw IOException("$name is incomplete in the ZIP")
                        }
                        if (output != null && name == WEIGHTS && fileBytes != expectedWeightsBytes) {
                            throw IOException("$WEIGHTS must be the official ${expectedWeightsBytes}-byte AI Mask checkpoint")
                        }
                        if (weightsDigest != null && weightsDigest.digest().toHex() != expectedWeightsSha256) {
                            throw IOException("$WEIGHTS doesn't match the official AI Mask checkpoint")
                        }
                        if (output != null && fileBytes == 0L) throw IOException("$name is empty")
                    }
                    zip.closeEntry()
                }
            }
            val missing = requiredFiles - copied
            if (missing.isNotEmpty()) throw IOException("The ZIP is missing ${missing.sorted().joinToString()}")
        }
    }

    fun installFiles(files: Map<String, () -> InputStream>, target: File) =
        installFiles(files, target, WEIGHTS_BYTES, WEIGHTS_SHA256)

    internal fun installFiles(
        files: Map<String, () -> InputStream>,
        target: File,
        expectedWeightsBytes: Long,
        expectedWeightsSha256: String = WEIGHTS_SHA256,
    ) {
        val missing = requiredFiles - files.keys
        val unexpected = files.keys - requiredFiles
        if (missing.isNotEmpty() || unexpected.isNotEmpty()) {
            val details = buildList {
                if (missing.isNotEmpty()) add("missing ${missing.sorted().joinToString()}")
                if (unexpected.isNotEmpty()) add("unexpected ${unexpected.sorted().joinToString()}")
            }
            throw IOException("Select exactly ${requiredFiles.sorted().joinToString()}, ${details.joinToString("; ")}")
        }

        installStaged(target) { staging ->
            files.forEach { (name, open) ->
                val limit = if (name == WEIGHTS) expectedWeightsBytes else TOKENIZER_FILE_LIMIT
                val digest = if (name == WEIGHTS) MessageDigest.getInstance("SHA-256") else null
                var fileBytes = 0L
                open().use { input ->
                    FileOutputStream(File(staging, name)).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            fileBytes += count
                            if (fileBytes > limit) throw IOException("$name is larger than expected")
                            output.write(buffer, 0, count)
                            digest?.update(buffer, 0, count)
                        }
                    }
                }
                if (fileBytes == 0L) throw IOException("$name is empty")
                if (name == WEIGHTS && fileBytes != expectedWeightsBytes) {
                    throw IOException("$WEIGHTS must be the official ${expectedWeightsBytes}-byte AI Mask checkpoint")
                }
                if (digest != null && digest.digest().toHex() != expectedWeightsSha256) {
                    throw IOException("$WEIGHTS doesn't match the official AI Mask checkpoint")
                }
            }
        }
    }

    private fun installStaged(target: File, populate: (File) -> Unit) {
        val parent = target.parentFile ?: throw IOException("The AI Mask model folder has no parent")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Couldn't create ${parent.absolutePath}")
        val staging = File(parent, ".${target.name}.importing")
        val backup = File(parent, ".${target.name}.previous")
        if (!target.exists() && backup.exists() && !backup.renameTo(target)) {
            throw IOException("Couldn't restore the previous AI Mask model")
        }
        if (staging.exists() && !staging.deleteRecursively()) throw IOException("Couldn't clear an earlier incomplete AI Mask import")

        try {
            if (!staging.mkdirs()) throw IOException("Couldn't create the temporary AI Mask model folder")
            populate(staging)

            if (backup.exists() && !backup.deleteRecursively()) throw IOException("Couldn't clear the previous AI Mask model backup")
            val hadModel = target.exists()
            if (hadModel && !target.renameTo(backup)) throw IOException("Couldn't replace the existing AI Mask model")
            if (!staging.renameTo(target)) {
                if (hadModel && !backup.renameTo(target)) {
                    throw IOException("Couldn't install the AI Mask model or restore the previous one")
                }
                throw IOException("Couldn't install the AI Mask model")
            }
            if (hadModel && !backup.deleteRecursively()) throw IOException("The new AI Mask model was installed, but the previous copy couldn't be removed")
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
