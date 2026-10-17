package omnibeat.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import omnibeat.app.R
import omnibeat.app.model.podcastFeedKey
import omnibeat.app.podcast.ITunesPodcast
import omnibeat.app.ui.appStringResource as stringResource

/** 详情页封面尺寸：比列表大一圈，但不至于把简介挤出首屏 */
private val DETAIL_ARTWORK_SIZE = 132.dp

/**
 * 播客在线搜索页：返回箭头 + 搜索框 + 结果列表。
 *
 * 只做手动触发（键盘搜索键 / 右侧搜索按钮），不做输入即搜：
 * iTunes 接口对短时间多次请求会限流，实时搜索既浪费请求也容易把结果顺序搅乱。
 */
@Composable
fun PodcastSearchPage(
    query: String,
    results: List<ITunesPodcast>,
    loading: Boolean,
    errorText: String?,
    subscribedKeys: Set<String>,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenDetail: (ITunesPodcast) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val submit = {
        focusManager.clearFocus()
        onSearch()
    }
    // 进页面就直接能打字：少一次点击，也没有需要先看的说明内容
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = RadioSpacing.extraSmall,
                    end = RadioSpacing.medium,
                    top = RadioSpacing.small,
                    bottom = RadioSpacing.small,
                ),
        ) {
            OmniIconButton(
                painter = painterResource(R.drawable.ic_arrow_back),
                onClick = onBack,
            )
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                label = { Text(stringResource(R.string.podcast_search_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                trailingIcon = {
                    IconButton(
                        onClick = submit,
                        enabled = query.isNotBlank() && !loading,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_search),
                            contentDescription = stringResource(R.string.podcast_search_hint),
                            tint = if (query.isNotBlank()) RadioPrimary else RadioTextMuted,
                            modifier = Modifier.size(RadioSizes.iconInline),
                        )
                    }
                },
                colors = omniTextFieldColors(),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
            )
        }

        when {
            loading -> SearchStatus(text = stringResource(R.string.podcast_search_searching))
            errorText != null -> SearchError(
                message = stringResource(R.string.podcast_search_failed, errorText),
                onRetry = submit,
            )
            query.isBlank() -> SearchStatus(text = stringResource(R.string.podcast_search_idle))
            results.isEmpty() -> SearchStatus(text = stringResource(R.string.podcast_search_empty))
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = RadioSpacing.medium,
                    vertical = RadioSpacing.small,
                ),
                verticalArrangement = Arrangement.spacedBy(RadioSpacing.small),
            ) {
                items(results, key = { "${it.collectionId}:${it.feedUrl}" }) { podcast ->
                    PodcastSearchResultRow(
                        podcast = podcast,
                        subscribed = podcastFeedKeyOf(podcast) in subscribedKeys,
                        onClick = { onOpenDetail(podcast) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PodcastSearchResultRow(
    podcast: ITunesPodcast,
    subscribed: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RadioCorners.medium))
            .clickable(onClick = onClick)
            .padding(horizontal = RadioSpacing.compact, vertical = RadioSpacing.rowVertical),
    ) {
        StationArtwork(imageUrl = podcast.artworkUrl)
        Column(
            verticalArrangement = Arrangement.spacedBy(RadioSpacing.extraSmall),
            modifier = Modifier
                .weight(1f)
                .padding(start = RadioSpacing.medium),
        ) {
            Text(
                text = podcast.title,
                color = RadioText,
                fontSize = RadioTextSizes.stationTitle,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (podcast.author.isNotBlank()) {
                Text(
                    text = podcast.author,
                    color = RadioTextMuted,
                    fontSize = RadioTextSizes.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PodcastSearchMeta(podcast = podcast)
        }
        if (subscribed) {
            Text(
                text = stringResource(R.string.podcast_search_subscribed),
                color = RadioPrimary,
                fontSize = RadioTextSizes.badge,
                maxLines = 1,
                modifier = Modifier
                    .padding(start = RadioSpacing.small)
                    .clip(RoundedCornerShape(RadioCorners.pill))
                    .background(RadioPrimary.copy(alpha = 0.18f))
                    .padding(horizontal = RadioSpacing.compact, vertical = RadioSpacing.extraSmall),
            )
        }
    }
}

/** 「类型 · N 期」，两者都可能缺，缺了就不占位 */
@Composable
private fun PodcastSearchMeta(podcast: ITunesPodcast) {
    val genre = podcast.genre.orEmpty()
    val episodes = if (podcast.trackCount > 0) {
        stringResource(R.string.podcast_search_episode_count, podcast.trackCount)
    } else {
        ""
    }
    val meta = listOf(genre, episodes).filter { it.isNotBlank() }.joinToString(" · ")
    if (meta.isNotBlank()) {
        Text(
            text = meta,
            color = RadioTextMuted,
            fontSize = RadioTextSizes.caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 播客详情页：封面 / 标题 / 作者 / 类型与期数 / 简介，底部固定订阅按钮。
 * 已订阅时按钮禁用；订阅失败的错误显示在按钮上方，停留在本页可直接重试。
 */
@Composable
fun PodcastSearchDetailPage(
    podcast: ITunesPodcast,
    descriptionLoading: Boolean,
    subscribed: Boolean,
    submitting: Boolean,
    errorText: String?,
    onSubscribe: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = RadioSpacing.extraSmall,
                    end = RadioSpacing.medium,
                    top = RadioSpacing.small,
                    bottom = RadioSpacing.small,
                ),
        ) {
            OmniIconButton(
                painter = painterResource(R.drawable.ic_arrow_back),
                onClick = onBack,
            )
            Text(
                text = stringResource(R.string.podcast_search_detail_title),
                color = RadioText,
                fontSize = RadioTextSizes.subtitle,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = RadioSpacing.small),
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(RadioSpacing.small),
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = RadioSpacing.page,
                    end = RadioSpacing.page,
                    top = RadioSpacing.medium,
                    bottom = RadioSpacing.large,
                ),
        ) {
            StationArtwork(imageUrl = podcast.artworkUrl, size = DETAIL_ARTWORK_SIZE)
            Text(
                text = podcast.title,
                color = RadioText,
                fontSize = RadioTextSizes.title,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            if (podcast.author.isNotBlank()) {
                Text(
                    text = podcast.author,
                    color = RadioTextMuted,
                    fontSize = RadioTextSizes.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
            PodcastSearchMeta(podcast = podcast)

            val description = podcast.description
                ?.let(::plainDescription)
                ?.takeIf { it.isNotEmpty() }
            when {
                description != null -> Text(
                    text = description,
                    color = RadioTextMuted,
                    fontSize = RadioTextSizes.body,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = RadioSpacing.medium),
                )

                descriptionLoading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RadioSpacing.small),
                    modifier = Modifier.padding(top = RadioSpacing.medium),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(RadioSizes.iconInline),
                        color = RadioTextMuted,
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = stringResource(R.string.podcast_search_description_loading),
                        color = RadioTextMuted,
                        fontSize = RadioTextSizes.bodySmall,
                    )
                }

                else -> Text(
                    text = stringResource(R.string.podcast_search_no_description),
                    color = RadioTextMuted,
                    fontSize = RadioTextSizes.bodySmall,
                    modifier = Modifier.padding(top = RadioSpacing.medium),
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(RadioSpacing.small),
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = RadioSpacing.page,
                    end = RadioSpacing.page,
                    top = RadioSpacing.small,
                    bottom = RadioSpacing.page,
                ),
        ) {
            if (errorText != null) {
                Text(
                    text = stringResource(R.string.podcast_search_subscribe_failed, errorText),
                    color = RadioDanger,
                    fontSize = RadioTextSizes.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
            OmniPrimaryButton(
                text = when {
                    subscribed -> stringResource(R.string.podcast_search_subscribed)
                    submitting -> stringResource(R.string.podcast_search_subscribing)
                    else -> stringResource(R.string.podcast_subscribe)
                },
                onClick = onSubscribe,
                modifier = Modifier.fillMaxWidth(),
                enabled = !subscribed && !submitting,
            )
        }
    }
}

@Composable
private fun SearchStatus(text: String, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = RadioSpacing.page),
    ) {
        Text(
            text = text,
            color = RadioTextMuted,
            fontSize = RadioTextSizes.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SearchError(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = RadioSpacing.page),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(RadioSpacing.medium),
        ) {
            Text(
                text = message,
                color = RadioTextMuted,
                fontSize = RadioTextSizes.bodyLarge,
                textAlign = TextAlign.Center,
            )
            OmniSecondaryButton(
                text = stringResource(R.string.podcast_search_retry),
                onClick = onRetry,
            )
        }
    }
}

/** 搜索结果与已订阅集合用同一套归一化规则，避免协议/结尾斜杠差异导致漏判 */
private fun podcastFeedKeyOf(podcast: ITunesPodcast): String = podcastFeedKey(podcast.feedUrl)

/**
 * iTunes 的 description 常带简单 HTML（`<p>` / `<br>` / 实体）。
 * 这里只做够用的清洗：换行标签转行、去掉其余标签、还原常见实体，不引入 HTML 解析器。
 */
private fun plainDescription(raw: String): String {
    return raw
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</p\\s*>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}
