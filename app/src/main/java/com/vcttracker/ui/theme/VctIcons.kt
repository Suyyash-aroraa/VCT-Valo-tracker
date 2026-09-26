package com.vcttracker.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** A small hand-drawn icon set: square caps, 2px strokes, drawn on a 24 grid. */
object VctIcons {

    private fun icon(name: String, block: PathBuilder.() -> Unit, fill: (PathBuilder.() -> Unit)? = null) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Square,
                strokeLineJoin = StrokeJoin.Miter,
                pathBuilder = block,
            )
            if (fill != null) path(fill = SolidColor(Color.Black), pathBuilder = fill)
        }.build()

    /** Today: a scoreboard with a live marker. */
    val Today = icon("today", {
        moveTo(3f, 6f); lineTo(21f, 6f); lineTo(21f, 18f); lineTo(3f, 18f); close()
        moveTo(12f, 6f); lineTo(12f, 18f)
    }) {
        moveTo(6f, 10f); lineTo(9f, 10f); lineTo(9f, 14f); lineTo(6f, 14f); close()
        moveTo(15f, 10f); lineTo(18f, 10f); lineTo(18f, 14f); lineTo(15f, 14f); close()
    }

    /** Events: a bracket converging on a winner. */
    val Events = icon("events", {
        moveTo(3f, 6f); lineTo(10f, 6f); lineTo(10f, 18f); lineTo(3f, 18f)
        moveTo(10f, 12f); lineTo(15f, 12f)
    }) {
        moveTo(16f, 9f); lineTo(21f, 9f); lineTo(21f, 14f); lineTo(19f, 16f); lineTo(16f, 16f); close()
    }

    /** Standings: ranked bars. */
    val Standings = icon("standings", {
        moveTo(4f, 5f); lineTo(20f, 5f)
        moveTo(4f, 10f); lineTo(16f, 10f)
        moveTo(4f, 15f); lineTo(12f, 15f)
        moveTo(4f, 20f); lineTo(8f, 20f)
    })

    /** History: a trophy. */
    val History = icon("history", {
        moveTo(7f, 4f); lineTo(17f, 4f); lineTo(17f, 9f)
        curveTo(17f, 12f, 15f, 14f, 12f, 14f)
        curveTo(9f, 14f, 7f, 12f, 7f, 9f); close()
        moveTo(17f, 6f); lineTo(20f, 6f); lineTo(20f, 8f); curveTo(20f, 10f, 19f, 11f, 17f, 11f)
        moveTo(7f, 6f); lineTo(4f, 6f); lineTo(4f, 8f); curveTo(4f, 10f, 5f, 11f, 7f, 11f)
        moveTo(12f, 14f); lineTo(12f, 18f)
        moveTo(8f, 20f); lineTo(16f, 20f)
    })

    val Back = icon("back", {
        moveTo(20f, 12f); lineTo(5f, 12f)
        moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f)
    })

    val Refresh = icon("refresh", {
        moveTo(19f, 12f)
        curveTo(19f, 15.9f, 15.9f, 19f, 12f, 19f)
        curveTo(8.1f, 19f, 5f, 15.9f, 5f, 12f)
        curveTo(5f, 8.1f, 8.1f, 5f, 12f, 5f)
        lineTo(16f, 5f)
        moveTo(13f, 2f); lineTo(16f, 5f); lineTo(13f, 8f)
    })

    val External = icon("external", {
        moveTo(10f, 5f); lineTo(5f, 5f); lineTo(5f, 19f); lineTo(19f, 19f); lineTo(19f, 14f)
        moveTo(13f, 5f); lineTo(19f, 5f); lineTo(19f, 11f)
        moveTo(19f, 5f); lineTo(11f, 13f)
    })

    val Play = icon("play", {}) {
        moveTo(7f, 5f); lineTo(19f, 12f); lineTo(7f, 19f); close()
    }

    val Chevron = icon("chevron", {
        moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f)
    })

    val Info = icon("info", {
        moveTo(12f, 3f)
        curveTo(17f, 3f, 21f, 7f, 21f, 12f)
        curveTo(21f, 17f, 17f, 21f, 12f, 21f)
        curveTo(7f, 21f, 3f, 17f, 3f, 12f)
        curveTo(3f, 7f, 7f, 3f, 12f, 3f); close()
        moveTo(12f, 11f); lineTo(12f, 16f)
    }) {
        moveTo(11f, 7f); lineTo(13f, 7f); lineTo(13f, 9f); lineTo(11f, 9f); close()
    }
}
