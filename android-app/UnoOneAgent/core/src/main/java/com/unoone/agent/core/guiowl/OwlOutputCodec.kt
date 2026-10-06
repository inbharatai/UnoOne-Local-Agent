package com.unoone.agent.core.guiowl

/** Unexecuted model advice. No authority or executable strings are decoded. */
data class OwlPoint(val x: Double, val y: Double) {
    init { require(x.isFinite() && y.isFinite() && x in 0.0..1000.0 && y in 0.0..1000.0) }
}
data class OwlProposal(val action: String, val coordinate: OwlPoint? = null,
    val coordinate2: OwlPoint? = null, val text: String? = null, val time: Double? = null,
    val button: String? = null, val status: String? = null)

object OwlOutputCodec {
    /** Complete official Action + one tool_call envelope; never extracts or repairs JSON. */
    fun decode(raw: String): OwlProposal {
        require(raw.length <= 8192)
        val match = Regex("\\AAction: ([^\\r\\n<>]{1,240})\\r?\\n<tool_call>\\s*(\\{[\\s\\S]*})\\s*</tool_call>\\s*\\z").matchEntire(raw)
            ?: error("Expected one official Action/tool_call envelope")
        require(match.groupValues[1].none { it.code < 32 })
        val root = StrictJson(match.groupValues[2]).parse() as? Map<*, *> ?: error("Expected object")
        require(root.keys == setOf("name", "arguments") && root["name"] == "mobile_use")
        val args = root["arguments"] as? Map<*, *> ?: error("Expected arguments")
        val action = args["action"] as? String ?: error("Expected action")
        val fields = when (action) {
            "click" -> setOf("coordinate")
            "long_press" -> setOf("coordinate", "time")
            "swipe" -> setOf("coordinate", "coordinate2")
            "key", "type", "open", "answer", "interact" -> setOf("text")
            "wait" -> setOf("time")
            "system_button" -> setOf("button")
            "terminate" -> setOf("status")
            else -> error("Unsupported official action")
        }
        require(args.keys == fields + "action")
        fun point(key: String): OwlPoint? {
            if (key !in fields) return null
            val a = args[key] as? List<*> ?: error("Expected coordinate array")
            require(a.size == 2)
            return OwlPoint(a[0] as? Double ?: error("Numeric x required"), a[1] as? Double ?: error("Numeric y required"))
        }
        fun text(key: String): String? = if (key in fields) (args[key] as? String ?: error("String required")).also {
            require(it.length <= 2048 && it.none { c -> c.code < 32 && c != '\n' && c != '\t' })
        } else null
        val seconds = if ("time" in fields) (args["time"] as? Double ?: error("Numeric time required")).also {
            require(it.isFinite() && it in 0.0..2.0)
        } else null
        val button = text("button"); require(button == null || button in setOf("Back", "Home", "Menu", "Enter"))
        val status = text("status"); require(status == null || status in setOf("success", "failure"))
        return OwlProposal(action, point("coordinate"), point("coordinate2"), text("text"), seconds, button, status)
    }
}

/** Small bounded JSON reader: duplicate keys, coercions, nonfinite numbers and trailing data fail. */
private class StrictJson(private val s: String) {
    private var p = 0
    fun parse(): Any? = value(0).also { ws(); require(p == s.length) }
    private fun ws() { while (p < s.length && s[p] in " \t\r\n") p++ }
    private fun take(c: Char): Boolean { ws(); return if (p < s.length && s[p] == c) { p++; true } else false }
    private fun value(depth: Int): Any? {
        require(depth <= 5); ws(); require(p < s.length)
        return when (s[p]) {
            '{' -> { p++; val m = linkedMapOf<String, Any?>(); if (!take('}')) {
                do { ws(); val k = string(); require(!m.containsKey(k)); require(take(':')); m[k] = value(depth + 1) } while (take(','))
                require(take('}'))
            }; m }
            '[' -> { p++; val a = arrayListOf<Any?>(); if (!take(']')) {
                do { require(a.size < 8); a.add(value(depth + 1)) } while (take(',')); require(take(']'))
            }; a }
            '"' -> string()
            else -> {
                val start = p
                while (p < s.length && s[p] !in " ,]}\r\n\t") p++
                val token = s.substring(start, p)
                require(token.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")))
                token.toDouble().also { require(it.isFinite()) }
            }
        }
    }
    private fun string(): String {
        require(p < s.length && s[p++] == '"'); val b = StringBuilder()
        while (p < s.length) {
            val c = s[p++]; if (c == '"') return b.toString()
            require(c.code >= 32)
            if (c != '\\') b.append(c) else {
                require(p < s.length)
                b.append(when (val e = s[p++]) {
                    '"', '\\', '/' -> e; 'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                    'u' -> { require(p + 4 <= s.length); val h = s.substring(p, p + 4); require(h.all { it in "0123456789abcdefABCDEF" }); p += 4; h.toInt(16).toChar() }
                    else -> error("Invalid JSON escape")
                })
            }
        }
        error("Unterminated string")
    }
}
