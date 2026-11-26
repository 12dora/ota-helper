package io.github.otahelper.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

private const val VIEWPORT = 24f
private const val STROKE = 1.8f
private const val SHIELD = "M12 3 L19 6 V11.5 C19 15.8 16 19.4 12 21 C8 19.4 5 15.8 5 11.5 V6 Z"

/** A 24dp outline icon drawn from stroke-only path data, matching Material Symbols' outlined weight. */
private fun outline(name: String, vararg paths: String, autoMirror: Boolean = false): ImageVector {
    val builder = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = VIEWPORT,
        viewportHeight = VIEWPORT,
        autoMirror = autoMirror,
    )
    paths.forEach { data ->
        builder.addPath(
            pathData = PathParser().parsePathString(data).toNodes(),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = STROKE,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return builder.build()
}

/** The glyphs this app needs; material-icons-core lacks most of them, so all are drawn here in one style. */
internal object AppIcons {
    val ShieldCheck: ImageVector by lazy { outline("ShieldCheck", SHIELD, "M9 12 L11.2 14.2 L15.2 10") }
    val ShieldPlus: ImageVector by lazy { outline("ShieldPlus", SHIELD, "M12 9 V15", "M9 12 H15") }
    val ShieldAlert: ImageVector by lazy { outline("ShieldAlert", SHIELD, "M12 8.5 V12.5", "M12 15.6 V15.7") }
    val SystemUpdate: ImageVector by lazy {
        outline(
            "SystemUpdate",
            "M8 3 H16 A2 2 0 0 1 18 5 V19 A2 2 0 0 1 16 21 H8 A2 2 0 0 1 6 19 V5 A2 2 0 0 1 8 3 Z",
            "M12 7.5 V13.5",
            "M9.5 11 L12 13.5 L14.5 11",
            "M11 17.5 H13",
        )
    }
    val Restart: ImageVector by lazy {
        outline(
            "Restart",
            "M20 12 A8 8 0 1 1 17.66 6.34",
            "M17.66 2.5 V6.34 H13.8",
        )
    }
    val Lock: ImageVector by lazy {
        outline(
            "Lock",
            "M7 11 H17 A1.5 1.5 0 0 1 18.5 12.5 V19 A1.5 1.5 0 0 1 17 20.5 H7 A1.5 1.5 0 0 1 5.5 19 V12.5 " +
                "A1.5 1.5 0 0 1 7 11 Z",
            "M8.5 11 V8 A3.5 3.5 0 0 1 15.5 8 V11",
        )
    }
    val Alert: ImageVector by lazy {
        outline("Alert", "M12 3 A9 9 0 1 1 12 21 A9 9 0 1 1 12 3 Z", "M12 7.5 V12.8", "M12 16.3 V16.4")
    }
    val Check: ImageVector by lazy { outline("Check", "M5 12.5 L9.5 17 L19 7.5") }
    val Bolt: ImageVector by lazy { outline("Bolt", "M13 3 L5 13.5 H11.5 L10.5 21 L19 10.5 H12.5 Z") }
    val Folder: ImageVector by lazy {
        outline(
            "Folder",
            "M3.5 6.5 A1.5 1.5 0 0 1 5 5 H9.5 L11.5 7 H19 A1.5 1.5 0 0 1 20.5 8.5 V17.5 A1.5 1.5 0 0 1 19 19 H5 " +
                "A1.5 1.5 0 0 1 3.5 17.5 Z",
        )
    }
    val Log: ImageVector by lazy {
        outline(
            "Log",
            "M7 3 H14 L19 8 V19 A2 2 0 0 1 17 21 H7 A2 2 0 0 1 5 19 V5 A2 2 0 0 1 7 3 Z",
            "M14 3 V8 H19",
            "M9 13 H15",
            "M9 17 H13",
        )
    }
    val Copy: ImageVector by lazy {
        outline(
            "Copy",
            "M9 9 H18 A1 1 0 0 1 19 10 V19 A1 1 0 0 1 18 20 H9 A1 1 0 0 1 8 19 V10 A1 1 0 0 1 9 9 Z",
            "M5 15 V5 A1 1 0 0 1 6 4 H15",
        )
    }
    val ChevronRight: ImageVector by lazy { outline("ChevronRight", "M9.5 6 L15.5 12 L9.5 18", autoMirror = true) }
}
