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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import omnibeat.app.model.ThemeMode
import omnibeat.app.ui.appStringResource as stringResource
import kotlin.math.absoluteValue

/**
 * 首次启动引导，六页。
 *
 * 竖屏：一页一张半屏示意图（只露界面上半部分），下面标题与说明。
 * 横屏：高度不够放竖图，改成左图右文，图按可用高度铺满。
 *
 * 示意图全部由 [OnboardingArt] 用 Canvas 绘制，不依赖任何位图资源。
 */
private data class OnboardingPage(
    @StringRes val titleRes: Int,
    val textType: OnboardingTextType,
    val art: OnboardingArtKind,
)

private enum class OnboardingTextType {
    AddStation,
    ManageStation,
    SubscribePodcast,
    ManagePodcast,
    PodcastList,
    EpisodeActions,
}

private val OnboardingPages = listOf(
    OnboardingPage(
        titleRes = R.string.onboarding_build_title,
        textType = OnboardingTextType.AddStation,
        art = OnboardingArtKind.AddStation,
    ),
    OnboardingPage(
        titleRes = R.string.onboarding_manage_title,
        textType = OnboardingTextType.ManageStation,
        art = OnboardingArtKind.ManageStation,
    ),
    OnboardingPage(
        titleRes = R.string.onboarding_podcast_subscribe_title,
        textType = OnboardingTextType.SubscribePodcast,
        art = OnboardingArtKind.SubscribePodcast,
    ),
    OnboardingPage(
        titleRes = R.string.onboarding_podcast_manage_title,
        textType = OnboardingTextType.ManagePodcast,
        art = OnboardingArtKind.ManagePodcast,
    ),
    OnboardingPage(
        titleRes = R.string.onboarding_podcast_list_title,
        textType = OnboardingTextType.PodcastList,
        art = OnboardingArtKind.PodcastList,
    ),
    OnboardingPage(
        titleRes = R.string.onboarding_episode_actions_title,
        textType = OnboardingTextType.EpisodeActions,
        art = OnboardingArtKind.EpisodeActions,
    ),
)

/** 示意图宽高比：320 × 340（界面只露上半屏） */
private const val ART_RATIO = 320f / 440f

/**
 * 横屏左右分栏比例：左右各占一半，各自在自己的半屏里居中。
 * 图在左半边居中、文案在右半边居中，视觉上就是「两个等分屏」。
 */
private const val LANDSCAPE_ART_WEIGHT = 0.5f
private const val LANDSCAPE_TEXT_WEIGHT = 0.5f

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

        OnboardingButtons(
            pageIndex = pageIndex,
            isLastPage = isLastPage,
            previousText = stringResource(R.string.action_back),
            lastPageText = stringResource(R.string.action_finish),
            onPrevious = { scope.launch { pagerState.animateScrollToPage(pageIndex - 1) } },
            onLast = onFinished,
            onNext = { scope.launch { pagerState.animateScrollToPage(pageIndex + 1) } },
        )
    }
}

@Composable
private fun OnboardingLandscape(
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
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { page ->
            val item = OnboardingPages[page]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RadioSpacing.large),
                modifier = Modifier
                    .fillMaxSize()
                    .onboardingPagerFade(pagerState, page),
            ) {
                // 左栏固定 45%：图在这个区域内按「区域宽 / 行高」更紧的一侧等比缩放并居中，
                // 这样右边文案区始终拿到固定的 55%，不会被宽图挤窄
                BoxWithConstraints(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(LANDSCAPE_ART_WEIGHT)
                        .fillMaxHeight(),
                ) {
                    val artHeight = minOf(maxHeight, maxWidth / ART_RATIO)
                    OnboardingArtFrame(
                        art = item.art,
                        modifier = Modifier
                            .height(artHeight)
                            .width(artHeight * ART_RATIO),
                    )
                }
                OnboardingLandscapeText(
                    page = item,
                    modifier = Modifier.weight(LANDSCAPE_TEXT_WEIGHT),
                )
            }
        }
        // 横屏圆点跟着文案走：只在右半屏底部居中，不横跨整屏（否则会落到最左端）
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.weight(LANDSCAPE_ART_WEIGHT))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.weight(LANDSCAPE_TEXT_WEIGHT),
            ) {
                OnboardingDots(pageCount = OnboardingPages.size, selectedIndex = pageIndex)
            }
        }
        Spacer(modifier = Modifier.height(RadioSpacing.medium))

        OnboardingButtons(
            pageIndex = pageIndex,
            isLastPage = isLastPage,
            previousText = stringResource(R.string.action_back),
            lastPageText = stringResource(R.string.action_start_using),
            onPrevious = { scope.launch { pagerState.animateScrollToPage(pageIndex - 1) } },
            onLast = onFinished,
            onNext = { scope.launch { pagerState.animateScrollToPage(pageIndex + 1) } },
        )
    }
}

