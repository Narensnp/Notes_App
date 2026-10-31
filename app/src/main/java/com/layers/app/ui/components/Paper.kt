package com.layers.app.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.layers.app.ui.theme.Paper

/** Light, purposeful haptics: long-press, pin drop, checkbox toggle. */
class Haptics(private val view: View) {
    fun longPress() {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    fun tick() {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    fun confirm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    fun drop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

/**
 * A sheet of paper: soft, warm shadow, a hairline edge and an optional accent stripe.
 * When [stackDepth] > 0, one or two sheets peek out underneath — the visual cue that there are
 * sub-layers below.
 */
@Composable
fun PaperSheet(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    elevation: Dp = 3.dp,
    color: Color = Paper.colors.sheet,
    stackDepth: Int = 0,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    contentPadding: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val paper = Paper.colors
    Box(modifier) {
        val layers = stackDepth.coerceAtMost(2)
        for (i in layers downTo 1) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        translationY = (5 * i).dp.toPx()
                        rotationZ = if (i == 1) -1.4f else 1.6f
                        scaleX = 1f - 0.025f * i
                    }
                    .shadow(elevation / 2, shape, ambientColor = paper.shadow.copy(alpha = 0.10f), spotColor = paper.shadow.copy(alpha = 0.12f))
                    .background(if (i == 1) paper.sheetBehind else paper.sheetDeep, shape)
                    .border(0.5.dp, paper.hairline, shape)
            )
        }
        val clickModifier = if (onClick != null || onLongClick != null) {
            Modifier.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick)
        } else {
            Modifier
        }
        Box(
            Modifier
                .shadow(elevation, shape, ambientColor = paper.shadow.copy(alpha = 0.10f), spotColor = paper.shadow.copy(alpha = 0.16f))
                .background(color, shape)
                .border(0.5.dp, paper.hairline, shape)
                .clip(shape)
                .then(clickModifier)
                .padding(contentPadding),
            content = content,
        )
    }
}
