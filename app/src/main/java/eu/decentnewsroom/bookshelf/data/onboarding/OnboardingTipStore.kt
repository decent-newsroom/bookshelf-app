package eu.decentnewsroom.bookshelf.data.onboarding

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persists the contextual tips a person has already discovered or dismissed. */
class OnboardingTipStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _seenTips = MutableStateFlow(loadSeenTips())
    val seenTips: StateFlow<Set<OnboardingTip>> = _seenTips.asStateFlow()

    fun markSeen(tip: OnboardingTip) {
        if (tip in _seenTips.value) return

        val next = _seenTips.value + tip
        _seenTips.value = next
        preferences.edit { putStringSet(KEY_SEEN_TIPS, next.mapTo(linkedSetOf()) { it.preferenceKey }) }
    }

    private fun loadSeenTips(): Set<OnboardingTip> =
        preferences
            .getStringSet(KEY_SEEN_TIPS, emptySet())
            .orEmpty()
            .mapNotNull { key -> OnboardingTip.entries.firstOrNull { it.preferenceKey == key } }
            .toSet()

    private companion object {
        const val PREFERENCES_NAME = "bookshelf_onboarding"
        const val KEY_SEEN_TIPS = "seen_tips"
    }
}
