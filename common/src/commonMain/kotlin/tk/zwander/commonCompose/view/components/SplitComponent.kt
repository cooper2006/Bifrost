package tk.zwander.commonCompose.view.components

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.annexflow.constraintlayout.compose.ConstraintLayout
import tech.annexflow.constraintlayout.compose.Dimension

@Composable
internal fun SplitComponent(
    startComponent: @Composable (Modifier) -> Unit,
    endComponent: @Composable (Modifier) -> Unit,
    threshold: Dp = 600.dp,
    modifier: Modifier = Modifier,
    startRatio: Double = 0.5,
    endRatio: Double = 0.5,
    horizontalSpacing: Dp = 8.dp,
    verticalSpacing: Dp = 8.dp,
) {
    BoxWithConstraints {
        with(LocalDensity.current) {
            val isOverThreshold = (constraints.maxWidth / (density * fontScale)).dp > threshold

            ConstraintLayout(
                animateChangesSpec = tween(),
                modifier = modifier,
            ) {
                val (startRef, endRef) = createRefs()

                startComponent(
                    Modifier.constrainAs(startRef) {
                        horizontalChainWeight = if (!isOverThreshold) 1f else startRatio.toFloat()
                        width = Dimension.fillToConstraints
                        start.linkTo(parent.start)
                        end.linkTo(
                            anchor = if (!isOverThreshold) parent.end else endRef.start,
                            margin = if (!isOverThreshold) 0.dp else horizontalSpacing,
                        )
                        top.linkTo(parent.top)
                    },
                )

                endComponent(
                    Modifier.constrainAs(endRef) {
                        horizontalChainWeight = if (!isOverThreshold) 1f else endRatio.toFloat()
                        width = Dimension.fillToConstraints
                        start.linkTo(if (!isOverThreshold) parent.start else startRef.end)
                        end.linkTo(parent.end)
                        top.linkTo(
                            anchor = if (!isOverThreshold) startRef.bottom else parent.top,
                            margin = if (!isOverThreshold) verticalSpacing else 0.dp,
                        )
                    },
                )
            }
        }
    }
}
