package com.unoone.agent

import com.unoone.agent.core.model.compoundSteps
import com.unoone.agent.localbrain.RuleBasedParser
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RuleBasedParserTest {
    @Test
    fun routesOfflinePdfAndDocxFillCommands() {
        val pdf = RuleBasedParser.parse("fill a PDF form")!!
        assertEquals("prepare_document_fill", pdf.tool)
        assertEquals("pdf", pdf.args["format"]!!.jsonPrimitive.content)

        val docx = RuleBasedParser.parse("fill a DOCX template")!!
        assertEquals("prepare_document_fill", docx.tool)
        assertEquals("docx", docx.args["format"]!!.jsonPrimitive.content)
    }

    @Test
    fun testBlindAidActivationTriggers() {
        // 4D: "obstacles" and "barriers" alone are DEACTIVATION, not activation.
        // Only triggers with positive context ("detect", "start", etc.) activate.
        val triggers = listOf(
            "start blind aid",
            "start blind",
            "start blind mode",
            "start blind view",
            "enable blind mode",
            "blind mode on",
            "blind view on",
            "blind mode chalu karo",
            "blind mode start karo",
            "blind view shuru karo",
            "activate blind aid",
            "detect objects",
            "what's in front of me",
            "detect barrier",
            "detect barriers",
            "look for obstacles"
        )
        for (trigger in triggers) {
            val toolCall = RuleBasedParser.parse(trigger)
            assertNotNull("Failed to parse trigger: $trigger", toolCall)
            assertEquals("detect_objects", toolCall!!.tool)
        }
        listOf(
            "ब्लाइंड मोड चालू करो",
            "ब्लाइंड मोड शुरू करो",
            "ब्लाइंड व्यू चालू करो",
            "नेत्रहीन मोड चालू करो"
        ).forEach { trigger ->
            assertEquals(trigger, "detect_objects", RuleBasedParser.parse(trigger)?.tool)
        }
    }

    @Test
    fun testBlindAidDeactivationTriggers() {
        val triggers = listOf(
            "stop blind aid",
            "stop blind",
            "stop blind mode",
            "stop blind view",
            "blind mode off",
            "disable blind mode",
            "blind mode band karo",
            "deactivate blind aid",
            "turn off blind aid",
            "stop scanning",
            "stop barriers",
            "remove obstacles",
            "turn off obstacles",
            "disable barriers",
            "no more barriers"
        )
        for (trigger in triggers) {
            val toolCall = RuleBasedParser.parse(trigger)
            assertNotNull("Failed to parse deactivation trigger: $trigger", toolCall)
            assertEquals("deactivate_blind_aid", toolCall!!.tool)
        }
    }

    @Test
    fun testNoteCreationTriggers() {
        val toolCall = RuleBasedParser.parse("remember: pick up groceries")
        assertNotNull(toolCall)
        assertEquals("create_note", toolCall!!.tool)
    }

    @Test
    fun testNoteCreationWithoutColon() {
        // "add note buy milk" should extract "buy milk" without leaking "add " prefix
        val toolCall = RuleBasedParser.parse("add note buy milk")
        assertNotNull(toolCall)
        assertEquals("create_note", toolCall!!.tool)
        val content = toolCall.args["content"]?.toString()?.replace("\"", "") ?: ""
        assertEquals("buy milk", content)
    }

    @Test
    fun testNoteRememberToStripsToPrefix() {
        // "remember to buy groceries" should strip the grammatical "to" and yield "buy groceries"
        val toolCall = RuleBasedParser.parse("remember to buy groceries")
        assertNotNull(toolCall)
        assertEquals("create_note", toolCall!!.tool)
        val content = toolCall.args["content"]?.toString()?.replace("\"", "") ?: ""
        assertEquals("buy groceries", content)
    }

    @Test
    fun testCompoundCommand() {
        // "scroll down and go home" — both halves are navigation commands,
        // so the compound handler splits and parses them correctly into an ordered
        // `steps` array. Domain-specific rules (skill, email, whatsapp, calendar) are
        // checked BEFORE compound splitting, so they preserve their internal "and" semantics.
        val toolCall = RuleBasedParser.parse("scroll down and go home")
        assertNotNull("Compound command should parse", toolCall)
        assertEquals("compound", toolCall!!.tool)
        val steps = toolCall.compoundSteps()
        assertEquals("Compound must expand to 2 ordered steps", 2, steps.size)
        assertEquals("system_control", steps[0].tool)
        assertEquals("system_control", steps[1].tool)
        assertEquals("scroll_down", steps[0].args["action"]?.jsonPrimitive?.content)
        assertEquals("go_home", steps[1].args["action"]?.jsonPrimitive?.content)
    }

    @Test
    fun testSearchForOpensGoogleSearch() {
        // "search for cats" → open_url with a Google search URL (URL-encoded query).
        val toolCall = RuleBasedParser.parse("search for cats")
        assertNotNull(toolCall)
        assertEquals("open_url", toolCall!!.tool)
        val url = toolCall.args["url"]?.jsonPrimitive?.content ?: ""
        assertTrue("URL must be a google search", url.startsWith("https://www.google.com/search?q="))
        assertTrue("Query must be encoded", url.endsWith("cats"))
    }

    @Test
    fun testCompoundOpenChromeAndSearchForCats() {
        // README compound example: "open chrome and search for cats" → compound([open_chrome,
        // open_url(google search "cats")]). Both halves parse, so a 2-step compound is produced.
        val toolCall = RuleBasedParser.parse("open chrome and search for cats")
        assertNotNull(toolCall)
        assertEquals("compound", toolCall!!.tool)
        val steps = toolCall.compoundSteps()
        assertEquals("Compound must expand to 2 ordered steps", 2, steps.size)
        assertEquals("open_chrome", steps[0].tool)
        assertEquals("open_url", steps[1].tool)
        val url = steps[1].args["url"]?.jsonPrimitive?.content ?: ""
        assertTrue("Second step must be a google search for cats", url.contains("search?q=") && url.endsWith("cats"))
    }

    @Test
    fun testSearchDoesNotHijackNoteSearch() {
        // "search my notes for milk" must NOT route to open_url — note search is left for the
        // LLM/search_notes path. The "note" guard excludes it from the web-search rule.
        val toolCall = RuleBasedParser.parse("search my notes for milk")
        // It may parse to null (no note-search rule offline) or to a non-open_url tool, but never
        // open_url with a google search.
        if (toolCall != null) {
            assertTrue(
                "Note search must not be hijacked into open_url",
                toolCall.tool != "open_url" || !(toolCall.args["url"]?.jsonPrimitive?.content ?: "").contains("search?q=")
            )
        }
    }

    @Test
    fun testCompoundCommandDoesNotBreakSkillSteps() {
        // "create skill called greeting to say hello and wave goodbye" — the skill rule
        // must match as a whole (not split on "and"), with both steps preserved.
        val toolCall = RuleBasedParser.parse("create skill called greeting to say hello and wave goodbye")
        assertNotNull(toolCall)
        assertEquals("create_skill", toolCall!!.tool)
        val steps = toolCall.args["steps"]?.toString()?.replace("\"", "") ?: ""
        assertEquals(true, steps.contains("say hello"))
        assertEquals(true, steps.contains("wave goodbye"))
    }

    @Test
    fun testLongPressWithText() {
        val toolCall = RuleBasedParser.parse("long press on settings")
        assertNotNull(toolCall)
        assertEquals("system_control", toolCall!!.tool)
        assertEquals("long_press", toolCall.args["action"]?.toString()?.replace("\"", ""))
        assertEquals("settings", toolCall.args["target"]?.toString()?.replace("\"", ""))
    }

    @Test
    fun testActivationNotConfusedByDeactivation() {
        // Ensure "deactivate blind aid" does NOT match activation
        val toolCall = RuleBasedParser.parse("deactivate blind aid")
        assertNotNull(toolCall)
        assertEquals("deactivate_blind_aid", toolCall!!.tool)

        // And "stop barriers" should be deactivation, not activation
        val toolCall2 = RuleBasedParser.parse("stop barriers")
        assertNotNull(toolCall2)
        assertEquals("deactivate_blind_aid", toolCall2!!.tool)
    }

    // === 4D Parser Bug Fix Tests ===

    @Test
    fun testBareBarriersTriggersDeactivation() {
        // 4D: "barriers" alone should be deactivation only, not detect_objects
        val toolCall = RuleBasedParser.parse("barriers")
        assertNotNull("Bare 'barriers' should parse as deactivation", toolCall)
        assertEquals("deactivate_blind_aid", toolCall!!.tool)
    }

    @Test
    fun testNoteWithNegationVerb() {
        // 4D: "delete note" should NOT create a note
        val toolCall = RuleBasedParser.parse("delete note groceries")
        // Should not parse as create_note
        if (toolCall != null) {
            assert(toolCall.tool != "create_note") { "Negation verb + note should not create a note" }
        }
    }

    @Test
    fun testRemoveNoteDoesNotCreateNote() {
        val toolCall = RuleBasedParser.parse("remove note about meeting")
        if (toolCall != null) {
            assert(toolCall.tool != "create_note") { "Remove + note should not create a note" }
        }
    }

    @Test
    fun testOpenSettingsBeforeOpenGoogle() {
        // 4D: "open settings" parses as open_app (with Settings package), not open_chrome
        val toolCall = RuleBasedParser.parse("open settings")
        assertNotNull(toolCall)
        assertEquals("open_app", toolCall!!.tool)
    }

    @Test
    fun testOpenGoogleSettingsParsesAsUrl() {
        // "open google settings" does NOT contain "open settings" as a contiguous substring
        // (because "open " is followed by "google", not "settings"), so it falls through
        // to the "open google" rule and returns open_url.
        val toolCall = RuleBasedParser.parse("open google settings")
        assertNotNull(toolCall)
        assertEquals("open_url", toolCall!!.tool)
    }

    @Test
    fun testThreePartCompoundCommand() {
        // "A and B and C" must parse into a compound with all 3 steps preserved in order
        // (the 3rd part was previously parsed and discarded).
        val toolCall = RuleBasedParser.parse("open chrome and scroll down and go home")
        assertNotNull(toolCall)
        assertEquals("compound", toolCall!!.tool)
        val steps = toolCall.compoundSteps()
        assertEquals("Three-part compound must expand to 3 ordered steps", 3, steps.size)
        assertEquals("open_chrome", steps[0].tool)
        assertEquals("system_control", steps[1].tool)
        assertEquals("system_control", steps[2].tool)
        assertEquals("scroll_down", steps[1].args["action"]?.jsonPrimitive?.content)
        assertEquals("go_home", steps[2].args["action"]?.jsonPrimitive?.content)
    }

    @Test
    fun testEmailRegexWithDot() {
        // 4D: Email regex should match emails with dots in local part
        val toolCall = RuleBasedParser.parse("send email to john.doe@example.com about meeting")
        if (toolCall != null && toolCall.tool == "draft_email") {
            val email = toolCall.args["to"]?.toString()?.replace("\"", "") ?: ""
            assertTrue("Email should contain @example.com", email.contains("@example.com"))
        }
    }

    @Test
    fun testCalendarCheck() {
        val toolCall = RuleBasedParser.parse("check calendar")
        assertNotNull(toolCall)
        assertEquals("check_calendar", toolCall!!.tool)
    }

    @Test
    fun testOpenCalendarLaunches() {
        // Regression: plain "open calendar" used to hit the calendar-keyword branch, find no
        // check/add verb, return null, and never reach the open_app catch-all. Now it routes to
        // the dedicated open_calendar launcher.
        for (phrase in listOf(
            "open calendar", "open the calendar", "open my calendar", "open calendar app",
            "launch calendar", "launch the calendar", "launch calendar app",
            "show calendar app", "show the calendar app"
        )) {
            val toolCall = RuleBasedParser.parse(phrase)
            assertNotNull("$phrase should parse", toolCall)
            assertEquals("$phrase -> open_calendar", "open_calendar", toolCall!!.tool)
        }
    }

    @Test
    fun testOpenCalendarInsertStillRoutesToInsert() {
        val toolCall = RuleBasedParser.parse("add meeting to calendar")
        assertNotNull(toolCall)
        assertEquals("open_calendar_insert", toolCall!!.tool)
    }

    @Test
    fun scheduleCalendarCommandRoutesToAReviewableInsert() {
        val toolCall = RuleBasedParser.parse("schedule a meeting tomorrow at 4 PM")
        assertNotNull(toolCall)
        assertEquals("open_calendar_insert", toolCall!!.tool)
        assertTrue(toolCall.args["start_time"]!!.jsonPrimitive.content.contains("T16:00"))
    }

    @Test
    fun testOpenWhatsAppRoutesToAppLaunchNotMessageDraft() {
        for (phrase in listOf("open whatsapp", "open my whatsapp", "launch whatsapp")) {
            val toolCall = RuleBasedParser.parse(phrase)
            assertNotNull(phrase, toolCall)
            assertEquals("$phrase must launch the app", "open_app", toolCall!!.tool)
            assertEquals("com.whatsapp", toolCall.args["package_name"]?.toString()?.trim('"'))
        }
    }

    @Test
    fun testReadScreen() {
        val toolCall = RuleBasedParser.parse("read screen")
        assertNotNull(toolCall)
        assertEquals("read_screen", toolCall!!.tool)
    }

    @Test
    fun testOcrScreen() {
        // "ocr" maps to read_screen (the parser uses "ocr" or "screen text" triggers)
        val toolCall = RuleBasedParser.parse("ocr the screen")
        assertNotNull(toolCall)
        assertEquals("read_screen", toolCall!!.tool)
    }

    @Test
    fun testOpenCamera() {
        val toolCall = RuleBasedParser.parse("open camera")
        assertNotNull(toolCall)
        assertEquals("open_camera", toolCall!!.tool)
    }

    @Test
    fun testDraftEmail() {
        val toolCall = RuleBasedParser.parse("draft email to test@example.com about update with body hello")
        assertNotNull(toolCall)
        assertEquals("draft_email", toolCall!!.tool)
    }

    @Test
    fun testFindAndClick() {
        val toolCall = RuleBasedParser.parse("find and click submit")
        assertNotNull(toolCall)
        assertEquals("system_control", toolCall!!.tool)
        assertEquals("find_and_click", toolCall.args["action"]?.toString()?.replace("\"", ""))
        assertEquals("submit", toolCall.args["target"]?.toString()?.replace("\"", ""))
    }

    @Test
    fun testScrollDown() {
        val toolCall = RuleBasedParser.parse("scroll down")
        assertNotNull(toolCall)
        assertEquals("system_control", toolCall!!.tool)
        assertEquals("scroll_down", toolCall.args["action"]?.toString()?.replace("\"", ""))
    }

    // Eyes-free (WS4): the secure-browser friendly names are domain-specific so a trailing task
    // clause is not split off, and each friendly name resolves to its canonical approved origin.
    @Test
    fun openUnigurusRoutesToSecureBrowserNavigateOnly() {
        val toolCall = RuleBasedParser.parse("open unigurus")
        assertNotNull(toolCall)
        assertEquals("secure_browser_task", toolCall!!.tool)
        assertEquals("https://unigurus.com", toolCall.args["origin"]?.jsonPrimitive?.content)
        assertEquals("", toolCall.args["task"]?.jsonPrimitive?.content)
    }

    @Test
    fun openUniassistAndFillFormKeepsTaskIntact() {
        val toolCall = RuleBasedParser.parse("open uniassist and fill the profile form")
        assertNotNull(toolCall)
        assertEquals("secure_browser_task", toolCall!!.tool)
        assertEquals("https://uniassist.ai", toolCall.args["origin"]?.jsonPrimitive?.content)
        assertEquals("fill the profile form", toolCall.args["task"]?.jsonPrimitive?.content)
    }

    @Test
    fun eachApprovedFriendlyNameResolvesToItsCanonicalOrigin() {
        val cases = listOf(
            "open testsprep" to "https://testsprep.in",
            "open inbharat and read the page" to "https://inbharat.ai",
            "open uni-assist" to "https://uniassist.ai",
            "open in bharat" to "https://inbharat.ai"
        )
        for ((command, expectedOrigin) in cases) {
            val toolCall = RuleBasedParser.parse(command)
            assertNotNull("Failed to parse: $command", toolCall)
            assertEquals("secure_browser_task", toolCall!!.tool)
            assertEquals(expectedOrigin, toolCall.args["origin"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun secureBrowserPhraseDefaultsToUnigurus() {
        val toolCall = RuleBasedParser.parse("secure browser")
        assertNotNull(toolCall)
        assertEquals("secure_browser_task", toolCall!!.tool)
        assertEquals("https://unigurus.com", toolCall.args["origin"]?.jsonPrimitive?.content)
    }

    @Test
    fun friendlyNameInsideAnUnrelatedIntentIsNotHijackedIntoBrowser() {
        // "inbharat" appears, but the user wants a NOTE — no open/launch verb + "secure browser".
        // Must route to create_note, not secure_browser_task.
        val toolCall = RuleBasedParser.parse("create a note about inbharat")
        assertNotNull(toolCall)
        assertEquals("create_note", toolCall!!.tool)
    }

    @Test
    fun openUnigurusAndFillFormBeatsTheFillBranch() {
        // Regression: the `fill` gesture branch must not shadow secure_browser_task.
        val toolCall = RuleBasedParser.parse("open unigurus and fill the contact form")
        assertNotNull(toolCall)
        assertEquals("secure_browser_task", toolCall!!.tool)
        assertEquals("https://unigurus.com", toolCall.args["origin"]?.jsonPrimitive?.content)
        assertEquals("fill the contact form", toolCall.args["task"]?.jsonPrimitive?.content)
    }

    @Test
    fun spokenPublicUrlIsPreservedForPrototypeExecutorValidation() {
        val toolCall = RuleBasedParser.parse(
            "open secure browser example.org/application and fill the contact form"
        )
        assertNotNull(toolCall)
        assertEquals("secure_browser_task", toolCall!!.tool)
        assertEquals("example.org/application", toolCall.args["origin"]?.jsonPrimitive?.content)
        assertEquals("fill the contact form", toolCall.args["task"]?.jsonPrimitive?.content)
    }

    @Test
    fun emailFieldDataCannotHijackFriendlyBrowserOrigin() {
        val toolCall = RuleBasedParser.parse(
            "open unigurus and fill the contact form with name UnoOne Test " +
                "and email qa@example.com and stop before submission"
        )

        assertNotNull(toolCall)
        assertEquals("secure_browser_task", toolCall!!.tool)
        assertEquals("https://unigurus.com", toolCall.args["origin"]?.jsonPrimitive?.content)
        assertTrue(toolCall.args["task"]?.jsonPrimitive?.content.orEmpty().contains("qa@example.com"))
    }

    @Test
    fun explicitHttpsFormCommandRoutesWholeInstructionToSecureBrowser() {
        val toolCall = RuleBasedParser.parse(
            "open https://httpbin.org/forms/post and fill customer name Reetu, " +
                "email qa@example.com and stop before submission"
        )

        assertNotNull(toolCall)
        assertEquals("secure_browser_task", toolCall!!.tool)
        assertEquals(
            "https://httpbin.org/forms/post",
            toolCall.args["origin"]?.jsonPrimitive?.content
        )
        val task = toolCall.args["task"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(task.contains("fill customer name reetu"))
        assertTrue(task.contains("qa@example.com"))
        assertTrue(task.contains("stop before submission"))
    }

    @Test
    fun nativeBlindAidCommandsRouteDeterministicallyInEveryIndicLanguage() {
        val starts = listOf(
            "ब्लाइंड एड चालू करो", "ব্লাইন্ড এইড চালু করো", "பிளைண்ட் எய்டை தொடங்கு",
            "బ్లైండ్ ఎయిడ్ ప్రారంభించు", "ಬ್ಲೈಂಡ್ ಏಡ್ ಪ್ರಾರಂಭಿಸು", "ബ്ലൈൻഡ് എയ്ഡ് തുടങ്ങുക"
        )
        val stops = listOf(
            "ब्लाइंड एड बंद करो", "ব্লাইন্ড এইড বন্ধ করো", "பிளைண்ட் எய்டை நிறுத்து",
            "బ్లైండ్ ఎయిడ్ ఆపు", "ಬ್ಲೈಂಡ್ ಏಡ್ ನಿಲ್ಲಿಸು", "ബ്ലൈൻഡ് എയ്ഡ് നിർത്തുക"
        )
        starts.forEach { assertEquals(it, "detect_objects", RuleBasedParser.parse(it)?.tool) }
        stops.forEach { assertEquals(it, "deactivate_blind_aid", RuleBasedParser.parse(it)?.tool) }
    }

    @Test
    fun nativeCoreHandsFreeCommandsCoverAllSupportedIndicLanguages() {
        val cases = mapOf(
            "स्क्रीन पढ़ो" to "read_screen",
            "ক্যামেরা খোলো" to "open_camera",
            "காலெண்டரை திற" to "open_calendar",
            "వాట్సాప్ తెరువు" to "open_app",
            "ಕ್ರೋಮ್ ತೆರೆಯಿರಿ" to "open_chrome",
            "സുരക്ഷിത ബ്രൗസർ തുറക്കുക" to "secure_browser_task"
        )
        cases.forEach { (command, expectedTool) ->
            assertEquals(command, expectedTool, RuleBasedParser.parse(command)?.tool)
        }
    }

    @Test
    fun commonStreamingAsrFinalConsonantLossStillOpensCalendar() {
        assertEquals("open_calendar", RuleBasedParser.parse("open calenda")?.tool)
    }

    @Test
    fun openGmailNeverBecomesAnEmptyEmailDraft() {
        val call = RuleBasedParser.parse("open gmail")
        assertEquals("open_app", call?.tool)
        assertEquals("com.google.android.gm", call?.args?.get("package_name")?.jsonPrimitive?.content)
    }

    @Test
    fun whatsappDraftWithoutNumberUsesWhatsAppRecipientPickerPath() {
        val call = RuleBasedParser.parse("write a WhatsApp message saying I will be late")
        assertEquals("send_whatsapp", call?.tool)
        assertEquals("", call?.args?.get("number")?.jsonPrimitive?.content)
        assertEquals("I will be late", call?.args?.get("message")?.jsonPrimitive?.content)
    }

    @Test
    fun HindiCoreDraftAndCalendarCommandsStayDeterministic() {
        assertEquals(
            "send_whatsapp",
            RuleBasedParser.parse("मम्मी को व्हाट्सएप पर मैसेज लिखो कि मैं देर से आऊंगा")?.tool
        )
        assertEquals(
            "draft_email",
            RuleBasedParser.parse("ईमेल ड्राफ्ट बनाओ कि रिपोर्ट तैयार है")?.tool
        )
        assertEquals(
            "open_calendar_insert",
            RuleBasedParser.parse("कल शाम ५ बजे मीटिंग कैलेंडर में जोड़ो")?.tool
        )
    }
}
