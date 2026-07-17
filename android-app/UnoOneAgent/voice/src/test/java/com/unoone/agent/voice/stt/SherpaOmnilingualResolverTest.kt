package com.unoone.agent.voice.stt

import java.nio.file.Files
import kotlin.io.path.createDirectory
import kotlin.io.path.createFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SherpaOmnilingualResolverTest {

    @Test
    fun resolvesOfficialExtractedArchive() {
        val root = Files.createTempDirectory("omnilingual")
        val extracted = root.resolve(
            "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12"
        ).createDirectory()
        extracted.resolve("model.int8.onnx").createFile()
        extracted.resolve("tokens.txt").createFile()

        val files = SherpaSttEngine.resolveOmnilingualFiles(root.toString())!!

        assertEquals("model.int8.onnx", files.model.name)
        assertEquals("tokens.txt", files.tokens.name)
    }

    @Test
    fun rejectsIncompleteInstall() {
        val root = Files.createTempDirectory("omnilingual-incomplete")
        root.resolve("model.int8.onnx").createFile()

        assertNull(SherpaSttEngine.resolveOmnilingualFiles(root.toString()))
    }
}
