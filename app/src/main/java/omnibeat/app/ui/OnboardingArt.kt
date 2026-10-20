package omnibeat.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import omnibeat.app.R
import omnibeat.app.ui.appStringResource as stringResource

/**
 * 首次启动的示意图。
 *
 * 全部用 Canvas 画，不占任何图片资源：
 * - 颜色只取主题里的主色 / 描边 / 文本 / 危险色，换配色或深浅色都不用重新出图；
 * - 画布固定 320×340（竖屏只露上半屏），按实际尺寸等比缩放；
 * - 菜单项、字段名、按钮这些**固定文案**写成真实文字（跟随系统语言），
 *   台名、曲目、期数这类**示例内容**保持色块 —— 写死某个台名反而误导。
 */
enum class OnboardingArtKind {
    /** 1 添加电台 */
    AddStation,

    /** 2 管理电台 */
    ManageStation,

    /** 3 订阅播客 */
    SubscribePodcast,

    /** 4 管理播客（订阅长按菜单） */
    ManagePodcast,

    /** 5 播客列表（期数列表 + ⋮ 菜单） */
    PodcastList,

    /** 6 节目操作（长按单期弹出的底部菜单） */
    EpisodeActions,
}

/** 图上需要出现的关键文案，全部来自 strings.xml */
private data class OnboardingArtLabels(
    val tabStations: String = "",
    val tabFavorites: String = "",
    val tabSearch: String = "",
    val tabPodcasts: String = "",
    val addManually: String = "",
    val searchOnline: String = "",
    val chart: String = "",
    val editStation: String = "",
    val nameLabel: String = "",
    val streamLabel: String = "",
    val tagsLabel: String = "",
    val delete: String = "",
    val save: String = "",
    val edit: String = "",
    val unsubscribe: String = "",
    val sortLatest: String = "",
    val refresh: String = "",
    val editPodcast: String = "",
    val multiSelect: String = "",
    val episodeActions: String = "",
    val favorite: String = "",
    val download: String = "",
    val markPlayed: String = "",
)

@Composable
fun OnboardingArt(kind: OnboardingArtKind, modifier: Modifier = Modifier) {
    val colors = ArtColors(
        primary = RadioPrimary,
        surface = RadioSurface,
        surfaceHigh = RadioSurfaceHigh,
        outline = RadioOutline,
        text = RadioText,
        muted = RadioTextMuted,
        danger = RadioDanger,
    )
    val textMeasurer = rememberTextMeasurer()
    val labels = OnboardingArtLabels(
        tabStations = stringResource(R.string.page_stations),
        tabFavorites = stringResource(R.string.page_favorites),
        tabSearch = stringResource(R.string.action_search),
        tabPodcasts = stringResource(R.string.page_podcasts),
        addManually = stringResource(R.string.action_add_manually),
        searchOnline = stringResource(R.string.action_search_online),
        chart = stringResource(R.string.podcast_chart_title),
        editStation = stringResource(R.string.station_editor_edit_title),
        nameLabel = stringResource(R.string.station_editor_title_label),
        streamLabel = stringResource(R.string.station_editor_stream_url_label),
        tagsLabel = stringResource(R.string.station_editor_tags_label),
        delete = stringResource(R.string.action_delete),
        save = stringResource(R.string.station_editor_save),
        edit = stringResource(R.string.action_edit),
        unsubscribe = stringResource(R.string.podcast_unsubscribe),
        sortLatest = stringResource(R.string.podcast_sort_latest_first),
        refresh = stringResource(R.string.podcast_menu_refresh),
        editPodcast = stringResource(R.string.podcast_edit_title),
        multiSelect = stringResource(R.string.podcast_menu_multi_select),
        episodeActions = stringResource(R.string.podcast_episode_actions),
        favorite = stringResource(R.string.podcast_favorite_add),
        download = stringResource(R.string.podcast_batch_download),
        markPlayed = stringResource(R.string.podcast_mark_played),
    )
    Canvas(modifier = modifier) {
        // 等比缩放：画布固定 320×340，比例变化时只多留白，图形不会被拉伸
        val scale = minOf(size.width / 320f, size.height / 340f)
        val offsetX = (size.width - 320f * scale) / 2f
        val offsetY = (size.height - 340f * scale) / 2f
        withTransform({
            translate(offsetX, offsetY)
            scale(scale, scale, pivot = Offset.Zero)
        }) {
            val pen = ArtPen(this, colors, textMeasurer, labels)
            when (kind) {
                OnboardingArtKind.AddStation -> pen.drawAddStation()
                OnboardingArtKind.ManageStation -> pen.drawManageStation()
                OnboardingArtKind.SubscribePodcast -> pen.drawSubscribePodcast()
                OnboardingArtKind.ManagePodcast -> pen.drawManagePodcast()
                OnboardingArtKind.PodcastList -> pen.drawPodcastList()
                OnboardingArtKind.EpisodeActions -> pen.drawEpisodeActions()
            }
        }
    }
}

