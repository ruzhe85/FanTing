package omnibeat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import omnibeat.app.ui.AppLanguageProvider
import omnibeat.app.ui.OmniBeatApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // 应用内语言：provider 持有语言状态并在根部提供本地化 Context，
            // 切换语言只重组、不重建 Activity。
            AppLanguageProvider {
                OmniBeatApp()
            }
        }
    }
}
