package app.zemote.ui.chat.timeline

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 网页端使用的 lucide 线性图标（24×24、stroke-width 2、圆头圆角）。
 *
 * Material 图标是填充风格，和网页的细线图标差异明显；这里直接内嵌 lucide 的
 * path 数据，保证图标形状一比一。`Icon` 的 tint 会统一着色。
 */
internal object LucideIcons {

    val Brain: ImageVector = icon(
        "lucide-brain",
        "M12 5a3 3 0 1 0-5.997.125 4 4 0 0 0-2.526 5.77 4 4 0 0 0 .556 6.588A4 4 0 1 0 12 18Z",
        "M12 5a3 3 0 1 1 5.997.125 4 4 0 0 1 2.526 5.77 4 4 0 0 1-.556 6.588A4 4 0 1 1 12 18Z",
        "M15 13a4.5 4.5 0 0 1-3-4 4.5 4.5 0 0 1-3 4",
        "M17.599 6.5a3 3 0 0 0 .399-1.375",
        "M6.003 5.125A3 3 0 0 0 6.401 6.5",
        "M3.477 10.896a4 4 0 0 1 .585-.396",
        "M19.938 10.5a4 4 0 0 1 .585.396",
        "M6 18a4 4 0 0 1-1.967-.516",
        "M19.967 17.484A4 4 0 0 1 18 18",
    )

    val SquareTerminal: ImageVector = icon(
        "lucide-square-terminal",
        "m7 11 2-2-2-2",
        "M11 13h4",
        "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
    )

    val Search: ImageVector = icon(
        "lucide-search",
        "M19 11a8 8 0 1 1-16 0 8 8 0 0 1 16 0z",
        "m21 21-4.3-4.3",
    )

    val Pencil: ImageVector = icon(
        "lucide-pencil",
        "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z",
        "m15 5 4 4",
    )

    val Bot: ImageVector = icon(
        "lucide-bot",
        "M12 8V4H8",
        "M6 8h12a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2z",
        "M2 14h2",
        "M20 14h2",
        "M15 13v2",
        "M9 13v2",
    )

    val Plug: ImageVector = icon(
        "lucide-plug",
        "M12 22v-5",
        "M9 8V2",
        "M15 8V2",
        "M18 8v5a4 4 0 0 1-4 4h-4a4 4 0 0 1-4-4V8Z",
    )

    val Wrench: ImageVector = icon(
        "lucide-wrench",
        "M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z",
    )

    val File: ImageVector = icon(
        "lucide-file",
        "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z",
        "M14 2v4a2 2 0 0 0 2 2h4",
    )

    val Copy: ImageVector = icon(
        "lucide-copy",
        "M10 8h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H10a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2z",
        "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2",
    )

    val Check: ImageVector = icon(
        "lucide-check",
        "M20 6 9 17l-5-5",
    )

    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            paths.forEach { d ->
                addPath(
                    pathData = addPathNodes(d),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}
