package at.websters.tabbyandroid

import android.app.Application
import android.util.Log
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
 * Live repro for the user-visible resize corruption: toggling the soft
 * keyboard (viewport rows shrink/grow + SIGWINCH each way) duplicates shell
 * prompts and cuts the first character of lines (`home@home` → `ome@home`).
 */
@OptIn(ExperimentalTestApi::class)
class TerminalResizeLiveTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun keyboardSizeResizesDoNotDuplicatePrompt() {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("ssh.host") ?: return
        val port = args.getString("ssh.port")?.toIntOrNull() ?: 22
        val user = args.getString("ssh.user") ?: return
        val password = args.getString("ssh.password") ?: return
        val app = ApplicationProvider.getApplicationContext<Application>()
        val tabsVm = TerminalTabsViewModel(app)
        val connectionsVm = ConnectionsViewModel(app)
        val keysVm = SshKeysViewModel(app)
        val profile = SshProfile(
            id = "terminal-resize-live",
            name = "terminal-resize-live",
            host = host,
            port = port,
            username = user,
        )
        val tabId = tabsVm.open(profile)
        val tab = tabsVm.tabs.value.first { it.id == tabId }
        val conn = tab.conn as SshConnection
        try {
            val r = runBlocking { conn.connect(password = password, acceptHostKey = true) }
            assertTrue("connect failed: ${r.exceptionOrNull()}", r.isSuccess)
            compose.setContent {
                TerminalScreen(tabsVm, connectionsVm, keysVm)
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag("terminal_sender").fetchSemanticsNodes().isNotEmpty()
            }
            // Let the shell print its first prompt at the measured viewport.
            Thread.sleep(4_000)
            assertEquals(SshState.CONNECTED, conn.state.value)
            val cols = conn.buffer.cols
            val rows = conn.buffer.rows
            Log.i("TabbyResizeTest", "viewport cols=$cols rows=$rows")
            assertTrue("viewport too small to simulate keyboard: cols=$cols rows=$rows", rows > 16)

            val promptsBefore = promptLines(conn.buffer.visibleText())
            // Three keyboard open/close cycles: shrink rows, let SIGWINCH +
            // readline redraw settle, restore.
            repeat(3) {
                conn.buffer.resize(cols, rows - 12)
                conn.setPtySize(cols, rows - 12)
                Thread.sleep(1_500)
                conn.buffer.resize(cols, rows)
                conn.setPtySize(cols, rows)
                Thread.sleep(1_500)
            }
            Thread.sleep(2_000)
            assertEquals("resize dropped the session", SshState.CONNECTED, conn.state.value)
            val after = conn.buffer.visibleText()
            val promptsAfter = promptLines(after)
            Log.i(
                "TabbyResizeTest",
                "prompts before=$promptsBefore after=$promptsAfter\n---AFTER---\n$after\n---END---",
            )
            assertTrue(
                "keyboard resizes duplicated prompts ($promptsBefore -> $promptsAfter)",
                promptsAfter <= promptsBefore + 2,
            )
            val truncated = after.lines().filter { it.startsWith("ome@") || it.startsWith("me@home") }
            assertTrue("resized lines lost their first character: $truncated", truncated.isEmpty())
        } finally {
            runCatching { tabsVm.close(tabId) }
        }
    }

    private fun promptLines(visible: String): Int =
        visible.lines().count { it.trimEnd().endsWith("$") || it.trimEnd().endsWith("#") }
}
