package eu.decentnewsroom.bookshelf.data.onboarding

/**
 * Stable identifiers for contextual help. Add a new entry when introducing a
 * new UI affordance so existing users can discover it independently.
 */
enum class OnboardingTip(val preferenceKey: String) {
    BookListMembership("book-list-membership"),
    ReaderMenus("reader-menus"),
}
