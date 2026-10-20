package omnibeat.app.data

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.LocaleList
import omnibeat.app.R
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

    private fun displayName(tag: String): String =
        Locale.forLanguageTag(tag).let { it.getDisplayName(it) }
}

/** tag 为 null 表示跟随系统。 */
data class AppLanguageOption(val tag: String?, val displayName: String) {
    companion object {
        val SYSTEM = AppLanguageOption(null, "")
    }
}
