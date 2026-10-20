package omnibeat.app.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import omnibeat.app.R
import java.util.Locale

/**
 * 应用内语言切换（minSdk 26，不依赖 API 33 的 LocaleManager，也不引入 appcompat）。
 *
 * 存储在 SharedPreferences 而非 DataStore：读取方（AppLanguageProvider 组合时、
 * PlaybackService.attachBaseContext）都是同步路径，DataStore 的异步读取用不了。
 * null 表示跟随系统（安卓 13+ 的系统级「应用语言」设置由框架直接生效，无需本类参与）。
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

    /**
     * 可选语言：「跟随系统」+ 显式声明的内置语言。
     *
     * 不能用 AssetManager.getLocales() 枚举：它返回合并后的资源表语言，
     * 会把 androidx 各库自带的多语言翻译全列进来。新增一门语言 =
     * 加 values-xx/strings.xml + 在 app_supported_languages 数组里加一行 tag。
     */
    fun options(context: Context): List<AppLanguageOption> {
        val tags = context.resources.getStringArray(R.array.app_supported_languages)
            .filter { it.isNotBlank() }
            .distinct()
        return listOf(AppLanguageOption.SYSTEM) + tags.map { AppLanguageOption(it, displayName(it)) }
    }

    /**
     * 按指定语言包装 Context；tag 为空（跟随系统）时原样返回。
     *
     * 关键：用 ContextWrapper 包住「原始 context（Activity/Service）」，只把 resources 换成
     * 本地化之后的版本。如果直接返回 createConfigurationContext() 的纯 ContextImpl，
     * 会丢失 Activity 身份，导致 findActivity()/LocalActivity 失效
     * （github flavor 的 rememberAppUpdates 第一行 checkNotNull(LocalActivity.current) 会崩溃）。
     *
     * 注意不要设置 Locale.setDefault：跟随系统时应保持系统默认 locale 不被污染。
     */
    fun wrapContext(context: Context, tag: String? = get(context)): Context {
        if (tag.isNullOrBlank()) return context
        val locale = Locale.forLanguageTag(tag)
        val configuration = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(locale))
            setLayoutDirection(locale)
        }
        val localized = context.createConfigurationContext(configuration)
        return object : ContextWrapper(context) {
            override fun getResources(): Resources = localized.resources
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun displayName(tag: String): String = when (tag) {
        // 简体 / 繁体中文用固定的自名，避免 getDisplayName 在不同安卓版本上
        // 渲染成「中文（繁体中文）」这类不一致的表述。
        "zh" -> "中文（简体）"
        "zh-Hant" -> "中文（繁體）"
        else -> Locale.forLanguageTag(tag).let { it.getDisplayName(it) }
    }
}

/** tag 为 null 表示跟随系统。 */
data class AppLanguageOption(val tag: String?, val displayName: String) {
    companion object {
        val SYSTEM = AppLanguageOption(null, "")
    }
}
