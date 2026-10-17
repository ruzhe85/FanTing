package omnibeat.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 带图标的文件操作行（导出 / 导入 / 重置），翻译页与设置页共用 */
@Composable
internal fun ExportImportActionRow(
    icon: Int,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RadioSpacing.large),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = RadioSpacing.rowVertical),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = if (enabled) RadioText else RadioTextMuted,
            modifier = Modifier.size(RadioSizes.iconLarge),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (enabled) RadioText else RadioTextMuted,
                fontSize = RadioTextSizes.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                color = RadioTextMuted,
                fontSize = RadioTextSizes.caption,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}
