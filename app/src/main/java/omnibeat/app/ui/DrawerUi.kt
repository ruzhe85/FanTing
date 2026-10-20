package omnibeat.app.ui

import androidx.compose.foundation.layout.heightIn

import omnibeat.app.R

import omnibeat.app.model.MainPage

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import omnibeat.app.ui.appStringResource as stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun DrawerContent(
    selectedPage: MainPage,
    onStationsClick: () -> Unit = {},
    onPodcastClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onAboutClick: () -> Unit = {},
    onExitClick: () -> Unit = {},
) {
    val aboutFocusRequester = remember { FocusRequester() }
    val exitFocusRequester = remember { FocusRequester() }
    val drawerScrollState = rememberScrollState()

    ModalDrawerSheet(
        drawerContainerColor = RadioSurface,
        drawerContentColor = RadioText,
        modifier = Modifier
            .width(RadioSizes.drawerWidth)
            .fillMaxHeight(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = RadioSpacing.medium),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(drawerScrollState)
                    .padding(top = RadioSpacing.spacious),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier
                        .padding(start = RadioSpacing.large)
                        .size(RadioSizes.drawerLogo)
                        .clip(CircleShape),
                )
                Text(
                    text = stringResource(R.string.app_name),
                    fontSize = RadioTextSizes.headline,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = RadioSpacing.large, top = RadioSpacing.rowVertical),
                )
                Text(
                    text = stringResource(R.string.drawer_subtitle),
                    color = RadioTextMuted,
                    fontSize = RadioTextSizes.caption,
                    modifier = Modifier.padding(start = RadioSpacing.large, top = 2.dp, bottom = RadioSpacing.medium),
                )
                DrawerDivider()
                DrawerItem(
                    text = stringResource(R.string.page_stations),
                    iconRes = R.drawable.ic_list,
                    selected = selectedPage in MainPage.tabPages,
                    onClick = onStationsClick,
                )
                // 播客也放一个入口：顶栏标签栏在窄屏上放不下三个标签，
                // 从这里进最稳妥（标签栏本身也改成可横向滚动了）
                DrawerItem(
                    text = stringResource(R.string.page_podcasts),
                    iconRes = R.drawable.ic_play_arrow,
                    selected = selectedPage == MainPage.Podcasts,
                    onClick = onPodcastClick,
                )
                DrawerItem(
                    text = stringResource(R.string.page_settings),
                    iconRes = R.drawable.ic_settings,
                    selected = selectedPage == MainPage.Settings,
                    onClick = onSettingsClick,
                )
                DrawerItem(
                    text = stringResource(R.string.page_about),
                    iconRes = R.drawable.ic_info,
                    selected = selectedPage == MainPage.About,
                    onClick = onAboutClick,
                    modifier = Modifier
                        .focusRequester(aboutFocusRequester)
                        .focusProperties { down = exitFocusRequester },
                )
            }
            DrawerDivider()
            DrawerItem(
                text = stringResource(R.string.drawer_close_app),
                iconRes = R.drawable.ic_exit,
                selected = false,
                onClick = onExitClick,
                modifier = Modifier
                    .focusRequester(exitFocusRequester)
                    .focusProperties { up = aboutFocusRequester },
            )
        }
    }
}

@Composable
private fun DrawerDivider() {
    HorizontalDivider(
        color = RadioOutline.copy(alpha = 0.55f),
        modifier = Modifier.padding(horizontal = RadioSpacing.large, vertical = RadioSpacing.fieldGap),
    )
}

@Composable
private fun DrawerItem(
    text: String,
    iconRes: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        icon = {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(RadioSizes.icon),
            )
        },
        label = {
            Text(
                text = text,
                fontSize = RadioTextSizes.body,
                fontWeight = FontWeight.Medium,
            )
        },
        selected = selected,
        onClick = onClick,
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = RadioSurfaceHigh,
            selectedIconColor = RadioText,
            selectedTextColor = RadioText,
            unselectedContainerColor = Color.Transparent,
            unselectedIconColor = RadioTextMuted,
            unselectedTextColor = RadioText,
        ),
        modifier = modifier
            .padding(horizontal = RadioSpacing.extraSmall, vertical = 2.dp)
            .clip(RoundedCornerShape(RadioCorners.pill))
            .heightIn(min = RadioSizes.drawerItemMinHeight),
    )
}
