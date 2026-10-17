package omnibeat.app.model

/**
 * 主题配色预设。
 *
 * 每套只声明浅色 / 深色两种模式下「主色」这一个种子，其余色阶
 * （背景、表面、次级表面、描边、文字、主色上的前景色）由 [omnibeat.app.ui.OmniBeatTheme]
 * 按固定明度阶梯派生，保证整体色调随主色变化但底仍是中性色。
 */
enum class AccentPalette(
    /** 浅色主题下的主色种子 */
    val seedLight: Long,
    /** 深色主题下的主色种子 */
    val seedDark: Long,
    /**
     * true = 中性色不染主色色相，走纯灰明度阶梯（太极的黑白方案）。
     */
    val achromatic: Boolean = false,
) {
    Violet(0xFF7650E8, 0xFF8F5CFF),
    Ocean(0xFF3C6BDE, 0xFF9DB9FF),
    Teal(0xFF0E8C82, 0xFF6FD9CD),
    Forest(0xFF2F7F45, 0xFF8FD99B),
    Amber(0xFF8A5A12, 0xFFFFC46B),
    Rose(0xFFC03B67, 0xFFFF90AE),
    Taiji(0xFF141414, 0xFFF2F2F2, achromatic = true),
    ;

    companion object {
        val Default: AccentPalette = Violet

        fun fromName(name: String?): AccentPalette =
            entries.firstOrNull { it.name == name } ?: Default
    }
}
