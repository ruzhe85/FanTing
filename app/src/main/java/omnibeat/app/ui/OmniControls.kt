package omnibeat.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollIndicatorState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch

@Composable
fun omniTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedPlaceholderColor = RadioTextMuted.copy(alpha = 0.55f),
    unfocusedPlaceholderColor = RadioTextMuted.copy(alpha = 0.55f),
    errorPlaceholderColor = RadioTextMuted.copy(alpha = 0.55f),
)

@Composable
fun omniDisabledTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedPlaceholderColor = RadioTextMuted.copy(alpha = 0.55f),
    unfocusedPlaceholderColor = RadioTextMuted.copy(alpha = 0.55f),
    errorPlaceholderColor = RadioTextMuted.copy(alpha = 0.55f),
    disabledTextColor = RadioText,
    disabledLabelColor = RadioTextMuted,
    disabledBorderColor = RadioOutline,
)

@Composable
fun OmniPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = RadioPrimary,
            contentColor = RadioOnPrimary,
            disabledContainerColor = RadioSurfaceHigh,
            disabledContentColor = RadioTextMuted,
        ),
        contentPadding = PaddingValues(horizontal = RadioSpacing.wide, vertical = RadioSpacing.small),
        modifier = modifier.heightIn(min = RadioSizes.actionButtonMinHeight),
    ) {
        Text(text)
    }
}

@Composable
fun OmniSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = RadioSurfaceHigh,
            contentColor = RadioText,
            disabledContainerColor = RadioSurfaceHigh,
            disabledContentColor = RadioTextMuted,
        ),
        contentPadding = PaddingValues(horizontal = RadioSpacing.wide, vertical = RadioSpacing.small),
        modifier = modifier.heightIn(min = RadioSizes.actionButtonMinHeight),
    ) {
        Text(text)
    }
}

@Composable
fun OmniDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = RadioDanger,
            contentColor = RadioText,
            disabledContainerColor = RadioSurfaceHigh,
            disabledContentColor = RadioTextMuted,
        ),
        contentPadding = PaddingValues(horizontal = RadioSpacing.wide, vertical = RadioSpacing.small),
        modifier = modifier.heightIn(min = RadioSizes.actionButtonMinHeight),
    ) {
        Text(text)
    }
}

@Composable
fun OmniIconButton(
    painter: Painter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = RadioText,
    disabledTint: Color = RadioTextMuted,
) {
    IconButton(
        enabled = enabled,
        onClick = onClick,
        modifier = modifier.sizeIn(minWidth = RadioSizes.button, minHeight = RadioSizes.button),
    ) {
        Icon(
            painter = painter,
            contentDescription = null,
            tint = if (enabled) tint else disabledTint,
            modifier = iconModifier.size(RadioSizes.icon),
        )
    }
}

@Composable
fun OmniFilledIconButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color? = null,
    contentColor: Color? = null,
    disabledContainerColor: Color? = null,
    disabledContentColor: Color? = null,
    content: @Composable () -> Unit,
) {
    FilledTonalIconButton(
        enabled = enabled,
        onClick = onClick,
        shape = CircleShape,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = containerColor ?: RadioSurfaceHigh,
            contentColor = contentColor ?: RadioPrimary,
            disabledContainerColor = disabledContainerColor ?: RadioSurfaceHigh,
            disabledContentColor = disabledContentColor ?: RadioTextMuted,
        ),
        modifier = modifier.sizeIn(minWidth = RadioSizes.button, minHeight = RadioSizes.button),
    ) {
        content()
    }
}

@Composable
fun OmniVerticalScrollIndicator(
    thumbTopFraction: Float,
    thumbHeightFraction: Float,
    modifier: Modifier = Modifier,
) {
    val trackColor = RadioOutline.copy(alpha = 0.55f)
    val thumbColor = RadioPrimary
    Canvas(modifier = modifier.width(3.dp)) {
        drawRect(color = trackColor, size = size)
        drawRect(
            color = thumbColor,
            topLeft = androidx.compose.ui.geometry.Offset(
                x = 0f,
                y = size.height * thumbTopFraction.coerceIn(0f, 1f),
            ),
            size = androidx.compose.ui.geometry.Size(
                width = size.width,
                height = size.height * thumbHeightFraction.coerceIn(0.12f, 1f),
            ),
        )
    }
}

