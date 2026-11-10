package com.barnabas.absorb.wear.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath

/**
 * A loading indicator in the Material 3 Expressive manner: a soft shape
 * that turns while it morphs through a few others, in place of a play
 * button that cannot be pressed yet.
 */
@Composable
fun ExpressiveSpinner(modifier: Modifier = Modifier, color: Color = Color.White) {
    val shapes = remember {
        listOf(
            RoundedPolygon.star(numVerticesPerRadius = 7, innerRadius = 0.8f, rounding = CornerRounding(0.3f)),
            RoundedPolygon(numVertices = 4, rounding = CornerRounding(0.45f)),
            RoundedPolygon.star(numVerticesPerRadius = 5, innerRadius = 0.7f, rounding = CornerRounding(0.25f)),
            RoundedPolygon(numVertices = 12, rounding = CornerRounding(0.5f)),
        ).map { it.normalized() }
    }
    val morphs = remember(shapes) { shapes.indices.map { Morph(shapes[it], shapes[(it + 1) % shapes.size]) } }
    val transition = rememberInfiniteTransition(label = "spinner")
    val step by transition.animateFloat(
        initialValue = 0f,
        targetValue = morphs.size.toFloat(),
        animationSpec = infiniteRepeatable(tween(durationMillis = morphs.size * 900, easing = LinearEasing)),
        label = "morph",
    )
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 2_600, easing = LinearEasing)),
        label = "turn",
    )
    val path = remember { android.graphics.Path() }
    val matrix = remember { android.graphics.Matrix() }
    Canvas(modifier) {
        val index = step.toInt().coerceIn(0, morphs.size - 1)
        path.rewind()
        morphs[index].toPath(step - index, path)
        matrix.reset()
        matrix.setScale(size.width, size.height)
        path.transform(matrix)
        rotate(angle) { drawPath(path.asComposePath(), color) }
    }
}
