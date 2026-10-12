package com.signal.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Icons from Lucide (ISC license), as the handoff asks once icons are added. Each is Lucide's
 * 24×24 stroke path, drawn with its usual 2-unit round stroke.
 */
object SignalIcons {
    /** Marks a program with a reminder (PLAN §4.3.4 → Reminders). */
    val bell: ImageVector by lazy {
        lucide("bell", "M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9", "M10.3 21a1.94 1.94 0 0 0 3.4 0")
    }

    private fun lucide(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            for (d in paths) {
                addPath(
                    PathParser().parsePathString(d).toNodes(),
                    stroke = SolidColor(Color.White),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}

@Composable
fun Icon(icon: ImageVector, size: Int, tint: Color, modifier: Modifier = Modifier) {
    Image(icon, contentDescription = null, modifier.size(size.dp), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint))
}
