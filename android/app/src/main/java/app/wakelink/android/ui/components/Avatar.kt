package app.wakelink.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wakelink.android.ui.theme.AvatarPalette

/** First visible character of a display name, for avatar initials. */
fun avatarInitial(name: String): String =
    name.trim().firstOrNull()?.uppercase() ?: "?"

/** Stable palette index for a name, so a contact keeps the same color everywhere. */
fun avatarColorIndex(name: String, paletteSize: Int): Int =
    Math.floorMod(name.trim().hashCode(), paletteSize)

@Composable
fun Avatar(name: String, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .background(AvatarPalette[avatarColorIndex(name, AvatarPalette.size)], CircleShape),
    ) {
        Text(
            avatarInitial(name),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = (size.value * 0.42f).sp),
        )
    }
}
