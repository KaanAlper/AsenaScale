package dev.asenascale.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * AsenaScale's own icon set: single-color line icons on a 24 grid, 1.75
 * stroke with round caps and joins. Tinted by Icon(), so they follow the
 * theme. A path of "h.01" draws a dot.
 */
object AsIcons {
    private fun icon(name: String, vararg paths: String, width: Float = 1.75f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            for (d in paths) {
                val dot = d.endsWith("h.01")
                addPath(
                    pathData = addPathNodes(d),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = if (dot) width * 1.6f else width,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    /** The AsenaScale mark: an "A" drawn like a prompt, with a floating cursor. */
    val Logo = icon("logo", "M5.5 20L12 5.5L18.5 20", "M10.7 16.3H13.3", width = 2.2f)

    val Back = icon("back", "M15 5l-7 7 7 7")
    val Chevron = icon("chevron", "M9 5l7 7-7 7")
    val Plus = icon("plus", "M12 5v14", "M5 12h14")
    val Close = icon("close", "M6 6l12 12", "M18 6L6 18")
    val More = icon("more", "M12 5h.01", "M12 12h.01", "M12 19h.01")
    val Check = icon("check", "M5 12.5l4.5 4.5L19 7")

    val Key = icon("key", "M8 11.5a3.5 3.5 0 1 0 0 7a3.5 3.5 0 1 0 0-7z", "M10.5 12.5L19 4", "M15.5 7.5l2.5 2.5", "M13.5 9.5l2 2")
    val Edit = icon("edit", "M4 20h4L19 9l-4-4L4 16z", "M13 7l4 4")
    val Logout = icon("logout", "M10 4H5v16h5", "M15 8l4 4-4 4", "M19 12H9")
    val Trash = icon("trash", "M4 7h16", "M9.5 7V4h5v3", "M6.5 7l1 13h9l1-13", "M10 11v5", "M14 11v5")

    val Copy = icon("copy", "M9 9h11v11H9z", "M5 15H4V4h11v1")
    val Paste = icon("paste", "M9 5H5v16h14V5h-4", "M9 3h6v4H9z")
    val Attach = icon("attach", "M20 11.5l-7.8 7.8a5 5 0 0 1-7.1-7.1l8.5-8.5a3.3 3.3 0 0 1 4.7 4.7l-8.5 8.5a1.7 1.7 0 0 1-2.4-2.4l7.8-7.8")
    val Camera = icon("camera", "M3.5 8h3.5l2-3h6l2 3h3.5v11h-17z", "M12 10.5a3 3 0 1 0 0 6a3 3 0 1 0 0-6z")
    val Image = icon("image", "M4 5h16v14H4z", "M4 16l5-5 4.5 4.5 2-2L20 18", "M15.5 9h.01")
    val File = icon("file", "M6 3h8l4 4v14H6z", "M14 3v4h4", "M9 12h6", "M9 16h6")
    val Folder = icon("folder", "M3 6h6.5l2 2H21v11H3z")
    val Download = icon("download", "M12 4v11", "M7 10l5 5 5-5", "M5 20h14")
    val Upload = icon("upload", "M12 15V4", "M7 9l5-5 5 5", "M5 20h14")
    val Share = icon(
        "share",
        "M18 3a2 2 0 1 0 0 4a2 2 0 1 0 0-4z", "M6 10a2 2 0 1 0 0 4a2 2 0 1 0 0-4z", "M18 17a2 2 0 1 0 0 4a2 2 0 1 0 0-4z",
        "M8 11l8-4", "M8 13l8 4",
    )
    val Refresh = icon("refresh", "M19.5 12a7.5 7.5 0 1 1-2.2-5.3", "M19.5 4v4h-4")

    val Monitor = icon("monitor", "M3 4.5h18v12H3z", "M8.5 20h7", "M12 16.5V20")
    val Window = icon("window", "M4 5h16v14H4z", "M4 9h16", "M7 7h.01")
    val Focus = icon("focus", "M4 9V4h5", "M15 4h5v5", "M20 15v5h-5", "M9 20H4v-5")
    val Terminal = icon("terminal", "M5 7l5 5-5 5", "M12.5 17H19")
    val Keyboard = icon("keyboard", "M3 6.5h18v11H3z", "M7 10h.01", "M10.5 10h.01", "M14 10h.01", "M17 10h.01", "M8 14h8")
    val Screen = icon("screen", "M3 4.5h18v12H3z", "M8.5 20h7", "M9.5 10.5l2 2 3.5-4")
    val Pointer = icon("pointer", "M6 3l12 7.5-5.5 1.5L10 17.5z", "M12.5 12l4.5 5.5")
    val Transfer = icon("transfer", "M7 4v16", "M3.5 7.5L7 4l3.5 3.5", "M17 20V4", "M13.5 16.5L17 20l3.5-3.5")
    val Power = icon("power", "M12 3v8", "M7 6a7 7 0 1 0 10 0")
    val Phone = icon("phone", "M7 3h10v18H7z", "M11 18h2")
}
