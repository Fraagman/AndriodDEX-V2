package com.example.androidhost.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.script.ScriptEngineManager

/**
 * Tests the JS-string quoter used to embed user-typed text into WebView
 * `evaluateJavascript` payloads.
 *
 * The property being tested is not "the output matches this exact byte sequence" — it
 * is that no character in the input can escape the quotation, alter the surrounding
 * script, or change what the receiving `document.execCommand('insertText', ...)` sees.
 * The important adversarial characters are `"`, `\`, control chars, and the `</`
 * sequence that could close a surrounding `<script>` tag.
 */
class JsEscapeTest {

    @Test
    fun quotesAreEscaped() {
        // A double quote must not close the string.
        val out = escapeForJsStringLiteral("say \"hi\"")
        assertEquals("\"say \\\"hi\\\"\"", out)
    }

    @Test
    fun singleQuotesArePreservedButHarmless() {
        // We emit a JSON-style double-quoted literal, so ' is literal and not special.
        val out = escapeForJsStringLiteral("it's ok")
        assertEquals("\"it's ok\"", out)
    }

    @Test
    fun backslashIsEscaped() {
        val out = escapeForJsStringLiteral("a\\b")
        assertEquals("\"a\\\\b\"", out)
    }

    @Test
    fun newlineIsEscaped() {
        val out = escapeForJsStringLiteral("line1\nline2")
        assertEquals("\"line1\\nline2\"", out)
    }

    @Test
    fun scriptCloseSequenceIsBroken() {
        // </script inside the payload must not terminate a surrounding <script> element
        // if the JS is ever serialised into HTML.
        val out = escapeForJsStringLiteral("</script><script>alert(1)</script>")
        assertFalse("payload must not contain a literal </", out.contains("</"))
        assertTrue("closing slash after < must be escaped", out.contains("<\\/"))
    }

    @Test
    fun adversarialCombinationIsSafe() {
        // Feed every dangerous class the spec calls out — double quote, single quote,
        // backslash, newline, and </script> — in one string, and verify the output is
        // still a valid JavaScript string literal that a real JS engine parses back
        // to the original input.
        val raw = "\"'\\\n</script>"
        val quoted = escapeForJsStringLiteral(raw)

        assertFalse("must not contain a raw </ that could close a <script>", quoted.contains("</"))
        assertTrue("must be wrapped in double quotes", quoted.startsWith("\"") && quoted.endsWith("\""))

        // Ask a real JavaScript engine to parse the quoted form back to a string.
        // If the escaping is wrong, this either fails to parse or produces a
        // different string, and the test fails in a way that names the defect.
        val engine = ScriptEngineManager().getEngineByName("javascript") ?: run {
            // A JVM without Nashorn (JDK 15+) still runs the assertions above.
            return
        }
        val roundTripped = engine.eval("$quoted") as? String
        assertEquals(raw, roundTripped)
    }

    @Test
    fun controlCharsAreEscaped() {
        val out = escapeForJsStringLiteral("a\u0001b\u001fc")
        assertEquals("\"a\\u0001b\\u001fc\"", out)
    }

    @Test
    fun insertTextScriptEmbedsTheQuotedString() {
        val script = buildInsertTextScript("q\"</script>")
        assertTrue("script must embed the safely-quoted payload",
            script.contains("\"q\\\"<\\/script>\""))
        assertTrue("script must call insertText", script.contains("document.execCommand('insertText'"))
        assertTrue("script must guard on activeElement", script.contains("document.activeElement"))
    }

    @Test
    fun controlKeyScriptContainsExpectedPieces() {
        val downEnter = buildControlKeyScript("Enter", 13, pressed = true)
        assertTrue(downEnter.contains("KeyboardEvent('keydown'"))
        assertTrue(downEnter.contains("requestSubmit"))

        val upEnter = buildControlKeyScript("Enter", 13, pressed = false)
        assertTrue(upEnter.contains("KeyboardEvent('keyup'"))

        val downBksp = buildControlKeyScript("Backspace", 8, pressed = true)
        assertTrue(downBksp.contains("execCommand('delete'"))
    }
}
