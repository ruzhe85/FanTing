package omnibeat.app.ui

import omnibeat.app.R

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import omnibeat.app.ui.appStringResource as stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import omnibeat.app.model.ThemeMode
import kotlin.math.absoluteValue

/**
 * 首次启动引导。
 *
 * 竖屏：一页一张竖版示意图，左滑翻页。
 * 横屏：高度不够放竖图，改成两列清单，七条内容一屏看完，底部一个「开始使用」。
 *
 * 示意图全部由 [OnboardingArt] 用 Canvas 绘制，不依赖任何位图资源。
 */
private data class OnboardingPage(
    @StringRes val titleRes: Int,
    val textType: OnboardingTextType,
    val art: OnboardingArtKind,
)

private enum class OnboardingTextType {
    Build,
    Organize,
    Manage,
    Controls,
    StreamActions,
    PodcastSubscribe,
    PodcastEpisodes,
}

private val OnboardingPages = listOf(
    OnboardingPage(
        titleRes = R.string.onboarding_build_title,
        textType = OnboardingTextType.Build,
        art = OnboardingArtKind.Library,
    ),
    OnboardingPage(
        titleRes = R.string.onboarding_organize_title,
        textType = OnboardingTextType.Organize,
        art = OnboardingArtKind.Organize,
    ),
    OnboardingPage(
        titleRes = R.string.onboarding_manage_title,
        textType = OnboardingTextType.Manage,
        art = OnboardingArtKind.Manage,
    ),
    OnboardingPage(
        titleRes = R.string.onboarding_controls_title,
        textType = OnboardingTextType.Controls,
        art = OnboardingArtKind.Player,
    ),
    OnboardingPage(
        titleRes = R.string.onboarding_stream_actions_title,
        textType = OnboardingTextType.StreamActions,
        art = OnboardingArtKind.StreamInfo,
    ),
    OnboardingPage(
        titleRes = R.string.onboarding_podcast_subscribe_title,
        textType = OnboardingTextType.PodcastSubscribe,
        art = OnboardingArtKind.PodcastSubscriptions,
    ),
    OnboardingPage(
        titleRes = R.string.onboarding_podcast_episodes_title,
        textType = OnboardingTextType.PodcastEpisodes,
        art = OnboardingArtKind.PodcastEpisodes,
    ),
)

