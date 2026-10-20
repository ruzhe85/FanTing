package omnibeat.app.ui

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.LayoutDirection
import omnibeat.app.data.AppLanguagePrefs

/** 应用内语言状态；tag 为 null 表示跟随系统。 */
class AppLanguageState internal constructor(
    val tag: String?,
    val hostActivity: ComponentActivity?,
    private val onSelect: (String?) -> Unit,
) {
    fun select(tag: String?) = onSelect(tag)
}

val LocalAppLanguage = staticCompositionLocalOf<AppLanguageState> {
    error("LocalAppLanguage not provided")
}

/**
 * 在根部持有语言状态并提供本地化 Context。
 *
 * 切换语言只重组这一层、不重建 Activity：recreate() 会整窗重启，
 * 旧窗口拆除时下拉弹窗冻在屏幕上，加上新 Activity 冷启动首帧，
 * 有明显卡顿。这里切状态即让整棵 UI 树按新资源重新解析。
 */
@Composable
fun AppLanguageProvider(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var tag by remember(context) { mutableStateOf(AppLanguagePrefs.get(context)) }
    val hostActivity = remember(context) { context.findActivity() as? ComponentActivity }
    val localizedContext = remember(tag, context) { AppLanguagePrefs.wrapContext(context, tag) }
    val layoutDirection = remember(localizedContext) {
        if (localizedContext.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            LayoutDirection.Rtl
        } else {
            LayoutDirection.Ltr
        }
    }
    val state = remember(tag, hostActivity) {
        AppLanguageState(tag, hostActivity) { selected ->
            if (selected != tag) {
                AppLanguagePrefs.set(context, selected)
                tag = selected
            }
        }
    }

    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedContext.resources.configuration,
        LocalResources provides localizedContext.resources,
        LocalLayoutDirection provides layoutDirection,
        LocalAppLanguage provides state,
    ) {
        content()
    }
}
