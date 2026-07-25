package eu.depau.bosectl.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Pulsing placeholder used while the first device read is in flight. */
@Composable
fun SkeletonBox(modifier: Modifier = Modifier, cornerRadius: Dp = 12.dp) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "alpha",
    )
    Spacer(
        modifier
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(cornerRadius))
    )
}

@Composable
fun BatterySkeleton() {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        repeat(3) { SkeletonBox(Modifier.width(88.dp).height(32.dp), cornerRadius = 16.dp) }
    }
}

@Composable
fun ModeCardsSkeleton() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(4) {
            SkeletonBox(
                Modifier.weight(1f).height(96.dp),
                cornerRadius = 12.dp,
            )
        }
    }
}

@Composable
fun BarSkeleton(height: Dp = 40.dp, cornerRadius: Dp = 20.dp) {
    SkeletonBox(Modifier.fillMaxWidth().height(height), cornerRadius)
}

@Composable
fun IconSkeleton(size: Dp = 24.dp) {
    SkeletonBox(Modifier.size(size), cornerRadius = size / 2)
}
