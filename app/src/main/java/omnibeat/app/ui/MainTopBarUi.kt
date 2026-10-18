package omnibeat.app.ui

import androidx.compose.foundation.layout.PaddingValues

import androidx.compose.foundation.layout.heightIn

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import omnibeat.app.ui.appStringResource as stringResource
import omnibeat.app.model.PodcastListLayout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import omnibeat.app.R
import omnibeat.app.model.MainPage
import omnibeat.app.model.PodcastSortMode
import omnibeat.app.model.PodcastSortState
import omnibeat.app.model.StationSortMode
import omnibeat.app.model.StationSortState

@Composable
fun MainTopBar(
    selectedPage: MainPage,
    visualSelectedPage: MainPage = selectedPage,
    tabPages: List<MainPage>,
    sortState: StationSortState,
    reordering: Boolean,
    onPageSelected: (MainPage) -> Unit,
    onSortModeSelected: (StationSortMode) -> Unit,
    onCancelReorder: () -> Unit,
    onConfirmReorder: () -> Unit,
    onOpenDrawer: () -> Unit,
    onAddStation: () -> Unit,
    onSearchOnline: () -> Unit,
    onAddPodcast: () -> Unit,
    onSearchPodcastOnline: () -> Unit,
    /** 播客「+」菜单的第三项：热门发现 */
    onOpenPodcastChart: () -> Unit = {},
    /** 播客的在线搜索 / 热门发现 / 详情页打开时隐藏「+」菜单，避免在浏览过程中再弹添加弹窗 */
    podcastBrowseActive: Boolean = false,
    /**
     * 期数页（打开了某个订阅）：顶栏放搜索与 ⋮ 菜单，页头节目名行不再放功能按钮
     */
    podcastEpisodesPageVisible: Boolean = false,
    /** 期数页顶栏：打开页内搜索框 */
    onOpenEpisodeSearch: () -> Unit = {},
    /** 期数页顶栏 ⋮ 菜单：期数排序（最新 / 最旧在上，带勾选） */
    episodesOldestFirst: Boolean = false,
    onEpisodeOldestFirstChange: (Boolean) -> Unit = {},
    /** 期数页顶栏 ⋮ 菜单：刷新节目 / 编辑订阅 / 进入多选 / 取消订阅 */
    onRefreshEpisodeFeed: () -> Unit = {},
    onEditPodcast: () -> Unit = {},
    onEpisodeMultiSelect: () -> Unit = {},
    onUnsubscribePodcast: () -> Unit = {},
    /** 订阅列表排序：与电台列表同一套菜单样式 */
    podcastSortState: PodcastSortState = PodcastSortState(),
    onPodcastSortModeSelected: (PodcastSortMode) -> Unit = {},
    /** 订阅列表显示方式：标准 / 紧凑 / 纯封面，与排序放在同一个菜单里 */
    podcastListLayout: PodcastListLayout = PodcastListLayout.Standard,
    onPodcastListLayoutSelected: (PodcastListLayout) -> Unit = {},
    /**
     * 下载管理页入口：非 null 时在播客订阅列表页「+」左边渲染下载图标（有下载才传）。
     * 期数页没有「+」，所以不放——用户明确入口是「添加源 icon 左边」。
     */
    onOpenDownloads: (() -> Unit)? = null,
    onlineSearchControl: (@Composable (Modifier) -> Unit)? = null,
) {
    val density = LocalDensity.current
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var addMenuExpanded by remember { mutableStateOf(false) }
    // 标签栏可横向滚动：三个标签在 20sp 字号下约 341dp，而中间只剩约 202dp
    // （屏宽 - 菜单键 - 排序键 - 添加键 - 边距），不滚动的话最后一个标签会被
    // 右侧按钮盖住、既看不见也点不到。
    val tabScrollState = rememberScrollState()
    // 每个标签的文本宽度（px）：累加起来就能算出选中标签在滚动内容里的偏移，
    // 用它把选中标签滚进视野（不依赖 LayoutCoordinates 的坐标 API）
    val tabTextWidths = remember { mutableStateMapOf<MainPage, Int>() }
    val tabPaddingPx = with(density) { (RadioSpacing.medium * 2).roundToPx() }

    // 选中的标签若滚出视野就自动滚回来：否则从抽屉进播客时，
    // 顶栏只剩两个未选中的标签可见，看不出当前在哪一页
    LaunchedEffect(visualSelectedPage, tabTextWidths.size, tabScrollState.viewportSize) {
        val viewport = tabScrollState.viewportSize
        if (viewport <= 0) return@LaunchedEffect
        val index = tabPages.indexOf(visualSelectedPage)
        if (index == -1) return@LaunchedEffect
        var offset = 0
        for (i in 0 until index) {
            // 宽度还没量到就等下一次重组
            offset += (tabTextWidths[tabPages[i]] ?: return@LaunchedEffect) + tabPaddingPx
        }
        val ownWidth = (tabTextWidths[visualSelectedPage] ?: return@LaunchedEffect) + tabPaddingPx
        val visibleLeft = tabScrollState.value
        val visibleRight = visibleLeft + viewport
        when {
            offset < visibleLeft -> tabScrollState.animateScrollTo(offset)
            offset + ownWidth > visibleRight -> tabScrollState.animateScrollTo(
                (offset + ownWidth - viewport).coerceAtMost(tabScrollState.maxValue),
            )
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(RadioBackground)
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(RadioSizes.topBarHeight)
            .padding(start = RadioSpacing.extraSmall, end = RadioSpacing.small),
    ) {
        // 左上角所有页面统一是抽屉键：非 Tab 页（设置 / 关于 / 导出导入 / 在线搜索）不再放返回键，
        // 否则在设置页点同一位置只会先退回上一页，抽屉反而够不着。
        // 返回能力不丢：系统返回手势/按键由 OmniBeatApp 的 BackHandler 接管（回 lastMainPage）。
        OmniIconButton(
            painter = painterResource(R.drawable.ic_menu),
            onClick = onOpenDrawer,
        )
        if (selectedPage == MainPage.SearchOnline && onlineSearchControl != null) {
            onlineSearchControl(
                Modifier
                    .weight(1f)
                    .padding(start = RadioSpacing.small, end = RadioSpacing.medium),
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (selectedPage in tabPages) {
                            Modifier.horizontalScroll(tabScrollState)
                        } else {
                            Modifier
                        },
                    ),
            ) {
                if (selectedPage in tabPages) {
                    tabPages.forEach { tab ->
                        // 选中态用颜色 + 字重区分，不再画下划线指示条。
                        // 字重差异拉满（Bold vs Normal）：SemiBold/Medium 只差一档，
                        // 去下划线后选中态几乎看不出来
                        Text(
                            text = stringResource(tab.titleRes()),
                            color = if (visualSelectedPage == tab) RadioText else RadioTextMuted,
                            fontSize = RadioTextSizes.subtitle,
                            fontWeight = if (visualSelectedPage == tab) {
                                FontWeight.Bold
                            } else {
                                FontWeight.Normal
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .selectable(
                                    selected = visualSelectedPage == tab,
                                    enabled = !reordering,
                                    role = Role.Tab,
                                    onClick = { onPageSelected(tab) },
                                )
                                .padding(
                                    horizontal = RadioSpacing.medium,
                                    vertical = RadioSpacing.small,
                                )
                                .onSizeChanged { size ->
                                    // 宽度只用于算标签在滚动内容里的偏移（不再画下划线）
                                    tabTextWidths[tab] = size.width
                                },
                        )
                    }
                } else {
                    Text(
                        text = stringResource(selectedPage.titleRes()),
                        color = RadioText,
                        fontSize = RadioTextSizes.title,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = RadioSpacing.medium),
                    )
                }
            }
        }
        if (selectedPage in tabPages) {
            when {
                reordering -> {
                    OmniIconButton(
                        painter = painterResource(R.drawable.ic_close),
                        onClick = onCancelReorder,
                        tint = Color(0xFFFF5C6C),
                    )
                    OmniIconButton(
                        painter = painterResource(R.drawable.ic_check),
                        onClick = onConfirmReorder,
                        tint = Color(0xFF66D17A),
                    )
                }

                // 播客页：「+」与电台一致；期数页顶栏放搜索与 ⋮ 菜单
                selectedPage == MainPage.Podcasts && !podcastBrowseActive -> {
                    if (!podcastEpisodesPageVisible) {
                        // 订阅列表：排序 → 下载入口 → 「+」，与电台页的图标顺序一致
                        PodcastSortMenuButton(
                            sortState = podcastSortState,
                            listLayout = podcastListLayout,
                            onSortModeSelected = onPodcastSortModeSelected,
                            onListLayoutSelected = onPodcastListLayoutSelected,
                        )
                        if (onOpenDownloads != null) {
                            OmniIconButton(
                                painter = painterResource(R.drawable.ic_download),
                                onClick = onOpenDownloads,
                            )
                        }
                        AddMenuButton(
                            expanded = addMenuExpanded,
                            onExpandedChange = { addMenuExpanded = it },
                            onAddManually = onAddPodcast,
                            onSearchOnline = onSearchPodcastOnline,
                            onOpenChart = onOpenPodcastChart,
                        )
                    } else {
                        // 期数页：搜索 + ⋮（排序 / 刷新 / 编辑 / 多选 / 退订）
                        OmniIconButton(
                            painter = painterResource(R.drawable.ic_search),
                            onClick = onOpenEpisodeSearch,
                        )
                        EpisodeTopBarMenu(
                            oldestFirst = episodesOldestFirst,
                            onOldestFirstChange = onEpisodeOldestFirstChange,
                            onRefresh = onRefreshEpisodeFeed,
                            onEdit = onEditPodcast,
                            onMultiSelect = onEpisodeMultiSelect,
                            onUnsubscribe = onUnsubscribePodcast,
                        )
                    }
                }

                selectedPage == MainPage.Podcasts -> Unit

                else -> {
                    SortMenuButton(
                        sortState = sortState,
                        expanded = sortMenuExpanded,
                        onExpandedChange = { sortMenuExpanded = it },
                        onSortModeSelected = onSortModeSelected,
                    )
                    AddMenuButton(
                        expanded = addMenuExpanded,
                        onExpandedChange = { addMenuExpanded = it },
                        onAddManually = onAddStation,
                        onSearchOnline = onSearchOnline,
                    )
                }
            }
        }
    }
}

