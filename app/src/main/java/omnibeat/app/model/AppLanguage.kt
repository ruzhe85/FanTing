package omnibeat.app.model

data class AppLanguage(
    val languageTag: String?,
    val displayName: String?,
) {
    companion object {
        val System = AppLanguage(null, null)
        val English = AppLanguage("en", "English")
        val Chinese = AppLanguage("zh", "简体中文")
        val Russian = AppLanguage("ru", "Русский")
        val entries = listOf(System, English, Chinese, Russian)
    }
}
