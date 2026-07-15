package com.unoone.agent.core.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the screen-reference gate that controls whether [buildContextSnapshot] grabs
 * accessibility screen text + OCR. The gate is conservative: when uncertain it captures, so a
 * screen-dependent planner is never starved; non-screen commands skip the grab (privacy + latency).
 */
class ScreenReferenceTest {

    // === screen-referencing -> capture (true) ===

    @Test
    fun readThisPageIsScreenReferencing() {
        assertTrue(ScreenReference.isScreenReferencing("read this page"))
    }

    @Test
    fun whatsOnMyScreenIsScreenReferencing() {
        assertTrue(ScreenReference.isScreenReferencing("what's on my screen"))
    }

    @Test
    fun readTheScreenIsScreenReferencing() {
        assertTrue(ScreenReference.isScreenReferencing("read the screen"))
    }

    @Test
    fun ocrScreenIsScreenReferencing() {
        assertTrue(ScreenReference.isScreenReferencing("ocr the screen"))
    }

    @Test
    fun tapTheLoginButtonIsScreenReferencing() {
        assertTrue(ScreenReference.isScreenReferencing("tap the login button"))
    }

    @Test
    fun fillTheNameFieldIsScreenReferencing() {
        assertTrue(ScreenReference.isScreenReferencing("fill the name field"))
    }

    @Test
    fun describeThisAppIsScreenReferencing() {
        assertTrue(ScreenReference.isScreenReferencing("describe this app"))
    }

    @Test
    fun whatIsVisibleIsScreenReferencing() {
        assertTrue(ScreenReference.isScreenReferencing("what is visible"))
    }

    // === non-screen -> skip capture (false) ===

    @Test
    fun explainGodIsNotScreenReferencing() {
        assertFalse(ScreenReference.isScreenReferencing("explain god"))
    }

    @Test
    fun whatIsPhotosynthesisIsNotScreenReferencing() {
        assertFalse(ScreenReference.isScreenReferencing("what is photosynthesis"))
    }

    @Test
    fun draftAnEmailIsNotScreenReferencing() {
        assertFalse(ScreenReference.isScreenReferencing("draft an email to mom about the trip"))
    }

    @Test
    fun searchMyNotesIsNotScreenReferencing() {
        assertFalse(ScreenReference.isScreenReferencing("search my notes for recipes"))
    }

    @Test
    fun createNoteIsNotScreenReferencing() {
        assertFalse(ScreenReference.isScreenReferencing("create note buy milk"))
    }

    @Test
    fun openCalendarIsNotScreenReferencing() {
        assertFalse(ScreenReference.isScreenReferencing("open calendar"))
    }

    @Test
    fun tellMeAJokeIsNotScreenReferencing() {
        assertFalse(ScreenReference.isScreenReferencing("tell me a joke"))
    }

    @Test
    fun cameraActionsAreNotScreenReferencing() {
        // Blind-Aid / camera references use the camera, not the foreground screen text.
        assertFalse(ScreenReference.isScreenReferencing("detect objects"))
        assertFalse(ScreenReference.isScreenReferencing("what's in front of me"))
        assertFalse(ScreenReference.isScreenReferencing("activate blind aid"))
    }

    @Test
    fun emptyIsNotScreenReferencing() {
        assertFalse(ScreenReference.isScreenReferencing(""))
    }
}