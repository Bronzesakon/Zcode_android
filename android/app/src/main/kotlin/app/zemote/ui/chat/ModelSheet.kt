package app.zemote.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.zemote.ui.chat.asString
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteType
import kotlinx.serialization.json.JsonObject

/**
 * 模型选择器：按 provider 分组的二级菜单（网页 `RH` 分组口径）。
 *
 * 只做「选择」；provider / 模型的增删改在设置页。当前值不在目录里时不合成菜单项，
 * 只在顶部显示当前 provider/model 文本。
 */
@Composable
internal fun ModelSheet(
    config: JsonObject?,
    view: JsonObject?,
    loading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
    onPick: (provider: String, model: String) -> Unit,
) {
    val colors = LocalZemoteColors.current
    val groups = providerGroups(view)
    val currentProvider = config?.get("provider").asString()
    val currentModel = config?.get("model").asString()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(onClick = onDismiss),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(colors.popover)
                    .navigationBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "模型",
                            style = ZemoteType.uiBase,
                            fontWeight = FontWeight.Medium,
                            color = colors.foreground,
                        )
                        Text(
                            text = listOfNotNull(currentProvider, currentModel)
                                .joinToString(" / ")
                                .ifBlank { "未提供" },
                            style = ZemoteType.uiXs.copy(fontFamily = FontFamily.Monospace),
                            color = colors.foregroundSubtle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = colors.foregroundSubtle,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                HorizontalDivider(color = colors.border)

                when {
                    loading && groups.isEmpty() -> Box(
                        Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 1.5.dp,
                            color = colors.foregroundSubtle,
                        )
                    }

                    error != null && groups.isEmpty() -> Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = error,
                            style = ZemoteType.uiSm,
                            color = colors.destructive,
                        )
                        TextButton(onClick = onReload) { Text("重试") }
                    }

                    else -> LazyColumn(Modifier.weight(1f, fill = false)) {
                        groups.forEach { group ->
                            item(key = "provider:${group.providerId}") {
                                Text(
                                    text = group.providerName,
                                    style = ZemoteType.uiXs,
                                    color = colors.foregroundSubtle,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            start = 16.dp,
                                            end = 16.dp,
                                            top = 12.dp,
                                            bottom = 4.dp,
                                        ),
                                )
                            }
                            items(group.models, key = { "${group.providerId}/${it.modelId}" }) { model ->
                                ModelRow(
                                    modelId = model.modelId,
                                    selected = group.providerId == currentProvider &&
                                        model.modelId == currentModel,
                                    onClick = { onPick(group.providerId, model.modelId) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelRow(modelId: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalZemoteColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = modelId,
            style = ZemoteType.uiBase,
            color = if (selected) colors.foreground else colors.foregroundSubtle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.Check,
                contentDescription = "当前模型",
                tint = colors.foreground,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
