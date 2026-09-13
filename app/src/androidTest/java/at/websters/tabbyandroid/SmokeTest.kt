package at.websters.tabbyandroid

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

/**
 * On-device smoke (runs via connectedDebugAndroidTest on a real phone):
 * launch → Hosts → Terminal → open the demo shell (needs no server) and
 * assert it comes up. Touches nav, dialogs, tab creation and the local shell.
 */
class SmokeTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchOpenDemoShell() {
        compose.onNodeWithText("Hosts").assertIsDisplayed()
        compose.onNodeWithText("Terminal").performClick()
        compose.onNodeWithContentDescription("New tab").performClick()
        compose.onNodeWithText("Try the demo shell (no server)").performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodes(hasTextContainingCompat("Demo shell")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Esc", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun hasTextContainingCompat(substring: String): androidx.compose.ui.test.SemanticsMatcher =
        androidx.compose.ui.test.hasText(substring, substring = true)
}
