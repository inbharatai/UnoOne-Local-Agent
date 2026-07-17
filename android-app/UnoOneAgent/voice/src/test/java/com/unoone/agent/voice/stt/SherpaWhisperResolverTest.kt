package com.unoone.agent.voice.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class SherpaWhisperResolverTest {

    @Test
    fun resolvesOfficialBaseInt8Layout() {
        val root = Files.createTempDirectory("whisper-resolver").toFile()
        val model = root.resolve("sherpa-onnx-whisper-base").apply { mkdirs() }
        model.resolve("base-encoder.int8.onnx").writeText("encoder")
        model.resolve("base-decoder.int8.onnx").writeText("decoder")
        model.resolve("base-tokens.txt").writeText("tokens")

        val files = SherpaSttEngine.resolveWhisperFiles(root.absolutePath)!!
        assertEquals("base", files.family)
        assertEquals("base-encoder.int8.onnx", files.encoder.name)
        assertEquals("base-decoder.int8.onnx", files.decoder.name)
    }

    @Test
    fun rejectsMixedOrIncompleteFamilies() {
        val root = Files.createTempDirectory("whisper-incomplete").toFile()
        root.resolve("base-encoder.int8.onnx").writeText("encoder")
        root.resolve("tiny-decoder.int8.onnx").writeText("decoder")
        root.resolve("base-tokens.txt").writeText("tokens")
        assertNull(SherpaSttEngine.resolveWhisperFiles(root.absolutePath))
    }
}
