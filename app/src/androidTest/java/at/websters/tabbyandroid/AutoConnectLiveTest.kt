package at.websters.tabbyandroid

import android.app.Application
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
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
 * Tapping play with presaved auth must connect automatically: no second tap
 * on Connect, no password dialog. The tab opens DISCONNECTED and the screen
 * auto-connects once from encrypted storage.
 */
@OptIn(ExperimentalTestApi::class)
class AutoConnectLiveTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun savedPasswordConnectsWithoutTaps() {
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
            id = "terminal-autoconnect-live",
            name = "terminal-autoconnect-live",
            host = host,
            port = port,
            username = user,
        )
        // Seed the host-key pin like a long-ago accepted dialog did, so the
        // auto attempt is not gated on user acceptance (tested elsewhere).
        runBlocking {
            val seed = tabsVm.open(profile)
            val seedConn = tabsVm.tabs.value.first { it.id == seed }.conn as SshConnection
            val r = seedConn.connect(password = password, acceptHostKey = true)
            assertTrue("seed connect failed: ${r.exceptionOrNull()}", r.isSuccess)
            tabsVm.close(seed)
            // Presaved password in encrypted storage (the user's setup).
            connectionsVm.savePassword(profile.id, password)
        }
        // Fresh tab, never touched: the play-button equivalent.
        val tabId = tabsVm.open(profile)
        val conn = tabsVm.tabs.value.first { it.id == tabId }.conn as SshConnection
        assertEquals(SshState.DISCONNECTED, conn.state.value)
        try {
            compose.setContent {
                TerminalScreen(tabsVm, connectionsVm, keysVm)
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag("terminal_sender").fetchSemanticsNodes().isNotEmpty()
            }
            // Zero taps: the screen must reach CONNECTED by itself.
            compose.waitUntil(timeoutMillis = 20_000) {
                conn.state.value == SshState.CONNECTED
            }
            assertEquals(SshState.CONNECTED, conn.state.value)
            compose.onNodeWithTag("terminal_sender").assertIsDisplayed()
            // …and no auth UI should be asking for anything.
            assertTrue(
                compose.onAllNodesWithText("Password", substring = true).fetchSemanticsNodes().isEmpty(),
            )
        } finally {
            runCatching { tabsVm.close(tabId) }
        }
    }
}
