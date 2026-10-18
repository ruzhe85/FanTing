package omnibeat.app.ui

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.absoluteValue
import kotlinx.coroutines.flow.first

@Suppress("FrequentlyChangedStateReadInComposition")
fun Modifier.pagerFade(
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

@Composable
fun <T> SyncPagerWithSelectedPage(
    pagerState: PagerState,
    pages: List<T>,
    selectedPage: T,
    onPageSettled: (T) -> Unit,
) {
    LaunchedEffect(selectedPage, pages) {
        val pageIndex = pages.indexOf(selectedPage)
        if (pageIndex < 0) return@LaunchedEffect
        // 正在滚动（回弹动画或历史遗留的划动）时不能直接 animate：会被当次滚动
        // 覆盖。旧实现在这里直接跳过，而 LaunchedEffect 不会重跑，selectedPage 与
        // pager 会永久错位（标签高亮 A、内容停在 B）。改为等当次滚动结束后补同步。
        if (pagerState.isScrollInProgress) {
            snapshotFlow { pagerState.isScrollInProgress }.first { !it }
        }
        if (pagerState.currentPage != pageIndex) {
            pagerState.animateScrollToPage(pageIndex)
        }
    }

    LaunchedEffect(pagerState, pages) {
        snapshotFlow { pagerState.settledPage }.collect { pageIndex ->
            pages.getOrNull(pageIndex)?.let(onPageSettled)
        }
    }

    LaunchedEffect(pagerState, pages, selectedPage) {
        snapshotFlow { pagerState.isScrollInProgress }
            .collect { scrolling ->
                // 有等待中的同步任务（选中页不是当前页）时不要弹回 currentPage：
                // 「弹回旧页」与「animate 到选中页」两个动画会互相竞争，可能停在错的页。
                // 没有同步任务时才需要它——修复划到一半 / 动画被打断的中间偏移。
                val syncPending =
                    pages.indexOf(selectedPage) >= 0 && pagerState.currentPage != pages.indexOf(selectedPage)
                if (!scrolling &&
                    pagerState.currentPageOffsetFraction.absoluteValue > 0.001f &&
                    !syncPending
                ) {
                    pagerState.animateScrollToPage(pagerState.currentPage)
                }
            }
    }
}
