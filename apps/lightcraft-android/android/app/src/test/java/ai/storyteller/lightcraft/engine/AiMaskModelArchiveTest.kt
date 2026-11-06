package ai.storyteller.lightcraft.engine

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AiMaskModelArchiveTest {
    @Test
    fun installsOnlyTheRequiredFilesFromOneNestedFolder() {
        val root = Files.createTempDirectory("ai-mask-model-test").toFile()
        try {
            val target = File(root, "models/ai-mask")
            val weights = byteArrayOf(1, 2, 3, 4)
            AiMaskModelArchive.install(
                zip(
                    "checkpoint/model.safetensors" to weights,
                    "checkpoint/vocab.json" to "{}".toByteArray(),
                    "checkpoint/merges.txt" to "a b".toByteArray(),
                    "checkpoint/README.md" to "ignored".toByteArray(),
                ),
                target,
                expectedWeightsBytes = 4,
                expectedWeightsSha256 = weights.sha256(),
            )
            assertEquals(listOf("merges.txt", "model.safetensors", "vocab.json"), target.list()?.sorted())
            assertEquals("a b", File(target, "merges.txt").readText())
            assertFalse(File(target, "README.md").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unsafePathsAndDifferentModelFoldersAreRejectedWithoutReplacingTheInstalledModel() {
        val root = Files.createTempDirectory("ai-mask-model-test").toFile()
        try {
            val target = File(root, "models/ai-mask").apply { mkdirs() }
            File(target, "keep.txt").writeText("existing")

            expectIOException {
                AiMaskModelArchive.install(
                    zip("../model.safetensors" to byteArrayOf(1, 2, 3, 4)),
                    target,
                    expectedWeightsBytes = 4,
                )
            }
            expectIOException {
                AiMaskModelArchive.install(
                    zip(
                        "one/vocab.json" to "{}".toByteArray(),
                        "two/model.safetensors" to byteArrayOf(1, 2, 3, 4),
                        "one/merges.txt" to "a b".toByteArray(),
                    ),
                    target,
                    expectedWeightsBytes = 4,
                )
            }

            assertEquals("existing", File(target, "keep.txt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun refusesArchivesMissingARequiredFile() {
        val root = Files.createTempDirectory("ai-mask-model-test").toFile()
        try {
            val weights = byteArrayOf(1, 2, 3, 4)
            expectIOException {
                AiMaskModelArchive.install(
                    zip(
                        "model.safetensors" to weights,
                        "vocab.json" to "{}".toByteArray(),
                    ),
                    File(root, "models/ai-mask"),
                    expectedWeightsBytes = 4,
                    expectedWeightsSha256 = weights.sha256(),
                )
            }
            assertFalse(File(root, "models/ai-mask").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun installsTheThreeIndividuallySelectedFiles() {
        val root = Files.createTempDirectory("ai-mask-model-test").toFile()
        try {
            val target = File(root, "models/ai-mask")
            val weights = byteArrayOf(1, 2, 3, 4)
            AiMaskModelArchive.installFiles(
                mapOf(
                    "model.safetensors" to { ByteArrayInputStream(weights) },
                    "vocab.json" to { ByteArrayInputStream("{}".toByteArray()) },
                    "merges.txt" to { ByteArrayInputStream("a b".toByteArray()) },
                ),
                target,
                expectedWeightsBytes = 4,
                expectedWeightsSha256 = weights.sha256(),
            )
            assertEquals(listOf("merges.txt", "model.safetensors", "vocab.json"), target.list()?.sorted())
            assertEquals("a b", File(target, "merges.txt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun refusesIncorrectlySelectedFilesWithoutReplacingTheInstalledModel() {
        val root = Files.createTempDirectory("ai-mask-model-test").toFile()
        try {
            val target = File(root, "models/ai-mask").apply { mkdirs() }
            File(target, "keep.txt").writeText("existing")

            expectIOException {
                AiMaskModelArchive.installFiles(
                    mapOf("model.safetensors" to { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) }),
                    target,
                    expectedWeightsBytes = 4,
                )
            }
            expectIOException {
                AiMaskModelArchive.installFiles(
                    mapOf(
                        "model.safetensors" to { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) },
                        "vocab.json" to { ByteArrayInputStream("{}".toByteArray()) },
                        "merges.txt" to { ByteArrayInputStream("a b".toByteArray()) },
                    ),
                    target,
                    expectedWeightsBytes = 4,
                    expectedWeightsSha256 = "wrong",
                )
            }
            assertEquals("existing", File(target, "keep.txt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArrayInputStream =
        ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { zip ->
                entries.forEach { (name, contents) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(contents)
                    zip.closeEntry()
                }
            }
            ByteArrayInputStream(bytes.toByteArray())
        }

    private fun expectIOException(block: () -> Unit) {
        try {
            block()
        } catch (_: IOException) {
            return
        }
        throw AssertionError("Expected IOException")
    }

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
