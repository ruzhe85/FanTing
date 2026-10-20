package omnibeat.app.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** Composable 场景取文案：直接走系统资源，语言跟随系统。 */
@Composable
fun appStringResource(@StringRes id: Int, vararg formatArgs: Any): String =
    stringResource(id, *formatArgs)
