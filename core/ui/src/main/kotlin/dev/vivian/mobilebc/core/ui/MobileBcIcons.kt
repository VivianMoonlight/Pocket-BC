package dev.vivian.mobilebc.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Small, dependency-free icon set used by the app shell. */
object MobileBcIcons {
    val ExpandUp: ImageVector by lazy {
        icon("ExpandUp") {
            moveTo(5f, 15f); lineTo(12f, 8f); lineTo(19f, 15f); lineTo(17.6f, 16.4f)
            lineTo(12f, 10.8f); lineTo(6.4f, 16.4f); close()
        }
    }

    val ExpandDown: ImageVector by lazy {
        icon("ExpandDown") {
            moveTo(5f, 9f); lineTo(6.4f, 7.6f); lineTo(12f, 13.2f)
            lineTo(17.6f, 7.6f); lineTo(19f, 9f); lineTo(12f, 16f); close()
        }
    }
    val Menu: ImageVector by lazy {
        icon("Menu") {
            for (x in listOf(3f, 14f)) for (y in listOf(3f, 14f)) {
                moveTo(x, y); horizontalLineTo(x + 7f); verticalLineTo(y + 7f); horizontalLineTo(x); close()
            }
        }
    }

    val Members: ImageVector by lazy {
        icon("Members") {
            moveTo(9f, 11f); curveTo(13f, 11f, 13f, 3f, 9f, 3f); curveTo(5f, 3f, 5f, 11f, 9f, 11f); close()
            moveTo(2f, 20f); verticalLineTo(17f); curveTo(2f, 11f, 16f, 11f, 16f, 17f); verticalLineTo(20f); close()
            moveTo(16f, 3f); curveTo(22f, 3f, 22f, 11f, 16f, 11f); curveTo(19f, 9f, 19f, 5f, 16f, 3f); close()
            moveTo(18f, 13f); curveTo(23f, 14f, 23f, 16f, 23f, 20f); horizontalLineTo(18f); close()
        }
    }

    val Items: ImageVector by lazy {
        icon("Items") {
            moveTo(3f, 7f); lineTo(12f, 2f); lineTo(21f, 7f); verticalLineTo(17f); lineTo(12f, 22f); lineTo(3f, 17f); close()
            moveTo(5f, 8f); verticalLineTo(16f); lineTo(11f, 19.3f); verticalLineTo(11.3f); close()
            moveTo(13f, 11.3f); verticalLineTo(19.3f); lineTo(19f, 16f); verticalLineTo(8f); close()
            moveTo(6f, 6.6f); lineTo(12f, 10f); lineTo(18f, 6.6f); lineTo(12f, 3.3f); close()
        }
    }

    val Wardrobe: ImageVector by lazy {
        icon("Wardrobe") {
            moveTo(8f, 3f); lineTo(3f, 5f); lineTo(1f, 10f); lineTo(5f, 12f); verticalLineTo(21f)
            horizontalLineTo(19f); verticalLineTo(12f); lineTo(23f, 10f); lineTo(21f, 5f); lineTo(16f, 3f)
            curveTo(16f, 8f, 8f, 8f, 8f, 3f); close()
        }
    }

    val Bell: ImageVector by lazy {
        icon("Bell") {
            moveTo(4f, 18f); lineTo(6f, 15f); verticalLineTo(9f); curveTo(6f, 1f, 18f, 1f, 18f, 9f)
            verticalLineTo(15f); lineTo(20f, 18f); close()
            moveTo(9f, 20f); horizontalLineTo(15f); curveTo(15f, 24f, 9f, 24f, 9f, 20f); close()
        }
    }

    val Appearance: ImageVector by lazy {
        icon("Appearance") {
            moveTo(12f, 2f); curveTo(25.3f, 2f, 25.3f, 22f, 12f, 22f)
            curveTo(-1.3f, 22f, -1.3f, 2f, 12f, 2f); close()
            moveTo(12f, 4f); curveTo(1.3f, 4f, 1.3f, 20f, 12f, 20f); close()
        }
    }

    val Diagnostics: ImageVector by lazy {
        icon("Diagnostics") {
            moveTo(5f, 2f); horizontalLineTo(19f); verticalLineTo(22f); horizontalLineTo(5f); close()
            for (y in listOf(6f, 11f, 16f)) {
                moveTo(8f, y); verticalLineTo(y + 2f); horizontalLineTo(16f); verticalLineTo(y); close()
            }
        }
    }

    val Back: ImageVector by lazy {
        icon("Back") {
            moveTo(20f, 11f)
            lineTo(7.83f, 11f)
            lineTo(13.42f, 5.41f)
            lineTo(12f, 4f)
            lineTo(4f, 12f)
            lineTo(12f, 20f)
            lineTo(13.41f, 18.59f)
            lineTo(7.83f, 13f)
            lineTo(20f, 13f)
            close()
        }
    }

    val Chats: ImageVector by lazy {
        icon("Chats") {
            moveTo(20f, 2f)
            horizontalLineTo(4f)
            curveTo(2.9f, 2f, 2f, 2.9f, 2f, 4f)
            verticalLineTo(22f)
            lineTo(6f, 18f)
            horizontalLineTo(20f)
            curveTo(21.1f, 18f, 22f, 17.1f, 22f, 16f)
            verticalLineTo(4f)
            curveTo(22f, 2.9f, 21.1f, 2f, 20f, 2f)
            close()
            moveTo(20f, 16f)
            horizontalLineTo(5.17f)
            lineTo(4f, 17.17f)
            verticalLineTo(4f)
            horizontalLineTo(20f)
            close()
        }
    }

    val Rooms: ImageVector by lazy {
        icon("Rooms") {
            moveTo(19f, 19f)
            verticalLineTo(5f)
            curveTo(19f, 3.9f, 18.1f, 3f, 17f, 3f)
            horizontalLineTo(7f)
            curveTo(5.9f, 3f, 5f, 3.9f, 5f, 5f)
            verticalLineTo(19f)
            horizontalLineTo(3f)
            verticalLineTo(21f)
            horizontalLineTo(21f)
            verticalLineTo(19f)
            close()
            moveTo(17f, 19f)
            horizontalLineTo(7f)
            verticalLineTo(5f)
            horizontalLineTo(17f)
            close()
            moveTo(12f, 11f)
            horizontalLineTo(14f)
            verticalLineTo(13f)
            horizontalLineTo(12f)
            close()
        }
    }

    val Profile: ImageVector by lazy {
        icon("Profile") {
            moveTo(12f, 12f)
            curveTo(14.21f, 12f, 16f, 10.21f, 16f, 8f)
            curveTo(16f, 5.79f, 14.21f, 4f, 12f, 4f)
            curveTo(9.79f, 4f, 8f, 5.79f, 8f, 8f)
            curveTo(8f, 10.21f, 9.79f, 12f, 12f, 12f)
            close()
            moveTo(12f, 14f)
            curveTo(9.33f, 14f, 4f, 15.34f, 4f, 18f)
            verticalLineTo(20f)
            horizontalLineTo(20f)
            verticalLineTo(18f)
            curveTo(20f, 15.34f, 14.67f, 14f, 12f, 14f)
            close()
        }
    }
}

private fun icon(
    name: String,
    pathBuilder: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black), pathBuilder = pathBuilder)
}.build()