@Composable
private fun SortMenuButton(
    sortState: StationSortState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSortModeSelected: (StationSortMode) -> Unit,
) {
    Box {
        OmniIconButton(
            painter = painterResource(R.drawable.ic_sort),
            onClick = { onExpandedChange(true) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            containerColor = RadioSurface,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(RadioCorners.medium),
            offset = DpOffset(x = 0.dp, y = RadioSpacing.extraSmall),
        ) {
            StationSortMode.entries.forEach { option ->
                val selected = sortState.mode == option
                DropdownMenuItem(
                    modifier = Modifier.heightIn(min = RadioSizes.menuItemMinHeight),
                    contentPadding = PaddingValues(horizontal = RadioSpacing.large),
                    text = {
                        Text(
                            text = stringResource(option.labelRes()),
                            color = RadioText,
                        )
                    },
                    onClick = {
                        onSortModeSelected(option)
                    },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(
                                if (selected) {
                                    R.drawable.ic_radio_button_checked
                                } else {
                                    R.drawable.ic_radio_button_unchecked
                                },
                            ),
                            contentDescription = null,
                            modifier = Modifier.size(RadioSizes.icon),
                            tint = if (selected) RadioPrimary else RadioTextMuted,
                        )
                    },
                    trailingIcon = if (selected && option != StationSortMode.Custom) {
                        {
                            Icon(
                                painter = painterResource(
                                    if (sortState.ascending) {
                                        R.drawable.ic_keyboard_arrow_up
                                    } else {
                                        R.drawable.ic_keyboard_arrow_down
                                    },
                                ),
                                contentDescription = null,
                                modifier = Modifier.size(RadioSizes.icon),
                                tint = RadioText,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** 订阅列表的排序菜单：最近更新 / 播客名称 / 自定义（自定义进入拖动排序） */
@Composable
private fun PodcastSortMenuButton(
    sortState: PodcastSortState,
    listLayout: PodcastListLayout,
    onSortModeSelected: (PodcastSortMode) -> Unit,
    onListLayoutSelected: (PodcastListLayout) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OmniIconButton(
            painter = painterResource(R.drawable.ic_sort),
            onClick = { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = RadioSurface,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(RadioCorners.medium),
            offset = DpOffset(x = 0.dp, y = RadioSpacing.extraSmall),
        ) {
            PodcastSortMode.entries.forEach { option ->
                val selected = sortState.mode == option
                DropdownMenuItem(
                    modifier = Modifier.heightIn(min = RadioSizes.menuItemMinHeight),
                    contentPadding = PaddingValues(horizontal = RadioSpacing.large),
                    text = {
                        Text(
                            text = stringResource(option.labelRes()),
                            color = RadioText,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSortModeSelected(option)
                    },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(
                                if (selected) {
                                    R.drawable.ic_radio_button_checked
                                } else {
                                    R.drawable.ic_radio_button_unchecked
                                },
                            ),
                            contentDescription = null,
                            modifier = Modifier.size(RadioSizes.icon),
                            tint = if (selected) RadioPrimary else RadioTextMuted,
                        )
                    },
                    trailingIcon = if (selected && option != PodcastSortMode.Custom) {
                        {
                            Icon(
                                painter = painterResource(
                                    if (sortState.ascending) {
                                        R.drawable.ic_keyboard_arrow_up
                                    } else {
                                        R.drawable.ic_keyboard_arrow_down
                                    },
                                ),
                                contentDescription = null,
                                modifier = Modifier.size(RadioSizes.icon),
                                tint = RadioText,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
            HorizontalDivider(
                color = RadioOutline.copy(alpha = 0.55f),
                modifier = Modifier.padding(
                    horizontal = RadioSpacing.large,
                    vertical = RadioSpacing.extraSmall,
                ),
            )
            Text(
                text = stringResource(R.string.podcast_list_layout_title),
                color = RadioTextMuted,
                fontSize = RadioTextSizes.caption,
                modifier = Modifier.padding(
                    horizontal = RadioSpacing.large,
                    vertical = RadioSpacing.extraSmall,
                ),
            )
            PodcastListLayout.entries.forEach { option ->
                val selected = option == listLayout
                DropdownMenuItem(
                    modifier = Modifier.heightIn(min = RadioSizes.menuItemMinHeight),
                    contentPadding = PaddingValues(horizontal = RadioSpacing.large),
                    text = {
                        Text(
                            text = stringResource(option.labelRes()),
                            color = RadioText,
                        )
                    },
                    onClick = {
                        expanded = false
                        onListLayoutSelected(option)
                    },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(
                                if (selected) {
                                    R.drawable.ic_radio_button_checked
                                } else {
                                    R.drawable.ic_radio_button_unchecked
                                },
                            ),
                            contentDescription = null,
                            modifier = Modifier.size(RadioSizes.icon),
                            tint = if (selected) RadioPrimary else RadioTextMuted,
                        )
                    },
                )
            }
        }
    }
}

/** 订阅列表显示方式的菜单文案 */
private fun PodcastListLayout.labelRes(): Int = when (this) {
    PodcastListLayout.Standard -> R.string.podcast_list_layout_standard
    PodcastListLayout.Compact -> R.string.podcast_list_layout_compact
    PodcastListLayout.Cover -> R.string.podcast_list_layout_cover
}

@Composable
private fun AddMenuButton(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onAddManually: () -> Unit,
    onSearchOnline: () -> Unit,
    /** 传 null 时不显示「热门发现」：电台页的「+」只有手动添加 / 在线搜索 */
    onOpenChart: (() -> Unit)? = null,
) {
    Box {
        OmniIconButton(
            painter = painterResource(R.drawable.ic_add),
            onClick = { onExpandedChange(true) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            containerColor = RadioSurface,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(RadioCorners.medium),
            offset = DpOffset(x = 0.dp, y = RadioSpacing.extraSmall),
        ) {
            DropdownMenuItem(
                modifier = Modifier.heightIn(min = RadioSizes.menuItemMinHeight),
                contentPadding = PaddingValues(horizontal = RadioSpacing.large),
                text = {
                    Text(
                        text = stringResource(R.string.action_add_manually),
                        color = RadioText,
                    )
                },
                onClick = {
                    onExpandedChange(false)
                    onAddManually()
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_add_manually),
                        contentDescription = null,
                        modifier = Modifier.size(RadioSizes.icon),
                        tint = RadioText,
                    )
                },
            )
            DropdownMenuItem(
                modifier = Modifier.heightIn(min = RadioSizes.menuItemMinHeight),
                contentPadding = PaddingValues(horizontal = RadioSpacing.large),
                text = {
                    Text(
                        text = stringResource(R.string.action_search_online),
                        color = RadioText,
                    )
                },
                onClick = {
                    onExpandedChange(false)
                    onSearchOnline()
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = null,
                        modifier = Modifier.size(RadioSizes.icon),
                        tint = RadioText,
                    )
                },
            )
            val openChart = onOpenChart
            if (openChart != null) {
                DropdownMenuItem(
                    modifier = Modifier.heightIn(min = RadioSizes.menuItemMinHeight),
                    contentPadding = PaddingValues(horizontal = RadioSpacing.large),
                    text = {
                        Text(
                            text = stringResource(R.string.podcast_chart_title),
                            color = RadioText,
                        )
                    },
                    onClick = {
                        onExpandedChange(false)
                        openChart()
                    },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_trending_up),
                            contentDescription = null,
                            modifier = Modifier.size(RadioSizes.icon),
                            tint = RadioText,
                        )
                    },
                )
            }
        }
    }
}

/**
 * 期数页顶栏的 ⋮ 菜单：排序（一个切换项，文案即当前顺序）/ 刷新 / 编辑 / 多选 / 取消订阅。
 * 纯文字无图标：菜单更窄更清爽，也不再被图标拉高每项的高度。
 */
@Composable
private fun EpisodeTopBarMenu(
    oldestFirst: Boolean,
    onOldestFirstChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    onMultiSelect: () -> Unit,
    onUnsubscribe: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OmniIconButton(
            painter = painterResource(R.drawable.ic_more_vert),
            onClick = { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = RadioSurface,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(RadioCorners.medium),
            offset = DpOffset(x = 0.dp, y = RadioSpacing.extraSmall),
        ) {
            // 排序：一个切换项，文案直接告诉用户当前顺序，点了就翻面
            DropdownMenuItem(
                modifier = Modifier.heightIn(min = RadioSizes.menuItemMinHeight),
                contentPadding = PaddingValues(horizontal = RadioSpacing.large),
                text = {
                    Text(
                        text = stringResource(
                            if (oldestFirst) {
                                R.string.podcast_sort_earliest_first
                            } else {
                                R.string.podcast_sort_latest_first
                            },
                        ),
                        color = RadioText,
                    )
                },
                onClick = {
                    expanded = false
                    onOldestFirstChange(!oldestFirst)
                },
            )
            HorizontalDivider(
                color = RadioOutline.copy(alpha = 0.55f),
                modifier = Modifier.padding(
                    horizontal = RadioSpacing.large,
                    vertical = RadioSpacing.extraSmall,
                ),
            )
            DropdownMenuItem(
                modifier = Modifier.heightIn(min = RadioSizes.menuItemMinHeight),
                contentPadding = PaddingValues(horizontal = RadioSpacing.large),
                text = {
                    Text(
                        text = stringResource(R.string.podcast_menu_refresh),
                        color = RadioText,
                    )
                },
                onClick = {
                    expanded = false
                    onRefresh()
                },
            )
            DropdownMenuItem(
                modifier = Modifier.heightIn(min = RadioSizes.menuItemMinHeight),
                contentPadding = PaddingValues(horizontal = RadioSpacing.large),
                text = {
                    Text(
                        text = stringResource(R.string.podcast_edit_title),
                        color = RadioText,
                    )
                },
                onClick = {
                    expanded = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                modifier = Modifier.heightIn(min = RadioSizes.menuItemMinHeight),
                contentPadding = PaddingValues(horizontal = RadioSpacing.large),
                text = {
                    Text(
                        text = stringResource(R.string.podcast_menu_multi_select),
                        color = RadioText,
                    )
                },
                onClick = {
                    expanded = false
                    onMultiSelect()
                },
            )
            HorizontalDivider(
                color = RadioOutline.copy(alpha = 0.55f),
                modifier = Modifier.padding(
                    horizontal = RadioSpacing.large,
                    vertical = RadioSpacing.extraSmall,
                ),
            )
            DropdownMenuItem(
                modifier = Modifier.heightIn(min = RadioSizes.menuItemMinHeight),
                contentPadding = PaddingValues(horizontal = RadioSpacing.large),
                text = {
                    Text(
                        text = stringResource(R.string.podcast_unsubscribe),
                        color = RadioDanger,
                    )
                },
                onClick = {
                    expanded = false
                    onUnsubscribe()
                },
            )
        }
    }
}
