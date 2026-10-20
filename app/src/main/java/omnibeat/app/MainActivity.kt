package omnibeat.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import omnibeat.app.data.AppLanguagePrefs
import omnibeat.app.ui.OmniBeatApp

class MainActivity : ComponentActivity() {
    // 应用内语言：在基类 attach 前按持久化语言包装 Context，进程重启后同样生效。
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguagePrefs.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OmniBeatApp()
        }
    }
}
