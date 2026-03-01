package ru.toinet.android

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.test.espresso.ViewAssertion
import androidx.test.espresso.assertion.ViewAssertions
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.TypeSafeMatcher
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import ru.toinet.android.util.Prefs
import tools.fastlane.screengrab.locale.LocaleTestRule

@LargeTest
@RunWith(AndroidJUnit4::class)
abstract class BaseScreenshotTest {

    @Rule @JvmField
    val localeTestRule = LocaleTestRule()

    fun isGone() = getViewAssertion(ViewMatchers.Visibility.GONE)

    fun isVisible() = getViewAssertion(ViewMatchers.Visibility.VISIBLE)

    fun isInvisible() = getViewAssertion(ViewMatchers.Visibility.INVISIBLE)


    @get:Rule
    var mGrantPermissionRule: GrantPermissionRule? =
        GrantPermissionRule.grant(
            "android.permission.POST_NOTIFICATIONS"
        )

    private fun getViewAssertion(visibility: ViewMatchers.Visibility): ViewAssertion? {
        return ViewAssertions.matches(ViewMatchers.withEffectiveVisibility(visibility))
    }

    open fun childAtPosition(
        parentMatcher: Matcher<View>, position: Int
    ): Matcher<View> {

        return object : TypeSafeMatcher<View>() {
            override fun describeTo(description: Description) {
                description.appendText("Child at position $position in parent ")
                parentMatcher.describeTo(description)
            }

            public override fun matchesSafely(view: View): Boolean {
                val parent = view.parent
                return parent is ViewGroup && parentMatcher.matches(parent)
                        && view == parent.getChildAt(position)
            }
        }
    }

    @Before
    fun setPrefs(){
        Prefs.setContext(getContext())
        Prefs.isSecureWindow = false
    }

    open fun getContext(): Context? {
        return InstrumentationRegistry.getInstrumentation().targetContext
    }

}