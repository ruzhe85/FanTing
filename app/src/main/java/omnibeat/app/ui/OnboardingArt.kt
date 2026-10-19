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

/**
 * 首次启动的示意图。
 *
 * 全部用 Canvas 画，不占任何图片资源：
 * - 颜色只取主题里的主色 / 描边 / 文本 / 危险色，换配色或深浅色都不用重新出图；
 * - 坐标以 320×640 为基准，按实际画布等比缩放，所以图上就是一台竖屏手机的比例；
 * - 只表达结构（行、封面、按钮、进度），不复刻每个文字，缩小后也不会糊。
 */
enum class OnboardingArtKind {
    Library,
    Organize,
    Manage,
    Player,
    StreamInfo,
    PodcastSubscriptions,
    PodcastEpisodes,
}

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
    Canvas(modifier = modifier) {
        val pen = ArtPen(this, size.width / 320f, size.height / 640f, colors)
        when (kind) {
            OnboardingArtKind.Library -> pen.drawLibrary()
            OnboardingArtKind.Organize -> pen.drawOrganize()
            OnboardingArtKind.Manage -> pen.drawManage()
            OnboardingArtKind.Player -> pen.drawPlayer()
            OnboardingArtKind.StreamInfo -> pen.drawStreamInfo()
            OnboardingArtKind.PodcastSubscriptions -> pen.drawPodcastSubscriptions()
            OnboardingArtKind.PodcastEpisodes -> pen.drawPodcastEpisodes()
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
 * 把 320×640 的设计坐标映射到实际画布。
 * 所有绘制方法都收设计坐标，内部乘缩放系数，写图形时不用关心真实像素。
 */
private class ArtPen(
    private val scope: DrawScope,
    private val sx: Float,
    private val sy: Float,
    private val colors: ArtColors,
) {
    private fun x(value: Float) = value * sx
    private fun y(value: Float) = value * sy

    /** 圆角矩形；横竖分别缩放，视觉比例不会因为画布拉伸而变形 */
    fun box(left: Float, top: Float, width: Float, height: Float, radius: Float, color: Color) {
        scope.drawRoundRect(
            color = color,
            topLeft = Offset(x(left), y(top)),
            size = Size(x(width), y(height)),
            cornerRadius = CornerRadius(x(radius), y(radius)),
        )
    }

    fun boxOutline(
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        radius: Float,
        color: Color,
        strokeWidth: Float = 1.4f,
    ) {
        scope.drawRoundRect(
            color = color,
            topLeft = Offset(x(left), y(top)),
            size = Size(x(width), y(height)),
            cornerRadius = CornerRadius(x(radius), y(radius)),
            style = Stroke(width = strokeWidth * sx),
        )
    }

    fun circle(centerX: Float, centerY: Float, radius: Float, color: Color) {
        scope.drawCircle(color = color, radius = x(radius), center = Offset(x(centerX), y(centerY)))
    }

    fun ring(centerX: Float, centerY: Float, radius: Float, color: Color, strokeWidth: Float = 1.4f) {
        scope.drawCircle(
            color = color,
            radius = x(radius),
            center = Offset(x(centerX), y(centerY)),
            style = Stroke(width = strokeWidth * sx),
        )
    }

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Color, strokeWidth: Float = 1.6f) {
        scope.drawLine(
            color = color,
            start = Offset(x(x1), y(y1)),
            end = Offset(x(x2), y(y2)),
            strokeWidth = strokeWidth * sx,
            cap = StrokeCap.Round,
        )
    }

    /** 圆头短线，用来画"文字占位"和"图标笔画" */
    fun bar(left: Float, top: Float, width: Float, height: Float, color: Color) {
        box(left, top, width, height, height / 2f, color)
    }

    // ---- 通用部件 ----

    /** 顶栏：左下菜单键 + 三段 tab + 右侧两个圆形按钮 */
    private fun drawTopBar(activeTab: Int, rightButtons: Int = 2) {
        repeat(3) { index ->
            line(16f, 24f + index * 6f, 32f, 24f + index * 6f, colors.muted, 1.6f)
        }
        val tabs = listOf(46f, 86f, 126f, 166f)
        tabs.take(4).forEachIndexed { index, left ->
            val color = if (index == activeTab) colors.text else colors.muted.copy(alpha = 0.55f)
            bar(left, 26f, 26f, 8f, color)
        }
        if (rightButtons >= 1) {
            ring(248f, 31f, 9f, colors.muted, 1.3f)
        }
        if (rightButtons >= 2) {
            ring(266f, 31f, 11f, colors.muted, 1.5f)
            line(266f, 26f, 266f, 36f, colors.primary, 2f)
            line(261f, 31f, 271f, 31f, colors.primary, 2f)
        }
    }

    /** 电台行：封面方块 + 标题条 + 标签条 */
    private fun drawStationRow(top: Float, alpha: Float, trailing: (() -> Unit)? = null) {
        box(12f, top, 296f, 58f, 10f, colors.surfaceHigh.copy(alpha = 0.55f * alpha))
        box(24f, top + 12f, 34f, 34f, 7f, colors.primary.copy(alpha = 0.22f * alpha))
        bar(70f, top + 20f, 86f, 8f, colors.text.copy(alpha = 0.75f * alpha))
        bar(70f, top + 36f, 52f, 6f, colors.muted.copy(alpha = 0.45f * alpha))
        trailing?.invoke()
    }

    /** 列表行尾的播放信号条（三根竖线） */
    private fun drawSignal(centerX: Float, centerY: Float) {
        listOf(12f to 14f, 20f to 20f, 28f to 10f).forEachIndexed { index, (offset, height) ->
            val left = centerX + offset - 8f
            box(left, centerY - height / 2f, 4f, height, 2f, colors.primary)
        }
    }

    /** 五角星（收藏） */
    private fun drawStar(centerX: Float, centerY: Float, filled: Boolean) {
        val radius = 9f
        val path = androidx.compose.ui.graphics.Path().apply {
            for (index in 0 until 10) {
                val angle = Math.PI / 5.0 * index - Math.PI / 2
                val r = if (index % 2 == 0) radius else radius * 0.45f
                val px = x(centerX) + (Math.cos(angle) * r * sx).toFloat()
                val py = y(centerY) + (Math.sin(angle) * r * sy).toFloat()
                if (index == 0) moveTo(px, py) else lineTo(px, py)
            }
            close()
        }
        if (filled) {
            scope.drawPath(path, colors.primary)
        } else {
            scope.drawPath(path, colors.muted, style = Stroke(width = 1.3f * sx))
        }
    }

    // ---- 1 建立电台库 ----

    fun drawLibrary() {
        box(0f, 0f, 320f, 640f, 0f, colors.surface.copy(alpha = 0.55f))
        drawTopBar(activeTab = 0)
        drawStationRow(58f, alpha = 1f) { drawSignal(262f, 87f) }
        drawStationRow(122f, alpha = 1f)
        drawStationRow(186f, alpha = 0.8f)
        drawStationRow(250f, alpha = 0.45f)
        // 「＋」展开的菜单
        box(196f, 48f, 112f, 92f, 10f, colors.surfaceHigh)
        boxOutline(196f, 48f, 112f, 92f, 10f, colors.outline, 1f)
        line(210f, 66f, 210f, 76f, colors.muted, 1.8f)
        line(205f, 71f, 215f, 71f, colors.muted, 1.8f)
        bar(224f, 67f, 62f, 8f, colors.text.copy(alpha = 0.7f))
        ring(210f, 98f, 5.5f, colors.muted, 1.4f)
        line(214f, 102f, 218f, 106f, colors.muted, 1.4f)
        bar(224f, 94f, 56f, 8f, colors.text.copy(alpha = 0.7f))
        ring(266f, 31f, 16f, colors.primary, 1.4f)
    }

    // ---- 2 整理电台库 ----

    fun drawOrganize() {
        box(0f, 0f, 320f, 640f, 0f, colors.surface.copy(alpha = 0.55f))
        drawTopBar(activeTab = 0)
        // 排序键高亮
        box(230f, 20f, 22f, 22f, 6f, colors.primary.copy(alpha = 0.16f))
        // 排序菜单
        box(178f, 48f, 130f, 112f, 10f, colors.surfaceHigh)
        boxOutline(178f, 48f, 130f, 112f, 10f, colors.outline, 1f)
        bar(192f, 62f, 54f, 8f, colors.text.copy(alpha = 0.7f))
        bar(192f, 88f, 68f, 8f, colors.primary)
        bar(192f, 114f, 56f, 8f, colors.text.copy(alpha = 0.7f))
        line(178f, 130f, 308f, 130f, colors.outline, 1f)
        bar(192f, 140f, 34f, 8f, colors.text.copy(alpha = 0.7f))

        // 首行：左侧手柄 + 右侧实心星
        box(12f, 58f, 296f, 54f, 10f, colors.surfaceHigh)
        listOf(76f, 83f, 90f).forEach { line(22f, it, 34f, it, colors.primary, 1.8f) }
        box(44f, 69f, 32f, 32f, 6f, colors.primary.copy(alpha = 0.22f))
        bar(86f, 76f, 84f, 8f, colors.text.copy(alpha = 0.75f))
        bar(86f, 92f, 50f, 6f, colors.muted.copy(alpha = 0.45f))
        drawStar(286f, 85f, filled = true)

        // 次行：手柄 + 空心星
        box(12f, 120f, 296f, 54f, 10f, colors.surfaceHigh.copy(alpha = 0.6f))
        listOf(138f, 145f, 152f).forEach { line(22f, it, 34f, it, colors.muted, 1.8f) }
        box(44f, 131f, 32f, 32f, 6f, colors.primary.copy(alpha = 0.18f))
        bar(86f, 138f, 70f, 8f, colors.text.copy(alpha = 0.7f))
        drawStar(286f, 147f, filled = false)

        box(12f, 182f, 296f, 54f, 10f, colors.surfaceHigh.copy(alpha = 0.35f))
    }

    // ---- 3 管理电台 ----

    fun drawManage() {
        box(0f, 0f, 320f, 640f, 0f, colors.surface.copy(alpha = 0.55f))
        drawStationRow(24f, alpha = 1f)
        // 编辑对话框
        box(20f, 100f, 280f, 440f, 14f, colors.surfaceHigh)
        boxOutline(20f, 100f, 280f, 440f, 14f, colors.outline, 1f)
        bar(40f, 118f, 78f, 12f, colors.text)
        val fieldTops = listOf(166f, 230f, 294f, 358f)
        val fieldLabels = listOf(158f, 222f, 286f, 350f)
        fieldLabels.forEachIndexed { index, top ->
            bar(40f, top, 44f, 7f, colors.muted.copy(alpha = 0.7f))
            box(40f, fieldTops[index], 240f, 34f, 7f, colors.surface)
            boxOutline(40f, fieldTops[index], 240f, 34f, 7f, colors.outline, 1f)
            bar(50f, fieldTops[index] + 13f, if (index == 1) 170f else 84f, 9f, colors.text.copy(alpha = 0.6f))
        }
        bar(40f, 406f, 44f, 7f, colors.muted.copy(alpha = 0.7f))
        bar(110f, 406f, 62f, 7f, colors.text.copy(alpha = 0.55f))
        // 删除 + 保存
        bar(44f, 498f, 34f, 10f, colors.danger)
        box(216f, 486f, 72f, 32f, 16f, colors.primary)
        bar(236f, 498f, 32f, 9f, colors.surface)
        // 长按指引
        line(158f, 76f, 158f, 96f, colors.primary, 1.6f)
        circle(158f, 88f, 5f, colors.primary.copy(alpha = 0.3f))
    }

    // ---- 4 控制播放 ----

    fun drawPlayer() {
        box(0f, 0f, 320f, 640f, 0f, colors.surface.copy(alpha = 0.55f))
        // 背景列表（淡）
        box(12f, 24f, 296f, 50f, 10f, colors.surfaceHigh.copy(alpha = 0.3f))
        box(12f, 80f, 296f, 50f, 10f, colors.surfaceHigh.copy(alpha = 0.22f))

        // 展开面板
        box(0f, 286f, 320f, 248f, 0f, colors.surfaceHigh)
        box(16f, 304f, 60f, 60f, 14f, colors.primary.copy(alpha = 0.22f))
        bar(90f, 312f, 108f, 10f, colors.text.copy(alpha = 0.85f))
        bar(90f, 332f, 82f, 7f, colors.muted.copy(alpha = 0.6f))
        bar(90f, 350f, 128f, 6f, colors.muted.copy(alpha = 0.4f))
        // 进度
        box(16f, 386f, 288f, 4f, 2f, colors.outline)
        box(16f, 386f, 112f, 4f, 2f, colors.primary)
        circle(128f, 388f, 7f, colors.primary)
        bar(16f, 406f, 30f, 6f, colors.muted.copy(alpha = 0.5f))
        bar(258f, 406f, 42f, 6f, colors.muted.copy(alpha = 0.5f))
        // 三键
        ring(68f, 474f, 20f, colors.muted, 1.5f)
        circle(160f, 474f, 26f, colors.primary)
        ring(252f, 474f, 20f, colors.muted, 1.5f)
        // 上一 / 下一 的三角形笔画
        drawTriangle(68f, 474f, pointingLeft = true, color = colors.muted)
        drawTriangle(252f, 474f, pointingLeft = false, color = colors.muted)
        // 播放/暂停
        box(154f, 465f, 4f, 18f, 2f, colors.surface)
        box(163f, 465f, 4f, 18f, 2f, colors.surface)

        // 折叠条
        box(0f, 534f, 320f, 106f, 0f, colors.surfaceHigh)
        bar(16f, 556f, 128f, 12f, colors.text.copy(alpha = 0.8f))
        bar(16f, 582f, 82f, 8f, colors.muted.copy(alpha = 0.5f))
        ring(196f, 574f, 16f, colors.muted, 1.4f)
        circle(246f, 574f, 20f, colors.primary)
        box(240f, 566f, 3.5f, 16f, 1.75f, colors.surface)
        box(249f, 566f, 3.5f, 16f, 1.75f, colors.surface)
        ring(292f, 574f, 16f, colors.muted, 1.4f)
        line(306f, 568f, 312f, 574f, colors.muted, 1.6f)
        line(312f, 574f, 306f, 580f, colors.muted, 1.6f)
    }

    private fun drawTriangle(centerX: Float, centerY: Float, pointingLeft: Boolean, color: Color) {
        val path = androidx.compose.ui.graphics.Path()
        val left = x(centerX - 5f)
        val right = x(centerX + 5f)
        val top = y(centerY - 6f)
        val bottom = y(centerY + 6f)
        if (pointingLeft) {
            path.moveTo(right, top); path.lineTo(right, bottom); path.lineTo(left, y(centerY))
        } else {
            path.moveTo(left, top); path.lineTo(left, bottom); path.lineTo(right, y(centerY))
        }
        path.close()
        scope.drawPath(path, color)
    }

    // ---- 5 音频流信息 ----

    fun drawStreamInfo() {
        box(0f, 0f, 320f, 640f, 0f, colors.surface.copy(alpha = 0.55f))
        box(12f, 24f, 296f, 50f, 10f, colors.surfaceHigh.copy(alpha = 0.25f))
        box(12f, 80f, 296f, 50f, 10f, colors.surfaceHigh.copy(alpha = 0.2f))

        // 对话框
        box(20f, 150f, 280f, 300f, 14f, colors.surfaceHigh)
        boxOutline(20f, 150f, 280f, 300f, 14f, colors.outline, 1f)
        bar(40f, 170f, 86f, 12f, colors.text)
        val rows = listOf(206f, 238f, 270f, 302f, 334f)
        rows.forEachIndexed { index, top ->
            bar(40f, top, if (index == 0) 28f else 40f, 7f, colors.muted.copy(alpha = 0.7f))
            bar(110f, top, if (index == 1) 150f else 90f, 7f, colors.text.copy(alpha = 0.7f))
        }
        box(180f, 398f, 100f, 32f, 16f, colors.primary)
        bar(214f, 410f, 34f, 9f, colors.surface)

        // 底部条 + 长按指示
        box(0f, 534f, 320f, 106f, 0f, colors.surfaceHigh)
        bar(16f, 556f, 112f, 12f, colors.text.copy(alpha = 0.8f))
        bar(16f, 582f, 70f, 8f, colors.muted.copy(alpha = 0.5f))
        circle(246f, 574f, 20f, colors.primary)
        box(240f, 566f, 3.5f, 16f, 1.75f, colors.surface)
        box(249f, 566f, 3.5f, 16f, 1.75f, colors.surface)
        circle(120f, 500f, 9f, colors.primary.copy(alpha = 0.28f))
        circle(120f, 500f, 4f, colors.primary)
        line(120f, 504f, 120f, 528f, colors.primary, 1.5f)
    }

    // ---- 6 播客订阅 ----

    fun drawPodcastSubscriptions() {
        box(0f, 0f, 320f, 640f, 0f, colors.surface.copy(alpha = 0.55f))
        drawTopBar(activeTab = 3, rightButtons = 1)
        // 三张订阅卡
        listOf(58f to 1f, 154f to 1f, 250f to 0.55f).forEach { (top, alpha) ->
            box(12f, top, 296f, 88f, 12f, colors.surfaceHigh.copy(alpha = 0.5f * alpha))
            box(24f, top + 12f, 64f, 64f, 10f, colors.primary.copy(alpha = 0.22f * alpha))
            circle(76f, top + 64f, 10f, colors.primary.copy(alpha = alpha))
            bar(102f, top + 22f, 96f, 10f, colors.text.copy(alpha = 0.75f * alpha))
            bar(102f, top + 44f, 120f, 7f, colors.muted.copy(alpha = 0.45f * alpha))
        }
        // 「＋」菜单三项
        box(182f, 48f, 126f, 134f, 10f, colors.surfaceHigh)
        boxOutline(182f, 48f, 126f, 134f, 10f, colors.outline, 1f)
        line(196f, 68f, 196f, 78f, colors.muted, 1.8f)
        line(191f, 73f, 201f, 73f, colors.muted, 1.8f)
        bar(210f, 69f, 62f, 8f, colors.text.copy(alpha = 0.7f))
        ring(196f, 102f, 5.5f, colors.muted, 1.4f)
        line(200f, 106f, 204f, 110f, colors.muted, 1.4f)
        bar(210f, 98f, 54f, 8f, colors.text.copy(alpha = 0.7f))
        line(190f, 130f, 202f, 130f, colors.muted, 1.6f)
        line(196f, 124f, 196f, 136f, colors.muted, 1.6f)
        bar(210f, 126f, 58f, 8f, colors.text.copy(alpha = 0.7f))
        // 刷新全部
        circle(272f, 540f, 26f, colors.primary)
        ring(272f, 540f, 11f, colors.surface, 2.4f)
        line(266f, 534f, 272f, 532f, colors.surface, 2.4f)
    }

    // ---- 7 播客单集 ----

    fun drawPodcastEpisodes() {
        box(0f, 0f, 320f, 640f, 0f, colors.surface.copy(alpha = 0.55f))
        // 顶栏：返回 + 标题 + 搜索 + 更多
        line(16f, 24f, 22f, 30f, colors.muted, 1.8f)
        line(22f, 30f, 16f, 36f, colors.muted, 1.8f)
        bar(34f, 24f, 78f, 10f, colors.text.copy(alpha = 0.85f))
        ring(248f, 31f, 9f, colors.muted, 1.4f)
        listOf(25f, 31f, 37f).forEach { circle(278f, it.toFloat(), 1.8f, colors.muted) }

        // 在听：标题 + meta + 进度 + 两个辅助钮
        box(12f, 58f, 296f, 96f, 10f, colors.surfaceHigh.copy(alpha = 0.7f))
        bar(24f, 74f, 168f, 9f, colors.text.copy(alpha = 0.85f))
        bar(24f, 96f, 108f, 6f, colors.muted.copy(alpha = 0.5f))
        box(24f, 114f, 220f, 3f, 1.5f, colors.outline)
        box(24f, 114f, 92f, 3f, 1.5f, colors.primary)
        ring(196f, 136f, 13f, colors.muted, 1.2f)
        ring(252f, 136f, 13f, colors.muted, 1.2f)

        // 未听
        box(12f, 162f, 296f, 92f, 10f, colors.surfaceHigh.copy(alpha = 0.45f))
        bar(24f, 178f, 148f, 9f, colors.text.copy(alpha = 0.75f))
        bar(24f, 200f, 76f, 6f, colors.muted.copy(alpha = 0.45f))
        ring(196f, 230f, 13f, colors.muted, 1.2f)
        ring(252f, 230f, 13f, colors.muted, 1.2f)

        // 听完：对勾
        box(12f, 262f, 296f, 92f, 10f, colors.surfaceHigh.copy(alpha = 0.35f))
        bar(24f, 278f, 160f, 9f, colors.text.copy(alpha = 0.65f))
        bar(24f, 300f, 58f, 6f, colors.muted.copy(alpha = 0.4f))
        circle(252f, 330f, 13f, colors.primary.copy(alpha = 0.2f))
        line(246f, 330f, 250f, 334f, colors.primary, 1.8f)
        line(250f, 334f, 258f, 326f, colors.primary, 1.8f)

        box(12f, 362f, 296f, 92f, 10f, colors.surfaceHigh.copy(alpha = 0.22f))

        // 底部续播条
        box(0f, 534f, 320f, 106f, 0f, colors.surfaceHigh)
        bar(16f, 556f, 120f, 12f, colors.text.copy(alpha = 0.8f))
        bar(16f, 582f, 78f, 8f, colors.muted.copy(alpha = 0.5f))
        circle(246f, 574f, 20f, colors.primary)
        box(240f, 566f, 3.5f, 16f, 1.75f, colors.surface)
        box(249f, 566f, 3.5f, 16f, 1.75f, colors.surface)
    }
}
