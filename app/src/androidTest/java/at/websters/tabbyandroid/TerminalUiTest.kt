package at.websters.tabbyandroid

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class TerminalUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun demoShellEchoesTypedAscii() {
        openDemoShell()
        sendLine("echo UI_ASCII_OK")
        waitForTerminalText("UI_ASCII_OK")
    }

    @Test
    fun demoShellEchoesTypedUnicode() {
        openDemoShell()
        sendLine("echo ÄÖÜ")
        waitForTerminalText("ÄÖÜ")
    }

    private fun sendLine(text: String) {
        val sender = compose.onNodeWithTag("terminal_sender")
        sender.performClick()
        sender.performTextInput(text)
        sender.performKeyInput { pressKey(Key.Enter) }
    }

    private fun openDemoShell() {
        compose.onNodeWithText("Hosts").assertIsDisplayed()
        compose.onNodeWithText("Terminal").performClick()
        if (compose.onAllNodesWithText("Demo shell", substring = true)
                .fetchSemanticsNodes().isEmpty()
        ) {
            compose.onNodeWithContentDescription("New tab").performClick()
            compose.onNodeWithText("Try the demo shell (no server)").performClick()
        }
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("Demo shell", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        waitForTerminalText("Local demo shell")
    }

    private fun waitForTerminalText(needle: String) {
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText(needle, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
