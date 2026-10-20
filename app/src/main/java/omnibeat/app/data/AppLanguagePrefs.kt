package omnibeat.app.data

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * 应用内语言切换（minSdk 26，不依赖 API 33 的 LocaleManager，也不引入 appcompat）。
 *
 * 存储在 SharedPreferences 而非 DataStore：attachBaseContext 需要同步读取，
 * DataStore 的异步读取在这里用不了。null 表示跟随系统（安卓 13+ 的系统级
 * 「应用语言」设置由框架直接生效，无需本类参与）。
 */
object AppLanguagePrefs {
    private const val PREFS_NAME = "app_language"
    private const val KEY_LANGUAGE = "language_tag"

    fun get(context: Context): String? =
        prefs(context).getString(KEY_LANGUAGE, null)?.takeIf { it.isNotBlank() }

    fun set(context: Context, languageTag: String?) {
        prefs(context).edit().apply {
            if (languageTag.isNullOrBlank()) remove(KEY_LANGUAGE) else putString(KEY_LANGUAGE, languageTag)
        }.apply()
    }

    /** 可选语言：「跟随系统」+ APK 内实际存在的资源语言（新增 values-xx 后自动出现在列表里）。 */
    fun options(context: Context): List<AppLanguageOption> {
        val tags = context.assets.locales
            .mapNotNull { normalize(it) }
            .distinct()
            .sorted()
        return listOf(AppLanguageOption.SYSTEM) + tags.map { AppLanguageOption(it, displayName(it)) }
    }

    /** 在 attachBaseContext 里同步调用：按持久化的语言包装 Context，未设置时原样返回。 */
    fun wrapContext(context: Context): Context {
        val tag = get(context) ?: return context
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val configuration = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(locale))
            setLayoutDirection(locale)
        }
        return context.createConfigurationContext(configuration)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** AssetManager 的原始 locale 归一化为 BCP-47："" → 默认语言，"b+zh+Hans" / "zh-rCN" → 连字符形式。 */
    private fun normalize(raw: String): String? = when {
        raw.isBlank() -> DEFAULT_LANGUAGE_TAG
        raw.startsWith("b+") -> raw.removePrefix("b+").replace('+', '-')
        raw.contains("-r") -> raw.replace("-r", "-")
        else -> raw
    }

    private fun displayName(tag: String): String =
        Locale.forLanguageTag(tag).let { it.getDisplayName(it) }

    /** 默认 values/（无限定符）即源码语言：英语。 */
    private const val DEFAULT_LANGUAGE_TAG = "en"
}

/** tag 为 null 表示跟随系统。 */
data class AppLanguageOption(val tag: String?, val displayName: String) {
    companion object {
        val SYSTEM = AppLanguageOption(null, "")
    }
}
