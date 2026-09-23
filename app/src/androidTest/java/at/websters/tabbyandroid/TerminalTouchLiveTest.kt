package at.websters.tabbyandroid

import android.app.Application
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeDown
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import at.websters.tabbyandroid.data.model.SshProfile
import at.websters.tabbyandroid.data.ssh.SshConnection
import at.websters.tabbyandroid.data.ssh.SshState
import at.websters.tabbyandroid.ui.screens.TerminalScreen
import at.websters.tabbyandroid.ui.state.ConnectionsViewModel
import at.websters.tabbyandroid.ui.state.SshKeysViewModel
import at.websters.tabbyandroid.ui.state.TerminalTabsViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Live touch verification over real SSH. A shell harness enables mouse
 * reporting (`CSI ? 1000/1006 h`) and hex-dumps everything it receives
 * (`xxd`), so taps and scrolls are observable as SGR byte sequences.
 */
@OptIn(ExperimentalTestApi::class)
class TerminalTouchLiveTest {

    @get:Rule
    val compose = createComposeRule()

    private data class LiveTab(
        val tabsVm: TerminalTabsViewModel,
        val tabId: String,
        val conn: SshConnection,
    )

    private fun openLiveTab(): LiveTab {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("ssh.host") ?: throw IllegalStateException("no ssh.host")
        val port = args.getString("ssh.port")?.toIntOrNull() ?: 22
        val user = args.getString("ssh.user") ?: throw IllegalStateException("no ssh.user")
        val password = args.getString("ssh.password") ?: throw IllegalStateException("no ssh.password")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val tabsVm = TerminalTabsViewModel(app)
        val profile = SshProfile(
            id = "terminal-touch-live",
            name = "terminal-touch-live",
            host = host,
            port = port,
            username = user,
        )
        val tabId = tabsVm.open(profile)
        val conn = tabsVm.tabs.value.first { it.id == tabId }.conn as SshConnection
        val r = runBlocking { conn.connect(password = password, acceptHostKey = true) }
        assertTrue("connect failed: ${r.exceptionOrNull()}", r.isSuccess)
        compose.setContent {
            TerminalScreen(tabsVm, ConnectionsViewModel(app), SshKeysViewModel(app))
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("terminal_sender").fetchSemanticsNodes().isNotEmpty()
        }
        Thread.sleep(3_000)
        assertEquals(SshState.CONNECTED, conn.state.value)
        return LiveTab(tabsVm, tabId, conn)
    }

    private fun sendLine(conn: SshConnection, text: String) {
        text.forEach { conn.send(it.toString()) }
        conn.send("\r")
    }

    private fun waitFor(buffer: () -> String, needle: String, what: String) {
        // Byte dumps (od) wrap mid-sequence: match whitespace-insensitively.
        val cleanNeedle = needle.filterNot { it.isWhitespace() }
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end) {
            if (buffer().filterNot { it.isWhitespace() }.contains(cleanNeedle)) return
            Thread.sleep(300)
        }
        throw AssertionError("$what: '$needle' not visible; tail=${buffer().takeLast(400)}")
    }

    private fun waitForState(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(300)
        }
        throw AssertionError("$what: condition not met")
    }

    @Test
    fun tapSendsSgrClickToMouseApp() {
        val (tabsVm, tabId, conn) = openLiveTab()
        try {
            // Harness: raw-ish stdin (bytes available immediately, no echo
            // mush) + SGR mouse reporting + hex dump. Canonical mode would
            // hold binary bytes back until a newline — useless for asserts.
            sendLine(conn, "stty -icanon -echo; printf '\\033[?1000h\\033[?1006h'; od -A n -t x1")
            waitForState("mouse mode on") { conn.buffer.mouseTracking == 1000 }
            assertTrue(conn.buffer.mouseSgrEncoding)
            // Tap near the bottom (the live screen, below any history): a
            // press+release pair => "1b5b3c30" .. "1b5b3c33".
            compose.onNodeWithTag("terminal_view").performTouchInput {
                click(Offset(centerX, height * 0.9f))
            }
            Log.i(
                "TabbyTouchTest",
                "post-tap state=${conn.state.value} len=${conn.buffer.visibleText().length} " +
                    "tracking=${conn.buffer.mouseTracking} sgr=${conn.buffer.mouseSgrEncoding} " +
                    "alt=${conn.buffer.altScreen} tail=${conn.buffer.visibleText().takeLast(200)}",
            )
            waitFor({ conn.buffer.visibleText() }, "1b5b3c30", "SGR press")
            val vis = conn.buffer.visibleText()
            Log.i("TabbyTouchTest", "tap dump tail=${vis.takeLast(300)}")
            assertTrue("no SGR release in tail", vis.filterNot { it.isWhitespace() }.contains("1b5b3c33"))
        } finally {
            runCatching { tabsVm.close(tabId) }
        }
    }

    @Test
    fun scrollSendsWheelToAltScreenMouseApp() {
        val (tabsVm, tabId, conn) = openLiveTab()
        try {
            // Alt screen + raw stdin + mouse mode + hex dump (wheel needs
            // the alt screen; raw stdin so od sees bytes immediately).
            sendLine(conn, "stty -icanon -echo; printf '\\033[?1049h\\033[?1000h\\033[?1006h'; od -A n -t x1")
            waitForState("alt screen on") { conn.buffer.altScreen }
            waitForState("mouse mode on") { conn.buffer.mouseTracking == 1000 }
            // Drag down = wheel up ("[<64;"), drag up = wheel down ("[<65;").
            compose.onNodeWithTag("terminal_view").performTouchInput { swipeDown() }
            Log.i(
                "TabbyTouchTest",
                "post-swipe-down state=${conn.state.value} len=${conn.buffer.visibleText().length} " +
                    "tail=${conn.buffer.visibleText().takeLast(200)}",
            )
            waitFor({ conn.buffer.visibleText() }, "1b5b3c3634", "wheel up")
            compose.onNodeWithTag("terminal_view").performTouchInput { swipeUp() }
            waitFor({ conn.buffer.visibleText() }, "1b5b3c3635", "wheel down")
            Log.i("TabbyTouchTest", "wheel dump tail=${conn.buffer.visibleText().takeLast(300)}")
        } finally {
            runCatching { tabsVm.close(tabId) }
        }
    }
}
