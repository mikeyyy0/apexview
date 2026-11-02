package app.apexview

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What a remote's arrows reach on a page laid out the way home is. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television", sdk = [34])
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class RemoteFocusTest {
    @get:Rule val rule = createComposeRule()

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun assertFocus(tag: String) = rule.onNodeWithTag(tag).assertIsFocused()

    @Test
    fun theSeekBarMovesOnArrowsAndSeeksOnSelect() {
        val moves = mutableListOf<Long>()
        var done = 0
        val bar = FocusRequester()
        rule.setContent {
            val input = LocalInputModeManager.current
            LaunchedEffect(Unit) { input.requestInputMode(InputMode.Keyboard) }
            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .testTag("bar")
                        .focusRequester(bar)
                        .remoteScrub(remember { MutableInteractionSource() }, { moves += it }, { done++ })
                        .fillMaxWidth()
                        .height(40.dp)
                )
                SecondaryButton("Play", Modifier.testTag("play")) {}
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { bar.requestFocus() }
        rule.waitForIdle()
        assertFocus("bar")

        press(Key.DirectionRight)
        press(Key.DirectionLeft)
        assertFocus("bar")
        assertEquals(listOf(30_000L, -30_000L), moves)
        val before = done
        press(Key.DirectionCenter)
        assertEquals(before + 1, done)

        press(Key.DirectionDown)
        assertFocus("play")
        // Moving away seeks too, in case the presses hadn't settled yet.
        assertEquals(before + 2, done)
        press(Key.DirectionUp)
        assertFocus("bar")
    }

    @Test
    fun holdingAnArrowSpeedsTheSeekBarUp() {
        assertEquals(30_000L, remoteScrubStepMs(0))
        assertEquals(10_000L, remoteScrubStepMs(1))
        assertEquals(30_000L, remoteScrubStepMs(20))
        assertEquals(60_000L, remoteScrubStepMs(60))
    }

    @Test
    fun theArrowsLeaveTheTopBarAndComeBack() {
        val first = FocusRequester()
        rule.setContent {
            val input = LocalInputModeManager.current
            LaunchedEffect(Unit) { input.requestInputMode(InputMode.Keyboard) }
            val pager = rememberPagerState(initialPage = 1500) { 3000 }
            ArtPage(
                image = null,
                artKey = null,
                title = "Apexview",
                listState = rememberLazyListState(),
                trailing = { backdrop ->
                    Row {
                        GlyphButton(Icons.Rounded.Settings, "Calendar", Modifier.testTag("calendar").focusRequester(first), backdrop) {}
                        GlyphButton(Icons.Rounded.Settings, "Settings", Modifier.testTag("settings"), backdrop) {}
                    }
                },
                heroBox = {
                    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize()) {
                            Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                                PrimaryButton("Play", modifier = Modifier.testTag("play")) {}
                            }
                        }
                    }
                },
            ) {
                item {
                    Box(Modifier.fillMaxWidth().padding(20.dp)) {
                        SecondaryButton("Row", Modifier.testTag("row")) {}
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { first.requestFocus() }
        rule.waitForIdle()

        assertFocus("calendar")
        press(Key.DirectionRight)
        assertFocus("settings")
        press(Key.DirectionDown)
        assertFocus("play")
        press(Key.DirectionDown)
        assertFocus("row")
        press(Key.DirectionUp)
        assertFocus("play")
        press(Key.DirectionUp)
        assertFocus("calendar")
    }
}
