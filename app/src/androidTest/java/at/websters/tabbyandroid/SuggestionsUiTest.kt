package at.websters.tabbyandroid

import android.app.Application
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import at.websters.tabbyandroid.ui.screens.TerminalScreen
import at.websters.tabbyandroid.ui.state.ConnectionsViewModel
import at.websters.tabbyandroid.ui.state.SshKeysViewModel
import at.websters.tabbyandroid.ui.state.TerminalTabsViewModel
import org.junit.Rule
import org.junit.Test

/**
 * The suggestion bar must take ZERO space when empty (it used to reserve a
 * fixed 36dp strip) and obey the per-tab toolbar toggle.
 */
@OptIn(ExperimentalTestApi::class)
class SuggestionsUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun suggestionBarCollapsesWhenEmptyAndObeysToggle() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val tabsVm = TerminalTabsViewModel(app)
        tabsVm.recordCommand("echo hello-sugg-123")
        tabsVm.openDemoShell()
        compose.setContent {
            TerminalScreen(tabsVm, ConnectionsViewModel(app), SshKeysViewModel(app))
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("terminal_sender").fetchSemanticsNodes().isNotEmpty()
        }
        // Empty line: no bar, no reserved space.
        assertGone()
        // Typing a prefix with history behind it: bar appears.
        compose.onNodeWithTag("terminal_sender").performClick()
        compose.onNodeWithTag("terminal_sender").performTextInput("ec")
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("suggestion_bar").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("suggestion_bar").assertIsDisplayed()
        // Toolbar toggle hides it even with a live prefix…
        compose.onNodeWithTag("suggestion_toggle").performClick()
        assertGone()
        // …and brings it back.
        compose.onNodeWithTag("suggestion_toggle").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("suggestion_bar").fetchSemanticsNodes().isNotEmpty()
        }
        // Clearing the line hides it again (zero space, not an empty strip).
        compose.onNodeWithTag("terminal_sender").performTextClearance()
        assertGone()
    }

    private fun assertGone() {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("suggestion_bar").fetchSemanticsNodes().isEmpty()
        }
    }
}
