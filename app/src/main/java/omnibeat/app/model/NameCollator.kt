package omnibeat.app.model

import java.text.Collator
import java.util.Comparator
import java.util.Locale

/**
 * 名称排序使用的 locale：中文显式指定 collation，其余语言沿用自身默认规则。
 *
 * 为什么不直接依赖语言默认值：CLDR 里简中默认是 pinyin、繁中默认是 stroke，
 * 但默认值随设备 ICU 版本变化，OEM 或旧系统上可能落到注音等其它 tailorings，
 * 同一份列表在不同设备上顺序会对不上。这里显式写进 BCP-47 的 `-u-co-` 扩展：
 *   - 简中 → 拼音（`zh-Hans-u-co-pinyin`）
 *   - 繁中（台 / 港 / 澳，或 script=Hant）→ 笔画（`zh-Hant-u-co-stroke`）
 * 设备不支持该 collation 时 ICU 会静默回退到语言默认规则，不会抛异常。
 *
 * 其它语言（英 / 日 / 俄 / 德…）直接用原 locale，即各自语言的常规排序法。
 */
fun collationLocale(locale: Locale): Locale {
    if (locale.language != "zh") return locale
    val traditional = locale.country == "TW" || locale.country == "HK" ||
        locale.country == "MO" || locale.script == "Hant"
    val tag = if (traditional) "zh-Hant-u-co-stroke" else "zh-Hans-u-co-pinyin"
    return Locale.forLanguageTag(tag)
}

/**
 * 按语言习惯比较名称的 [Collator]。
 *
 * 每个排序入口创建一次并复用：`Collator.compare` 比 `String.compareTo` 慢，
 * 但排序只在数据变化时做一次，代价可忽略；真正的坑是在比较 lambda 里反复 getInstance。
 * [Collator] 不是线程安全的，只能在单一线程内使用（组合线程，或同一个后台协程）。
 *
 * [Collator.PRIMARY] 让大小写与变音符号不参与比较，起到原 CASE_INSENSITIVE_ORDER 的作用。
 */
fun nameCollator(locale: Locale): Collator =
    Collator.getInstance(collationLocale(locale)).apply { strength = Collator.PRIMARY }

/** 名称升序比较器，规则同 [nameCollator]；配合 `compareBy(...) { it.title }` 使用 */
fun nameComparator(locale: Locale): Comparator<String> {
    val collator = nameCollator(locale)
    return Comparator { left, right -> collator.compare(left, right) }
}
