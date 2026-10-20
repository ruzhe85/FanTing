package omnibeat.app.data

import android.content.res.Resources
import androidx.annotation.StringRes

/** 非 Composable 场景取文案：直接走系统资源，语言跟随系统。 */
fun Resources.appString(@StringRes id: Int, vararg args: Any): String = getString(id, *args)