@Composable
fun OmniLazyListScrollIndicator(
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    OmniScrollIndicator(
        scrollIndicatorState = listState.scrollIndicatorState,
        modifier = modifier,
    )
}

/** 滚动条触摸区宽度：视觉只有 3dp，但手指需要更宽才好按住拖动 */
internal val SCROLLBAR_TOUCH_WIDTH = 16.dp

/**
 * 可拖动的列表滚动条，用于几百期的播客列表快速定位。
 *
 * 视觉沿用 [OmniVerticalScrollIndicator] 的细轨，但把触摸区放宽到 28dp：
 * 按住轨道拖动会按比例直接 `scrollToItem`；单击轨道也能跳转。
 * 当前跳到第几条通过 [onDraggingIndexChange] 交给调用方显示，避免被 28dp 的触摸区裁切。
 * 调用方应在列表右侧留出 [SCROLLBAR_TOUCH_WIDTH] 的空白，别让滚动条盖住行尾按钮。
 */
@Composable
fun OmniLazyScrollbar(
    listState: LazyListState,
    itemCount: Int,
    modifier: Modifier = Modifier,
    onDraggingIndexChange: (Int?) -> Unit = {},
) {
    if (itemCount <= 1) return
    val scope = rememberCoroutineScope()
    val firstVisibleIndex by remember {
        derivedStateOf { listState.firstVisibleItemIndex }
    }
    val visibleItemCount by remember {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.size }
    }
    val thumbHeightFraction = (visibleItemCount.toFloat() / itemCount).coerceIn(0.12f, 1f)
    val thumbTopFraction = (firstVisibleIndex.toFloat() / itemCount)
        .coerceIn(0f, 1f - thumbHeightFraction)

    fun jumpTo(y: Float, height: Int) {
        if (height <= 0) return
        val index = ((y / height).coerceIn(0f, 1f) * itemCount)
            .toInt()
            .coerceIn(0, itemCount - 1)
        onDraggingIndexChange(index)
        scope.launch { listState.scrollToItem(index) }
    }

    Box(
        modifier = modifier
            .width(SCROLLBAR_TOUCH_WIDTH)
            .pointerInput(itemCount) {
                detectVerticalDragGestures(
                    onDragStart = { offset -> jumpTo(offset.y, size.height) },
                    onDragEnd = { onDraggingIndexChange(null) },
                    onDragCancel = { onDraggingIndexChange(null) },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        jumpTo(change.position.y, size.height)
                    },
                )
            }
            .pointerInput(itemCount) {
                detectTapGestures { offset -> jumpTo(offset.y, size.height) }
            },
        contentAlignment = Alignment.CenterEnd,
    ) {
        OmniVerticalScrollIndicator(
            thumbTopFraction = thumbTopFraction,
            thumbHeightFraction = thumbHeightFraction,
            modifier = Modifier
                .fillMaxHeight()
                .padding(end = RadioSpacing.extraSmall),
        )
    }
}

@Composable
@Suppress("FrequentlyChangedStateReadInComposition")
fun OmniScrollIndicator(
    scrollIndicatorState: ScrollIndicatorState?,
    modifier: Modifier = Modifier,
) {
    val state = scrollIndicatorState ?: return
    val viewportSize = state.viewportSize
    val contentSize = state.contentSize
    val scrollOffset = state.scrollOffset
    val values = listOf(viewportSize, contentSize, scrollOffset)
    if (
        viewportSize !in 1 until contentSize ||
        values.any { it == Int.MAX_VALUE }
    ) {
        return
    }

    val thumbHeightFraction = (viewportSize.toFloat() / contentSize).coerceIn(0.12f, 1f)
    val maxScrollOffset = (contentSize - viewportSize).coerceAtLeast(1)
    val thumbTopFraction = (scrollOffset / maxScrollOffset.toFloat() * (1f - thumbHeightFraction))
        .coerceIn(0f, 1f - thumbHeightFraction)

    OmniVerticalScrollIndicator(
        thumbTopFraction = thumbTopFraction,
        thumbHeightFraction = thumbHeightFraction,
        modifier = modifier.fillMaxHeight(),
    )
}
