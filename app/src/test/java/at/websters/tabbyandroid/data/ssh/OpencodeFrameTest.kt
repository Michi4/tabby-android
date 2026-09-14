package at.websters.tabbyandroid.data.ssh

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test with a real captured opencode static frame
 * (`opencode-frame.bin`: probes + full welcome UI incl. OSC/DCS/APC queries).
 * Feeds it in odd chunks (split-sequence carry) and asserts the frame lands
 * intact: logo, input box, model line, footer. Proves the BUFFER side of the
 * 1.4.1 rendering investigation (the visible corruption came from Compose
 * soft-wrapping, fixed with softWrap=false).
 */
class OpencodeFrameTest {
    private fun replay(): TerminalBuffer {
        val stream: java.io.InputStream =
            this::class.java.getResourceAsStream("/opencode-frame.bin")
                ?: this::class.java.classLoader!!.getResourceAsStream("opencode-frame.bin")!!
        val data = stream.readBytes()
        val b = TerminalBuffer(cols = 80, rows = 24)
        var i = 0
        var step = 7
        while (i < data.size) {
            val n = minOf(step, data.size - i)
            b.feed(data, i, n)
            i += n
            step = (step * 13 % 511) + 1
        }
        return b
    }

    @Test fun logoLandsIntact() {
        val t = replay().visibleText()
        assertTrue(t.contains("Ask anything"))
        assertTrue(t.contains("1.18.29"))
        assertTrue(t.contains("tab ") && t.contains("agents"))
    }

    @Test fun noProbeLeaksIntoText() {
        val t = replay().visibleText()
        for (leak in listOf("Gi=", "?2027", "?2004", "opentui-notifications", "+q4d73")) {
            assertTrue("probe leaked: $leak", !t.contains(leak))
        }
    }
}
