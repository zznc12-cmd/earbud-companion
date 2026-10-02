package app.earbud.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

/** Run on a connected test device with :app:connectedDebugAndroidTest. */
class ScreenTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun permissionsAndUnverifiedBannerStateAreVisible() {
        rule.onNodeWithText("이어폰 도우미").assertIsDisplayed()
        rule.onNodeWithText("검증").performClick()
        rule.onNodeWithText("실기기 검증 전 · 미리보기 버전").assertIsDisplayed()
        rule.onNodeWithText("알림 치우기").assertIsDisplayed()
    }
    @Test fun notificationAndAudioSettingsAreReachable() {
        rule.onNodeWithText("알림").performClick()
        rule.onNodeWithText("발신자만 읽기").assertIsDisplayed()
        rule.onNodeWithText("소리").performClick()
        rule.onNodeWithText("낭독 소리").assertIsDisplayed()
    }
}
