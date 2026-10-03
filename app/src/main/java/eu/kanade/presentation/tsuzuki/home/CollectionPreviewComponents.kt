package eu.kanade.presentation.tsuzuki.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tachiyomi.domain.tsuzuki.home.model.HomeFolderTile

@Composable
internal fun FolderTile(
    folder: HomeFolderTile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 200.dp,
) {
    val backgroundColor = remember(folder.folderId) {
        folderTileColor(folder.folderId)
    }

    Column(
        modifier = modifier
            .width(width)
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(FOLDER_TILE_ASPECT_RATIO)
                .clip(MaterialTheme.shapes.large)
                .background(backgroundColor),
        )

        Text(
            text = folder.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun folderTileColor(folderId: String): Color {
    val hue = Math.floorMod(folderId.hashCode(), 360).toFloat()
    return Color.hsl(
        hue = hue,
        saturation = 0.34f,
        lightness = 0.30f,
    )
}

private const val FOLDER_TILE_ASPECT_RATIO = 16f / 9f
