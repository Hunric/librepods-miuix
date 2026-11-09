package me.kavishdevar.librepods.presentation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import top.yukonga.miuix.kmp.window.WindowBottomSheet


@Composable
fun StyledBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    backdrop: LayerBackdrop,
    content: @Composable (innerBackdrop: LayerBackdrop, progress: Float) -> Unit
) {
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        label = "sheetProgress"
    )
    val innerBackdrop = rememberLayerBackdrop()
    WindowBottomSheet(
        show = visible,
        onDismissRequest = onDismiss,
        modifier = Modifier,
        content = { content(innerBackdrop, progress) }
    )
}