@Composable
private fun OnboardingButtons(
    pageIndex: Int,
    isLastPage: Boolean,
    previousText: String,
    lastPageText: String,
    onPrevious: () -> Unit,
    onLast: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(RadioSpacing.medium),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (pageIndex > 0) {
            OmniSecondaryButton(
                text = previousText,
                onClick = onPrevious,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
        OmniPrimaryButton(
            text = if (isLastPage) lastPageText else stringResource(R.string.action_next),
            onClick = { if (isLastPage) onLast() else onNext() },
            modifier = Modifier.weight(1f),
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

@Composable
private fun OnboardingPageContent(
    page: OnboardingPage,
    modifier: Modifier = Modifier,
) {
    val text = onboardingText(page.textType)
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 图占一半略多的高度，剩下的给标题与正文；
        // 宽高都受约束，矮屏缩图、宽屏不会把正文挤没
        val artHeight = minOf(maxWidth / ART_RATIO, maxHeight * 0.76f)
        val bodyScrollState = rememberScrollState()
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize(),
        ) {
            Spacer(modifier = Modifier.height(RadioSizes.onboardingTopGap))
            OnboardingArtFrame(
                art = page.art,
                modifier = Modifier
                    .height(artHeight)
                    .width(artHeight * ART_RATIO),
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
                    fontSize = RadioTextSizes.onboardingBody,
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
        OnboardingTextType.AddStation ->
            AnnotatedString(stringResource(R.string.onboarding_build_text))

        OnboardingTextType.SubscribePodcast ->
            AnnotatedString(stringResource(R.string.onboarding_podcast_subscribe_text))

        OnboardingTextType.ManagePodcast ->
            AnnotatedString(stringResource(R.string.onboarding_podcast_manage_text))

        OnboardingTextType.PodcastList ->
            AnnotatedString(stringResource(R.string.onboarding_podcast_list_text))

        OnboardingTextType.EpisodeActions ->
            AnnotatedString(stringResource(R.string.onboarding_episode_actions_text))

        OnboardingTextType.ManageStation -> {
            val tap = stringResource(R.string.onboarding_tap)
            val tapSuffix = stringResource(R.string.onboarding_manage_tap_suffix)
            val pressAndHold = stringResource(R.string.onboarding_press_and_hold)
            val holdSuffix = stringResource(R.string.onboarding_manage_hold_suffix)
            onboardingText {
                appendBold(tap)
                append(tapSuffix)
                append("\n\n")
                appendBold(pressAndHold)
                append(holdSuffix)
            }
        }
    }
}

@Suppress("FrequentlyChangedStateReadInComposition")
private fun Modifier.onboardingPagerFade(
    pagerState: PagerState,
    pageIndex: Int,
): Modifier = graphicsLayer {
    val pageOffset = (
        (pagerState.currentPage - pageIndex) + pagerState.currentPageOffsetFraction
    ).absoluteValue.coerceIn(0f, 1f)
    val minPageAlpha = 0.35f
    val fadeProgress = (pageOffset * 2.2f).coerceIn(0f, 1f)
    alpha = 1f - fadeProgress * (1f - minPageAlpha)
}

/**
 * 示意图外框：调用方给尺寸，内部只负责圆角、底色与描边。
 */
@Composable
private fun OnboardingArtFrame(
    art: OnboardingArtKind,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(RadioCorners.large)
    Box(
        modifier = modifier
            .clip(shape)
            .background(RadioSurface)
            .border(width = 1.dp, color = RadioOutline, shape = shape),
    ) {
        OnboardingArt(kind = art, modifier = Modifier.fillMaxSize())
    }
}

/** 横屏右栏：标题 + 正文，整块在栏内垂直居中，正文可滚动 */
@Composable
private fun OnboardingLandscapeText(
    page: OnboardingPage,
    modifier: Modifier = Modifier,
) {
    val text = onboardingText(page.textType)
    val bodyScrollState = rememberScrollState()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxHeight(),
    ) {
        Text(
            text = stringResource(page.titleRes),
            color = RadioText,
            fontSize = RadioTextSizes.onboardingTitle,
            fontWeight = FontWeight.SemiBold,
            lineHeight = RadioTextSizes.onboardingLineHeight,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(RadioSpacing.medium))
        Text(
            text = text,
            color = RadioTextMuted,
            fontSize = RadioTextSizes.onboardingBody,
            lineHeight = RadioTextSizes.onboardingBodyLineHeight,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(bodyScrollState),
        )
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
