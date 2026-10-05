package com.unoone.agent.localbrain

import com.unoone.agent.core.device.*
import org.junit.Assert.*
import org.junit.Test

class GroundingBoxTest {
    private val raw = """{"left":0.1,"top":0.1,"right":0.3,"bottom":0.3,"confidence":0.95}"""
    private fun state() = PerceptionState(UiSnapshot("s1", 100, 0, RectData(0,0,100,100), emptyList()),
        visualTargets = listOf(VisualTarget("issued", "s1", RectData(10,10,30,30), "button")))
    private fun image(crop: RectData = RectData(0,0,100,100), captured: Long = 101_000_000,
        fence: Long = 100_000_000, id: String = "s1", epoch: Long = 0, secretSafe: Boolean = true,
        rotation: Int = 0) = SnapshotImageEnvelope(byteArrayOf(1), id, epoch, RectData(0,0,100,100),
        crop, fence, captured, 1, rotation, NativeImageReviewReceipt(secretSafe, true, SnapshotImageEnvelope.digest(byteArrayOf(1)),
            id, UiStateHasher.hash(state().snapshot), emptySet(), epoch, 1, rotation, captured / 1_000_000))
    @Test fun imageEnvelopeRejectsCropStaleEpochAndPrivacy() {
        for (bad in listOf(image(crop = RectData(50,50,100,100)), image(captured = 99_000_000),
            image(id = "other"), image(epoch = 1), image(secretSafe = false), image(rotation = 4)))
            assertTrue(runCatching { bad.validatedBytes(state(), 102) }.isFailure)
        assertTrue(runCatching { image().validatedBytes(state(), 31_000) }.isFailure)
        assertTrue(runCatching { image().validatedBytes(state(), 90) }.isFailure)
        assertArrayEquals(byteArrayOf(1), image().validatedBytes(state(), 102))
    }
    @Test fun elapsedClockWithSuspendOffsetIsRequired() {
        val offset = 3_600_000L
        val s = PerceptionState(UiSnapshot("s1", offset + 100, 0, RectData(0,0,100,100), emptyList()))
        val frame = image(fence = (offset + 100) * 1_000_000, captured = (offset + 101) * 1_000_000)
        assertArrayEquals(byteArrayOf(1), frame.validatedBytes(s, offset + 102))
        assertTrue(runCatching { frame.validatedBytes(s, 102) }.isFailure)
    }
    @Test fun consentDigestAndBindingCannotBeReusedForNewBytes() {
        val approved = image()
        val mismatched = SnapshotImageEnvelope(byteArrayOf(2), "s1", 0, RectData(0,0,100,100),
            RectData(0,0,100,100), 100_000_000, 101_000_000, 1, 0, approved.review)
        assertTrue(runCatching { mismatched.validatedBytes(state(), 102) }.isFailure)
        assertArrayEquals(byteArrayOf(1), approved.validatedBytes(state(), 20_000))
        assertTrue(runCatching { approved.validatedBytes(state(), 20_000, 5_000) }.isFailure)
        approved.close()
        assertTrue(runCatching { approved.validatedBytes(state(), 102) }.isFailure)
    }
    @Test fun mapsOnlyIssuedTarget() {
        assertEquals("issued", GroundingBox.parse(raw).matchIssuedTarget(state()).visualTargetId)
    }
    @Test fun rejectsMalformedGrounding() {
        for (bad in listOf("[$raw]", "```json\n$raw\n```", raw + raw,
            raw.replace("0.1", "-0.1"), raw.replace("0.95", "1.1"), raw.replace("0.95", "\"0.95\""),
            raw.replace("0.3", "0.0"), raw.replace("\"confidence\"", "\"unknown\""))) {
            assertTrue(bad, runCatching { GroundingBox.parse(bad) }.isFailure)
        }
    }
    @Test fun refusesLowConfidenceOrNoTarget() {
        assertTrue(runCatching { GroundingBox.parse(raw.replace("0.95", "0.2")).matchIssuedTarget(state()) }.isFailure)
        assertTrue(runCatching { GroundingBox(.7,.7,.9,.9,.99).matchIssuedTarget(state()) }.isFailure)
    }
    @Test fun controllerCodecAcceptsExactlyOneAction() {
        assertEquals(DeviceAction.Observe, DeviceActionCodec.decode("""{"type":"Observe"}""",state(),100))
        for (bad in listOf("""[{"type":"Observe"}]""", """{"type":"Observe"}{"type":"Home"}""",
            """{"type":"Observe","extra":true}""", """{"type":"ClickVisualTarget","snapshotId":"s1","targetId":"invented"}""")) {
            assertTrue(runCatching { DeviceActionCodec.decode(bad,state(),100) }.isFailure)
        }
    }
}
