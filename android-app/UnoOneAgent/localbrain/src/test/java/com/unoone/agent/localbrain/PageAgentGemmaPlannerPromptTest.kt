package com.unoone.agent.localbrain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageAgentGemmaPlannerPromptTest {

    private val planner = PageAgentGemmaPlanner()

    @Test
    fun `large upstream prompts are replaced by a bounded on-device prompt`() {
        val page = "TASK_AT_HEAD\n" + "middle-context\n".repeat(2_000) + "DOM_AT_TAIL"
        val prompt = planner.buildPrompt(
            pageAgentSystemPrompt = "UPSTREAM_SYSTEM_MARKER".repeat(2_000),
            pageAgentUserPrompt = page,
            macroToolSchemaJson = "UPSTREAM_SCHEMA_MARKER".repeat(2_000),
            maxOutputTokens = 512
        )

        assertTrue(prompt.length < 8_500)
        assertTrue(prompt.contains("TASK_AT_HEAD"))
        assertTrue(prompt.contains("DOM_AT_TAIL"))
        assertTrue(prompt.contains("older middle context omitted"))
        assertFalse(prompt.contains("UPSTREAM_SYSTEM_MARKER"))
        assertFalse(prompt.contains("UPSTREAM_SCHEMA_MARKER"))
    }

    @Test
    fun `compact schema retains every native supported action`() {
        val prompt = planner.buildPrompt("", "task and DOM", "", 512)

        PageAgentGemmaPlanner.ALLOWED_ACTIONS.forEach { action ->
            assertTrue("missing action $action", prompt.contains(action))
        }
        assertTrue(prompt.contains("submit_form{index,purpose}"))
        assertTrue(prompt.contains("upload_file{index,purpose}"))
        assertFalse(prompt.contains("execute_javascript{"))
    }

    @Test
    fun `lenient parser accepts unquoted keys from the on-device model`() {
        val result = planner.parseAndValidate(
            """
            {
              evaluation_previous_goal: "Ready",
              memory: "",
              next_goal: "Fill the name",
              action: { input_text: { index: 1, text: "Reeturaj" } }
            }
            """.trimIndent()
        )

        assertTrue(result is com.unoone.agent.core.model.Result.Success)
        val plan = (result as com.unoone.agent.core.model.Result.Success).data
        assertEquals("input_text", plan.actionName)
        assertTrue(plan.actionArgumentsJson.contains("Reeturaj"))
    }

    @Test
    fun `parser repairs missing opening quote on an allowlisted action key`() {
        val result = planner.parseAndValidate(
            """
            {
              "evaluation_previous_goal": "Ready",
              "memory": "",
              "next_goal": "Fill the name",
              "action": { input_text": { "index": 1, "text": "Reeturaj" } }
            }
            """.trimIndent()
        )

        assertTrue(result is com.unoone.agent.core.model.Result.Success)
        assertEquals(
            "input_text",
            (result as com.unoone.agent.core.model.Result.Success).data.actionName
        )
    }

    @Test
    fun `parser recovers one valid allowlisted action from a malformed envelope`() {
        val result = planner.parseAndValidate(
            """
            {
              "evaluation_previous_goal": "The previous field is correct"
              "memory": "missing comma above",
              "next_goal": "Fill email",
              "action": { "input_text": { "index": 3, "text": "qa@example.com" } }
            }
            """.trimIndent()
        )

        assertTrue(result is com.unoone.agent.core.model.Result.Success)
        val plan = (result as com.unoone.agent.core.model.Result.Success).data
        assertEquals("input_text", plan.actionName)
        assertTrue(plan.actionArgumentsJson.contains("qa@example.com"))
    }

    @Test
    fun `parser recovers typed action when final string quote is missing`() {
        val result = planner.parseAndValidate(
            """
            {
              "evaluation_previous_goal": "Ready",
              "memory": "",
              "next_goal": "Fill name",
              "action": { "input_text": { "index": 1, "text": "Reeturaj}}}
            """.trimIndent()
        )

        assertTrue(result is com.unoone.agent.core.model.Result.Success)
        val plan = (result as com.unoone.agent.core.model.Result.Success).data
        assertEquals("input_text", plan.actionName)
        assertEquals("""{"index":1,"text":"Reeturaj"}""", plan.actionArgumentsJson)
    }

    @Test
    fun `parser normalizes flattened indexed action emitted by small model`() {
        val result = planner.parseAndValidate(
            """
            {
              "evaluation_previous_goal": "Name filled",
              "memory": "Name is complete",
              "next_goal": "Fill email",
              "action": { "input_text": [3], "text": "qa@example.com" }
            }
            """.trimIndent()
        )

        assertTrue(result is com.unoone.agent.core.model.Result.Success)
        val plan = (result as com.unoone.agent.core.model.Result.Success).data
        assertEquals("input_text", plan.actionName)
        assertEquals("""{"text":"qa@example.com","index":3}""", plan.actionArgumentsJson)
    }

    @Test
    fun `parser accepts one allowlisted typed tool envelope emitted on device`() {
        val result = planner.parseAndValidate(
            """
            {
              "evaluation_previous_goal": "Opened the page",
              "memory": "Page is at the top",
              "next_goal": "Read more of the visible page",
              "action": {
                "action_name": "scroll",
                "arguments": {"down": true, "num_pages": 1, "pixels": 500, "index": 0}
              }
            }
            """.trimIndent()
        )

        assertTrue(result is com.unoone.agent.core.model.Result.Success)
        val plan = (result as com.unoone.agent.core.model.Result.Success).data
        assertEquals("scroll", plan.actionName)
        assertEquals(
            """{"down":true,"num_pages":1,"pixels":500,"index":0}""",
            plan.actionArgumentsJson
        )
    }

    @Test
    fun `typed tool envelope rejects unknown action`() {
        val result = planner.parseAndValidate(
            """
            {"action":{"action_name":"execute_javascript","arguments":{"code":"hidden()"}}}
            """.trimIndent()
        )

        assertTrue(result is com.unoone.agent.core.model.Result.Error)
    }

    @Test
    fun `parser recovers typed action despite malformed outer braces`() {
        val result = planner.parseAndValidate(
            """
            {"evaluation_previous_goal":"bad envelope","action":
              {"input_text":{"index":3,"text":"qa@example.com"}}}}
            """.trimIndent()
        )

        assertTrue(result is com.unoone.agent.core.model.Result.Success)
        val plan = (result as com.unoone.agent.core.model.Result.Success).data
        assertEquals("input_text", plan.actionName)
        assertEquals("""{"index":3,"text":"qa@example.com"}""", plan.actionArgumentsJson)
    }

    @Test
    fun `parser normalizes literal LiteRT quote markers`() {
        val result = planner.parseAndValidate(
            """
            {"evaluation_previous_goal":"Name complete","memory":"","next_goal":"Email",
             "action":{"input_text":{"index":3,"text":<|"|>qa@example.com<|"|>}}}
            """.trimIndent()
        )

        assertTrue(result is com.unoone.agent.core.model.Result.Success)
        val plan = (result as com.unoone.agent.core.model.Result.Success).data
        assertEquals("input_text", plan.actionName)
        assertEquals("""{"index":3,"text":"qa@example.com"}""", plan.actionArgumentsJson)
    }

    @Test
    fun `parser normalizes escaped string delimiters outside JSON strings`() {
        val result = planner.parseAndValidate(
            """
            {"evaluation_previous_goal":"Name complete","memory":"","next_goal":"Email",
             "action":{"input_text":{"index":3,"text":\"qa@example.com\"}}}
            """.trimIndent()
        )

        assertTrue(result is com.unoone.agent.core.model.Result.Success)
        val plan = (result as com.unoone.agent.core.model.Result.Success).data
        assertEquals("input_text", plan.actionName)
        assertEquals("""{"index":3,"text":"qa@example.com"}""", plan.actionArgumentsJson)
    }

    @Test
    fun `parser normalizes positional action arguments`() {
        val result = planner.parseAndValidate(
            """
            {"evaluation_previous_goal":"Name complete","memory":"","next_goal":"Email",
             "action":{"input_text":[3,"qa@example.com"]}}
            """.trimIndent()
        )

        assertTrue(result is com.unoone.agent.core.model.Result.Success)
        val plan = (result as com.unoone.agent.core.model.Result.Success).data
        assertEquals("input_text", plan.actionName)
        assertEquals("""{"index":3,"text":"qa@example.com"}""", plan.actionArgumentsJson)
    }

    @Test
    fun `parser recovers unterminated positional action array`() {
        val result = planner.parseAndValidate(
            """
            {"evaluation_previous_goal":"Name email and date complete","memory":"Country empty",
             "next_goal":"Select country","action":{"select_dropdown_option":[3,"India"}}
            """.trimIndent()
        )

        assertTrue(result is com.unoone.agent.core.model.Result.Success)
        val plan = (result as com.unoone.agent.core.model.Result.Success).data
        assertEquals("select_dropdown_option", plan.actionName)
        assertEquals("""{"index":3,"text":"India"}""", plan.actionArgumentsJson)
    }

    @Test
    fun `planner recovers explicit customer name when model omits input text value`() {
        val result = planner.recoverIncompleteInputAction(
            """
            {"evaluation_previous_goal":"Ready","memory":"","next_goal":"Fill name",
             "action":{"input_text":{"index":0,"text":}}}
            """.trimIndent(),
            """
            <user_request>
            fill customer name Reetu, telephone 1234567890, email qa@example.com
            </user_request>
            <browser_state>
            [0]<input name="custname" type="text" />
            [1]<input name="custtel" type="tel" />
            [2]<input name="custemail" type="email" />
            </browser_state>
            """.trimIndent()
        )

        assertTrue(result is com.unoone.agent.core.model.Result.Success)
        val plan = (result as com.unoone.agent.core.model.Result.Success).data
        assertEquals("input_text", plan.actionName)
        assertEquals("""{"index":0,"text":"Reetu"}""", plan.actionArgumentsJson)
    }

    @Test
    fun `planner recovers explicit email but never guesses an unrelated field`() {
        val raw = """{"action":{"input_text":{"index":2,"text":}}}"""
        val page = """
            <user_request>fill email qa@example.com</user_request>
            [2]<input name="custemail" type="email" />
        """.trimIndent()

        val recovered = planner.recoverIncompleteInputAction(raw, page)
        assertTrue(recovered is com.unoone.agent.core.model.Result.Success)
        assertEquals(
            """{"index":2,"text":"qa@example.com"}""",
            (recovered as com.unoone.agent.core.model.Result.Success).data.actionArgumentsJson
        )

        val unknown = planner.recoverIncompleteInputAction(
            """{"action":{"input_text":{"index":4,"text":}}}""",
            """
            <user_request>fill the reference</user_request>
            [4]<input name="reference" type="text" />
            """.trimIndent()
        )
        assertEquals(null, unknown)
    }

    @Test
    fun `planner repairs positional index inside malformed typed action object`() {
        val result = planner.recoverIncompleteInputAction(
            """{"action":{"input_text":{0,"text":"Reetu"}}}""",
            """
            <user_request>fill customer name Reetu</user_request>
            [0]<input name="custname" type="text" />
            """.trimIndent()
        )

        assertTrue(result is com.unoone.agent.core.model.Result.Success)
        assertEquals(
            """{"index":0,"text":"Reetu"}""",
            (result as com.unoone.agent.core.model.Result.Success).data.actionArgumentsJson
        )

        val decoded = planner.parseAndValidate(
            """{"action":{"input_text":{0,"text":"Reetu"}}}"""
        )
        assertTrue(decoded is com.unoone.agent.core.model.Result.Success)
        assertEquals(
            """{"index":0,"text":"Reetu"}""",
            (decoded as com.unoone.agent.core.model.Result.Success).data.actionArgumentsJson
        )
    }

    @Test
    fun `parser preserves unquoted numeric text in positional input action`() {
        val result = planner.parseAndValidate(
            """
            {"evaluation_previous_goal":"malformed envelope
             "action":{"input_text":[1,1234567890]}}
            """.trimIndent()
        )

        assertTrue(result is com.unoone.agent.core.model.Result.Success)
        assertEquals(
            """{"index":1,"text":"1234567890"}""",
            (result as com.unoone.agent.core.model.Result.Success).data.actionArgumentsJson
        )
    }

    @Test
    fun `canonical typed action missing required index is rejected`() {
        val result = planner.parseAndValidate(
            """{"action":{"input_text":{"text":"Medium"}}}"""
        )

        assertTrue(result is com.unoone.agent.core.model.Result.Error)
    }

    @Test
    fun `planner maps explicitly requested option to one unambiguous select`() {
        val result = planner.recoverIncompleteInputAction(
            """{"action":{"input_text":{"text":"Medium"}}}""",
            """
            <user_request>select Medium, check bacon and stop before submission</user_request>
            [4]<select name="size">Small Medium Large</select>
            """.trimIndent()
        )

        assertTrue(result is com.unoone.agent.core.model.Result.Success)
        val plan = (result as com.unoone.agent.core.model.Result.Success).data
        assertEquals("select_dropdown_option", plan.actionName)
        assertEquals("""{"index":4,"text":"Medium"}""", plan.actionArgumentsJson)
    }

    @Test
    fun `planner recovers explicit standard text input and textarea values`() {
        val request = """
            <user_request>
            fill text input UnoOne Test, textarea Offline Page Agent, select Two
            </user_request>
        """.trimIndent()

        val textInput = planner.recoverIncompleteInputAction(
            """{"action":{"input_text":{"index":0,"text":UnoOne Test}}}""",
            "$request\n[0]<input name=\"my-text\" type=\"text\" />"
        )
        val textArea = planner.recoverIncompleteInputAction(
            """{"action":{"input_text":{"index":3,"text":Offline Page Agent}}}""",
            "$request\n[3]<textarea name=\"my-textarea\"></textarea>"
        )

        assertEquals(
            """{"index":0,"text":"UnoOne Test"}""",
            (textInput as com.unoone.agent.core.model.Result.Success).data.actionArgumentsJson
        )
        assertEquals(
            """{"index":3,"text":"Offline Page Agent"}""",
            (textArea as com.unoone.agent.core.model.Result.Success).data.actionArgumentsJson
        )
    }

    @Test
    fun `DOM correction converts text input action on select to dropdown action`() {
        val plan = PageAgentPlan("", "", "", "input_text", """{"index":3,"text":"India"}""")

        val corrected = planner.correctActionForDom(
            plan,
            """
            <browser_state>
            [2]<input id="birth-date" type="date" value="1990-01-02">
            [3]<select id="country"><option>India</option></select>
            </browser_state>
            """.trimIndent()
        )

        assertEquals("select_dropdown_option", corrected.actionName)
        assertEquals("""{"index":3,"text":"India"}""", corrected.actionArgumentsJson)
    }

    @Test
    fun `DOM correction converts click actions for checkbox and radio`() {
        val checkbox = planner.correctActionForDom(
            PageAgentPlan("", "", "", "click_element_by_index", """{"index":4}"""),
            """[4]<input id="updates" type="checkbox">"""
        )
        val radio = planner.correctActionForDom(
            PageAgentPlan("", "", "", "click_element_by_index", """{"index":5}"""),
            """[5]<input type="radio" name="plan">"""
        )

        assertEquals("toggle_checkbox", checkbox.actionName)
        assertEquals("choose_radio", radio.actionName)
    }

    @Test
    fun `DOM correction remaps typed action to one unambiguous matching control`() {
        val page = """
            [0]<input id="full-name" name="fullName" />
            [1]<input id="email" name="email" type="email" />
            [2]<input id="birth-date" name="birthDate" type="date" />
            *[3]<select id="country" name="country">Choose a country />
            [4]<input id="updates" name="updates" type="checkbox" />
        """.trimIndent()

        val select = planner.correctActionForDom(
            PageAgentPlan("", "", "", "select_dropdown_option", """{"index":1,"text":"India"}"""),
            page
        )
        val checkbox = planner.correctActionForDom(
            PageAgentPlan("", "", "", "toggle_checkbox", """{"index":2}"""),
            page
        )

        assertEquals("""{"index":3,"text":"India"}""", select.actionArgumentsJson)
        assertEquals("""{"index":4}""", checkbox.actionArgumentsJson)
    }

    @Test
    fun `DOM correction never guesses between multiple matching controls`() {
        val corrected = planner.correctActionForDom(
            PageAgentPlan("", "", "", "select_dropdown_option", """{"index":0,"text":"India"}"""),
            """
            [0]<input id="query" />
            [1]<select id="country">Choose a country />
            [2]<select id="state">Choose a state />
            """.trimIndent()
        )

        assertEquals("""{"index":0,"text":"India"}""", corrected.actionArgumentsJson)
    }

    @Test
    fun `verified form progress advances from completed select to requested checkbox then done`() {
        val request = """
            <user_request>
            Select country India and enable product updates and stop before submission
            </user_request>
        """.trimIndent()
        val uncheckedPage = """
            $request
            [7]<select id="country" name="country">India />
            [8]<input id="updates" name="updates" type="checkbox" />
        """.trimIndent()
        val checkedPage = uncheckedPage.replace(
            """type="checkbox"""",
            """type="checkbox" checked=true"""
        )
        val repeated = PageAgentPlan(
            "", "", "", "select_dropdown_option", """{"index":7,"text":"India"}"""
        )

        val checkbox = planner.correctActionForDom(repeated, uncheckedPage)
        val done = planner.correctActionForDom(repeated, checkedPage)

        assertEquals("toggle_checkbox", checkbox.actionName)
        assertEquals("""{"index":8}""", checkbox.actionArgumentsJson)
        assertEquals("done", done.actionName)
        assertEquals(
            """{"text":"Requested form fields are filled. The form was not submitted.","success":true}""",
            done.actionArgumentsJson
        )
    }

    @Test
    fun `verified form progress does not treat checked false as checked`() {
        val page = """
            <user_request>
            Select country India and enable updates and stop before submission
            </user_request>
            [7]<select id=country>India />
            [9]<input id=updates type=checkbox checked=false />
        """.trimIndent()
        val repeated = PageAgentPlan(
            "", "", "", "select_dropdown_option", """{"index":7,"text":"India"}"""
        )

        val corrected = planner.correctActionForDom(repeated, page)

        assertEquals("toggle_checkbox", corrected.actionName)
        assertEquals("""{"index":9}""", corrected.actionArgumentsJson)
    }
}