private data class ArtColors(
    val primary: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val outline: Color,
    val text: Color,
    val muted: Color,
    val danger: Color,
)

/**
 * 把 320×340 的设计坐标映射到实际画布。
 * 缩放由外层画布变换完成，这里所有坐标都是设计值，文字与描边不用单独乘系数。
 */
private class ArtPen(
    private val scope: DrawScope,
    private val colors: ArtColors,
    private val textMeasurer: TextMeasurer,
    private val labels: OnboardingArtLabels,
) {
    fun box(left: Float, top: Float, width: Float, height: Float, radius: Float, color: Color) {
        scope.drawRoundRect(
            color = color,
            topLeft = Offset(left, top),
            size = Size(width, height),
            cornerRadius = CornerRadius(radius, radius),
        )
    }

    fun boxOutline(
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        radius: Float,
        color: Color,
        strokeWidth: Float = 1f,
    ) {
        scope.drawRoundRect(
            color = color,
            topLeft = Offset(left, top),
            size = Size(width, height),
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(width = strokeWidth),
        )
    }

    fun circle(centerX: Float, centerY: Float, radius: Float, color: Color) {
        scope.drawCircle(color = color, radius = radius, center = Offset(centerX, centerY))
    }

    fun ring(centerX: Float, centerY: Float, radius: Float, color: Color, strokeWidth: Float = 1.4f) {
        scope.drawCircle(
            color = color,
            radius = radius,
            center = Offset(centerX, centerY),
            style = Stroke(width = strokeWidth),
        )
    }

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Color, strokeWidth: Float = 1.6f) {
        scope.drawLine(
            color = color,
            start = Offset(x1, y1),
            end = Offset(x2, y2),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }

    /** 圆头短线，用来画"文字占位"和图标笔画 */
    fun bar(left: Float, top: Float, width: Float, height: Float, color: Color) {
        box(left, top, width, height, height / 2f, color)
    }

    /**
     * 在图上写一行字；top 是文字块顶部。
     *
     * 关键：测量密度固定为 1，让 1sp = 1 个设计单位。
     * 画布已经整体缩放过一次，若这里再按屏幕密度测量，
     * 文字会被放大两遍（真机上表现为标签挤成一团）。
     */
    fun text(left: Float, top: Float, value: String, size: Float = 9f, color: Color = colors.text) {
        if (value.isBlank()) return
        val layout = textMeasurer.measure(
            text = AnnotatedString(value),
            style = TextStyle(fontSize = size.sp, color = color),
            maxLines = 1,
            density = Density(1f),
        )
        scope.drawText(textLayoutResult = layout, topLeft = Offset(left, top))
    }

    // ---- 通用部件 ----

    /**
     * 顶栏：☰ + 四个文字标签 + 右侧两个圆形按钮。
     * [activeTab] 0 电台 / 1 收藏 / 2 搜索 / 3 播客
     */
    private fun drawTopBar(activeTab: Int) {
        repeat(3) { index ->
            line(16f, 24f + index * 6f, 32f, 24f + index * 6f, colors.muted, 1.6f)
        }
        val tabs = listOf(
            46f to labels.tabStations,
            82f to labels.tabFavorites,
            118f to labels.tabSearch,
            154f to labels.tabPodcasts,
        )
        tabs.forEachIndexed { index, (left, label) ->
            if (label.isNotBlank()) {
                text(
                    left = left,
                    top = 24f,
                    value = label,
                    size = 10f,
                    color = if (index == activeTab) colors.text else colors.muted.copy(alpha = 0.6f),
                )
            } else {
                bar(left, 27f, 24f, 7f, colors.muted.copy(alpha = 0.4f))
            }
        }
        // 排序键
        line(236f, 26f, 236f, 36f, colors.muted, 1.5f)
        line(231f, 30f, 241f, 30f, colors.muted, 1.5f)
        line(233f, 33f, 233f, 36f, colors.muted, 1.5f)
        line(239f, 33f, 239f, 36f, colors.muted, 1.5f)
        // 添加键
        ring(266f, 31f, 11f, colors.muted, 1.5f)
        line(266f, 26f, 266f, 36f, colors.primary, 1.9f)
        line(261f, 31f, 271f, 31f, colors.primary, 1.9f)
    }

    /** 电台行：封面方块 + 标题条 + 标签条 */
    private fun drawStationRow(top: Float, alpha: Float) {
        box(12f, top, 296f, 58f, 10f, colors.surfaceHigh.copy(alpha = 0.55f * alpha))
        box(24f, top + 12f, 34f, 34f, 7f, colors.primary.copy(alpha = 0.22f * alpha))
        bar(70f, top + 20f, 86f, 8f, colors.text.copy(alpha = 0.75f * alpha))
        bar(70f, top + 36f, 52f, 6f, colors.muted.copy(alpha = 0.45f * alpha))
    }

    /** 播放中的信号条（三根竖线） */
    private fun drawSignal(centerX: Float, centerY: Float) {
        listOf(12f to 14f, 20f to 20f, 28f to 10f).forEach { (offset, height) ->
            box(centerX + offset - 8f, centerY - height / 2f, 4f, height, 2f, colors.primary)
        }
    }

    /** 订阅卡：封面方块 + 标题条 + 副标题条 + 封面右下播放角标 */
    private fun drawSubscriptionCard(top: Float, alpha: Float) {
        box(12f, top, 296f, 88f, 12f, colors.surfaceHigh.copy(alpha = 0.6f * alpha))
        box(24f, top + 12f, 64f, 64f, 10f, colors.primary.copy(alpha = 0.22f * alpha))
        // 播放角标：主色圆底 + 三角，缺了三角就只是个蓝点
        circle(76f, top + 64f, 10f, colors.primary.copy(alpha = alpha))
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(73f, top + 58.5f)
            lineTo(73f, top + 69.5f)
            lineTo(81f, top + 64f)
            close()
        }
        scope.drawPath(path, colors.surface.copy(alpha = alpha))
        bar(102f, top + 22f, 104f, 9f, colors.text.copy(alpha = 0.78f * alpha))
        bar(102f, top + 44f, 132f, 7f, colors.muted.copy(alpha = 0.45f * alpha))
    }

    /** 期数行：标题条 + 进度/状态条（+可选尾部按钮） */
    private fun drawEpisodeRow(top: Float, height: Float, alpha: Float, progress: Float?) {
        box(12f, top, 296f, height, 10f, colors.surfaceHigh.copy(alpha = 0.55f * alpha))
        bar(24f, top + 14f, 158f, 9f, colors.text.copy(alpha = 0.78f * alpha))
        bar(24f, top + 34f, 96f, 6f, colors.muted.copy(alpha = 0.45f * alpha))
        if (progress != null) {
            box(24f, top + 50f, 176f, 3f, 1.5f, colors.outline.copy(alpha = 0.8f))
            box(24f, top + 50f, 176f * progress, 3f, 1.5f, colors.primary.copy(alpha = alpha))
        }
    }

    // ---- 1 添加电台 ----

    fun drawAddStation() {
        box(0f, 0f, 320f, 340f, 0f, colors.surface.copy(alpha = 0.5f))
        drawTopBar(activeTab = 0)
        drawStationRow(58f, 1f)
        drawSignal(262f, 87f)
        drawStationRow(122f, 1f)
        drawStationRow(186f, 0.85f)
        drawStationRow(250f, 0.6f)
        drawStationRow(314f, 0.35f)

        // 「＋」展开的菜单
        box(196f, 48f, 118f, 92f, 10f, colors.surfaceHigh)
        boxOutline(196f, 48f, 118f, 92f, 10f, colors.outline, 1f)
        line(210f, 66f, 210f, 76f, colors.muted, 1.8f)
        line(205f, 71f, 215f, 71f, colors.muted, 1.8f)
        text(224f, 62f, labels.addManually, 9f, colors.text)
        ring(210f, 98f, 5.5f, colors.muted, 1.4f)
        line(214f, 102f, 218f, 106f, colors.muted, 1.4f)
        text(224f, 90f, labels.searchOnline, 9f, colors.text)
    }

    // ---- 2 管理电台 ----

    fun drawManageStation() {
        box(0f, 0f, 320f, 340f, 0f, colors.surface.copy(alpha = 0.5f))
        // 被长按的那一行
        drawStationRow(20f, 1f)
        // 编辑对话框
        box(20f, 80f, 280f, 256f, 14f, colors.surfaceHigh)
        boxOutline(20f, 80f, 280f, 256f, 14f, colors.outline, 1f)
        text(40f, 96f, labels.editStation, 12f, colors.text)

        text(40f, 124f, labels.nameLabel, 8.5f, colors.muted.copy(alpha = 0.85f))
        box(40f, 134f, 240f, 28f, 7f, colors.surface)
        boxOutline(40f, 134f, 240f, 28f, 7f, colors.outline, 1f)
        bar(50f, 144f, 72f, 8f, colors.text.copy(alpha = 0.6f))

        text(40f, 176f, labels.streamLabel, 8.5f, colors.muted.copy(alpha = 0.85f))
        box(40f, 186f, 240f, 28f, 7f, colors.surface)
        boxOutline(40f, 186f, 240f, 28f, 7f, colors.outline, 1f)
        bar(50f, 196f, 150f, 8f, colors.text.copy(alpha = 0.5f))

        text(40f, 228f, labels.tagsLabel, 8.5f, colors.muted.copy(alpha = 0.85f))
        box(40f, 238f, 240f, 28f, 7f, colors.surface)
        boxOutline(40f, 238f, 240f, 28f, 7f, colors.outline, 1f)
        bar(50f, 248f, 56f, 8f, colors.text.copy(alpha = 0.45f))

        // 删除 / 保存
        text(44f, 292f, labels.delete, 10f, colors.danger)
        box(216f, 288f, 72f, 28f, 14f, colors.primary)
        text(238f, 297f, labels.save, 9f, colors.surface)

        // 长按指引
        line(158f, 66f, 158f, 78f, colors.primary, 1.5f)
        circle(158f, 74f, 4f, colors.primary.copy(alpha = 0.3f))
    }

    // ---- 3 订阅播客 ----

    fun drawSubscribePodcast() {
        box(0f, 0f, 320f, 340f, 0f, colors.surface.copy(alpha = 0.5f))
        drawTopBar(activeTab = 3)
        drawSubscriptionCard(58f, 1f)
        drawSubscriptionCard(154f, 0.9f)
        drawSubscriptionCard(250f, 0.6f)

        // 「＋」菜单三项
        box(182f, 48f, 126f, 134f, 10f, colors.surfaceHigh)
        boxOutline(182f, 48f, 126f, 134f, 10f, colors.outline, 1f)
        line(196f, 68f, 196f, 78f, colors.muted, 1.8f)
        line(191f, 73f, 201f, 73f, colors.muted, 1.8f)
        text(210f, 64f, labels.addManually, 9f, colors.text)
        ring(196f, 102f, 5.5f, colors.muted, 1.4f)
        line(200f, 106f, 204f, 110f, colors.muted, 1.4f)
        text(210f, 94f, labels.searchOnline, 9f, colors.text)
        line(190f, 130f, 202f, 130f, colors.muted, 1.6f)
        line(196f, 124f, 196f, 136f, colors.muted, 1.6f)
        text(210f, 122f, labels.chart, 9f, colors.text)
    }

    // ---- 4 管理播客 ----

    fun drawManagePodcast() {
        box(0f, 0f, 320f, 340f, 0f, colors.surface.copy(alpha = 0.5f))
        drawTopBar(activeTab = 3)

        // 长按中的卡片：主色描边
        box(12f, 58f, 296f, 88f, 12f, colors.surfaceHigh.copy(alpha = 0.85f))
        boxOutline(12f, 58f, 296f, 88f, 12f, colors.primary, 1.4f)
        box(24f, 70f, 64f, 64f, 10f, colors.primary.copy(alpha = 0.24f))
        bar(102f, 80f, 104f, 9f, colors.text.copy(alpha = 0.8f))
        bar(102f, 102f, 100f, 7f, colors.muted.copy(alpha = 0.5f))
        circle(76f, 122f, 10f, colors.primary)

        drawSubscriptionCard(154f, 0.85f)
        drawSubscriptionCard(250f, 0.55f)

        // 长按菜单：编辑 / 取消订阅
        box(176f, 62f, 140f, 92f, 10f, colors.surfaceHigh)
        boxOutline(176f, 62f, 140f, 92f, 10f, colors.outline, 1f)
        box(190f, 78f, 10f, 8f, 2f, colors.muted)
        line(196f, 74f, 196f, 78f, colors.muted, 1.5f)
        text(212f, 78f, labels.edit, 9f, colors.text)
        ring(196f, 122f, 6f, colors.danger, 1.5f)
        line(191f, 117f, 201f, 127f, colors.danger, 1.5f)
        text(212f, 112f, labels.unsubscribe, 9f, colors.danger)
    }

    // ---- 5 播客列表 ----

    fun drawPodcastList() {
        box(0f, 0f, 320f, 340f, 0f, colors.surface.copy(alpha = 0.5f))
        // 顶栏：返回 + 节目名 + 搜索 + ⋮（展开中）
        line(16f, 24f, 22f, 30f, colors.muted, 1.8f)
        line(22f, 30f, 16f, 36f, colors.muted, 1.8f)
        bar(34f, 25f, 86f, 9f, colors.text.copy(alpha = 0.8f))
        ring(226f, 31f, 9f, colors.muted, 1.5f)
        line(229f, 34f, 233f, 38f, colors.muted, 1.5f)
        box(248f, 18f, 26f, 26f, 7f, colors.primary.copy(alpha = 0.14f))
        listOf(26f, 31f, 36f).forEach { circle(261f, it, 1.9f, colors.primary) }

        drawEpisodeRow(56f, 76f, 1f, 0.42f)
        drawEpisodeRow(140f, 72f, 0.9f, null)
        drawEpisodeRow(220f, 72f, 0.8f, null)
        box(12f, 300f, 296f, 34f, 10f, colors.surfaceHigh.copy(alpha = 0.4f))

        // ⋮ 菜单
        box(164f, 46f, 152f, 176f, 10f, colors.surfaceHigh)
        boxOutline(164f, 46f, 152f, 176f, 10f, colors.outline, 1f)
        text(178f, 60f, labels.sortLatest, 9f, colors.text)
        line(176f, 84f, 304f, 84f, colors.outline, 0.8f)
        text(178f, 92f, labels.refresh, 9f, colors.text)
        text(178f, 120f, labels.editPodcast, 9f, colors.text)
        text(178f, 148f, labels.multiSelect, 9f, colors.text)
        line(176f, 176f, 304f, 176f, colors.outline, 0.8f)
        text(178f, 190f, labels.unsubscribe, 9f, colors.danger)
    }

    // ---- 6 节目操作 ----

    fun drawEpisodeActions() {
        box(0f, 0f, 320f, 340f, 0f, colors.surface.copy(alpha = 0.5f))
        line(16f, 24f, 22f, 30f, colors.muted, 1.8f)
        line(22f, 30f, 16f, 36f, colors.muted, 1.8f)
        bar(34f, 25f, 86f, 9f, colors.text.copy(alpha = 0.8f))
        ring(226f, 31f, 9f, colors.muted, 1.5f)
        line(229f, 34f, 233f, 38f, colors.muted, 1.5f)
        listOf(26f, 31f, 36f).forEach { circle(261f, it, 1.9f, colors.muted) }

        // 被长按的那一期
        drawEpisodeRow(54f, 72f, 1f, 0.42f)

        // 底部弹层
        box(0f, 176f, 320f, 164f, 18f, colors.surfaceHigh)
        bar(140f, 184f, 40f, 4f, colors.outline)
        text(20f, 198f, labels.episodeActions, 12f, colors.text)
        bar(20f, 218f, 148f, 7f, colors.muted.copy(alpha = 0.45f))

        // 收藏
        drawStar(28f, 254f)
        text(48f, 246f, labels.favorite, 10f, colors.text)
        line(20f, 272f, 300f, 272f, colors.outline.copy(alpha = 0.6f), 0.8f)

        // 下载
        line(28f, 288f, 28f, 302f, colors.muted, 1.6f)
        line(23f, 296f, 28f, 302f, colors.muted, 1.6f)
        line(33f, 296f, 28f, 302f, colors.muted, 1.6f)
        line(21f, 308f, 35f, 308f, colors.muted, 1.6f)
        text(48f, 292f, labels.download, 10f, colors.text)
        line(20f, 318f, 300f, 318f, colors.outline.copy(alpha = 0.6f), 0.8f)

        // 标记为已听
        ring(28f, 332f, 7f, colors.muted, 1.5f)
        line(24f, 332f, 27f, 335f, colors.muted, 1.5f)
        line(27f, 335f, 32f, 329f, colors.muted, 1.5f)
        text(48f, 324f, labels.markPlayed, 10f, colors.text)
    }

    /** 五角星（收藏图标），以 (cx, cy) 为中心 */
    private fun drawStar(cx: Float, cy: Float) {
        val path = androidx.compose.ui.graphics.Path()
        val outer = 8f
        val inner = 3.6f
        for (index in 0 until 10) {
            val angle = Math.PI / 5.0 * index - Math.PI / 2
            val radius = if (index % 2 == 0) outer else inner
            val px = cx + (Math.cos(angle) * radius).toFloat()
            val py = cy + (Math.sin(angle) * radius).toFloat()
            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        scope.drawPath(path, colors.muted, style = Stroke(width = 1.4f))
    }
}
