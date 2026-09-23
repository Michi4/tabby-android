package at.websters.tabbyandroid

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test

/** Settings → About → Open-source licenses must show the full texts. */
class OssLicensesUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun licensesDialogShowsFullTexts() {
        compose.onNodeWithText("Hosts").assertIsDisplayed()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Open-source licenses").performScrollTo()
        compose.onNodeWithText("Open-source licenses").performClick()
        compose.onNodeWithText("Version 2.0, January 2004", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("JCraft", substring = true).assertCountEquals(2)
        compose.onNodeWithText("Close").performClick()
    }
}