@Composable
fun OnboardingFlow(
    onFinished: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(RadioBackground)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            OnboardingLandscape(
                onFinished = onFinished,
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            OnboardingPortrait(
                onFinished = onFinished,
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun OnboardingPortrait(
    onFinished: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(pageCount = { OnboardingPages.size })
    val scope = rememberCoroutineScope()
    val pageIndex = pagerState.currentPage
    val isLastPage = pageIndex == OnboardingPages.lastIndex

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(
            horizontal = RadioSpacing.page,
            vertical = RadioSpacing.section,
        ),
    ) {
        OnboardingHeader(
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
            onSkip = onFinished,
        )

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { page ->
            OnboardingPageContent(
                page = OnboardingPages[page],
                modifier = Modifier.onboardingPagerFade(pagerState, page),
            )
        }
        OnboardingDots(pageCount = OnboardingPages.size, selectedIndex = pageIndex)
        Spacer(modifier = Modifier.height(RadioSpacing.section))

        Row(
            horizontalArrangement = Arrangement.spacedBy(RadioSpacing.medium),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (pageIndex > 0) {
                OmniSecondaryButton(
                    text = stringResource(R.string.action_back),
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(pageIndex - 1) }
                    },
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            OmniPrimaryButton(
                text = if (isLastPage) {
                    stringResource(R.string.action_finish)
                } else {
                    stringResource(R.string.action_next)
                },
                onClick = {
                    if (isLastPage) {
                        onFinished()
                    } else {
                        scope.launch { pagerState.animateScrollToPage(pageIndex + 1) }
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun OnboardingLandscape(
    onFinished: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(
            horizontal = RadioSpacing.page,
            vertical = RadioSpacing.medium,
        ),
    ) {
        OnboardingHeader(
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
            onSkip = onFinished,
        )
        Spacer(modifier = Modifier.height(RadioSpacing.medium))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(RadioSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(RadioSpacing.medium),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            itemsIndexed(OnboardingPages) { index, page ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RadioSpacing.medium),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(RadioSizes.button)
                            .clip(CircleShape)
                            .background(RadioPrimary.copy(alpha = 0.16f)),
                    ) {
                        Text(
                            text = (index + 1).toString(),
                            color = RadioPrimary,
                            fontSize = RadioTextSizes.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        text = stringResource(page.titleRes),
                        color = RadioText,
                        fontSize = RadioTextSizes.bodyLarge,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(RadioSpacing.medium))
        OmniPrimaryButton(
            text = stringResource(R.string.action_start_using),
            onClick = onFinished,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun OnboardingHeader(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onSkip: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        ThemeModeSegmentedControl(
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
        )
        TextButton(onClick = onSkip) {
            Text(stringResource(R.string.action_skip), color = RadioTextMuted)
        }
    }
}

private fun onboardingText(builder: AnnotatedString.Builder.() -> Unit): AnnotatedString {
    return buildAnnotatedString(builder)
}

private fun AnnotatedString.Builder.appendBold(text: String) {
    pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
    append(text)
    pop()
}

private fun AnnotatedString.Builder.appendLineBreak() {
    append("\n")
}

@Composable
private fun OnboardingPageContent(
    page: OnboardingPage,
    modifier: Modifier = Modifier,
) {
    val text = onboardingText(page.textType)
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 图占宽度的一半略多、固定 1:2；剩下的高度优先给文字，不再把图放大到溢出
        val bodyScrollState = rememberScrollState()
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize(),
        ) {
            Spacer(modifier = Modifier.height(RadioSizes.onboardingTopGap))
            OnboardingArtFrame(
                art = page.art,
                modifier = Modifier.fillMaxWidth(0.56f),
            )
            Spacer(modifier = Modifier.height(RadioSizes.onboardingTitleGap))

            Text(
                text = stringResource(page.titleRes),
                color = RadioText,
                fontSize = RadioTextSizes.onboardingTitle,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                lineHeight = RadioTextSizes.onboardingLineHeight,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = RadioSpacing.medium)
                    .weight(1f),
            ) {
                Text(
                    text = text,
                    color = RadioTextMuted,
                    fontSize = RadioTextSizes.bodyLarge,
                    textAlign = TextAlign.Start,
                    lineHeight = RadioTextSizes.onboardingBodyLineHeight,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(bodyScrollState)
                        .padding(end = RadioSpacing.fieldGap),
                )
                OmniScrollIndicator(
                    scrollIndicatorState = bodyScrollState.scrollIndicatorState,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun onboardingText(textType: OnboardingTextType): AnnotatedString {
    return when (textType) {
        OnboardingTextType.Build -> AnnotatedString(stringResource(R.string.onboarding_build_text))
        OnboardingTextType.Organize -> AnnotatedString(stringResource(R.string.onboarding_organize_text))
        OnboardingTextType.PodcastSubscribe ->
            AnnotatedString(stringResource(R.string.onboarding_podcast_subscribe_text))
        OnboardingTextType.PodcastEpisodes ->
            AnnotatedString(stringResource(R.string.onboarding_podcast_episodes_text))
        OnboardingTextType.Manage -> {
            val tap = stringResource(R.string.onboarding_tap)
            val tapSuffix = stringResource(R.string.onboarding_manage_tap_suffix)
            val pressAndHold = stringResource(R.string.onboarding_press_and_hold)
            val holdSuffix = stringResource(R.string.onboarding_manage_hold_suffix)
            onboardingText {
                appendBold(tap)
                append(tapSuffix)
                appendLineBreak()
                appendLineBreak()
                appendBold(pressAndHold)
                append(holdSuffix)
            }
        }
        OnboardingTextType.StreamActions -> {
            val tap = stringResource(R.string.onboarding_tap)
            val tapSuffix = stringResource(R.string.onboarding_stream_tap_suffix)
            val pressAndHold = stringResource(R.string.onboarding_press_and_hold)
            val holdSuffix = stringResource(R.string.onboarding_stream_hold_suffix)
            onboardingText {
                appendBold(tap)
                append(tapSuffix)
                appendLineBreak()
                appendLineBreak()
                appendBold(pressAndHold)
                append(holdSuffix)
            }
        }
        OnboardingTextType.Controls -> {
            val tap = stringResource(R.string.onboarding_tap)
            val tapSuffix = stringResource(R.string.onboarding_controls_tap_suffix)
            val tapAgain = stringResource(R.string.onboarding_tap_again)
            val tapAgainSuffix = stringResource(R.string.onboarding_controls_tap_again_suffix)
            val pressAndHold = stringResource(R.string.onboarding_press_and_hold)
            val holdSuffix = stringResource(R.string.onboarding_controls_hold_suffix)
            onboardingText {
                appendBold(tap)
                append(tapSuffix)
                appendLineBreak()
                appendLineBreak()
                appendBold(tapAgain)
                append(tapAgainSuffix)
                appendLineBreak()
                appendLineBreak()
                appendBold(pressAndHold)
                append(holdSuffix)
            }
        }
    }
}

@Suppress("FrequentlyChangedStateReadInComposition")
private fun Modifier.onboardingPagerFade(
    pagerState: androidx.compose.foundation.pager.PagerState,
    pageIndex: Int,
): Modifier = graphicsLayer {
    val pageOffset = (
        (pagerState.currentPage - pageIndex) + pagerState.currentPageOffsetFraction
    ).absoluteValue.coerceIn(0f, 1f)
    val minPageAlpha = 0.35f
    val fadeProgress = (pageOffset * 2.2f).coerceIn(0f, 1f)
    alpha = 1f - fadeProgress * (1f - minPageAlpha)
}

/** 示意图外框：竖版 1:2，圆角 20dp，描边用主题色 */
@Composable
private fun OnboardingArtFrame(
    art: OnboardingArtKind,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(RadioCorners.large)
    Box(
        modifier = modifier
            .aspectRatio(0.5f)
            .clip(shape)
            .background(RadioSurface)
            .border(width = 1.dp, color = RadioOutline, shape = shape),
    ) {
        OnboardingArt(kind = art, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun OnboardingDots(
    pageCount: Int,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(RadioSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        repeat(pageCount) { index ->
            Box(
                modifier = Modifier
                    .size(if (index == selectedIndex) RadioSizes.selectedPageDot else RadioSizes.pageDot)
                    .clip(CircleShape)
                    .background(if (index == selectedIndex) RadioPrimary else RadioOutline),
            )
        }
    }
}
