package srv.scrtvwr.ui.tagging

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import srv.scrtvwr.R
import srv.scrtvwr.media.MediaPreviewLoader
import srv.scrtvwr.media.PreviewResult
import srv.scrtvwr.tagging.CharacterProfile
import srv.scrtvwr.tagging.ImageTags

/** Characters keep a default name until the user renames them. */
@Composable
fun CharacterProfile.displayLabel(): String = label ?: stringResource(R.string.character_number, ordinal)

/**
 * Corner badge on a thumbnail: how many faces the image holds, and the character names once tagging
 * has recognised them. Draws nothing for images without faces, so an untagged grid looks unchanged.
 */
@Composable
fun BoxScope.CharacterTagBadge(
    tags: ImageTags?,
    characterNames: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    if (tags == null || !tags.hasFaces) return
    val names = tags.characterIds.mapNotNull(characterNames::get)
    val label = when {
        names.isEmpty() -> stringResource(R.string.faces_detected, tags.faceCount)
        names.size == 1 -> names.first()
        else -> "${names.first()} +${names.size - 1}"
    }

    Surface(
        modifier = modifier
            .align(Alignment.BottomEnd)
            .padding(4.dp),
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Face,
                contentDescription = stringResource(R.string.faces_detected, tags.faceCount),
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The first image a character was found in, used as its avatar in the characters list. */
@Composable
fun CharacterCover(
    profile: CharacterProfile,
    modifier: Modifier = Modifier.size(48.dp),
) {
    val coverPath = profile.coverPath
    val preview by produceState<PreviewResult?>(
        initialValue = null,
        key1 = coverPath,
        key2 = profile.coverModifiedMillis,
    ) {
        value = coverPath?.let { path ->
            MediaPreviewLoader.loadImage(path, profile.coverModifiedMillis, COVER_DIMENSION)
        } ?: PreviewResult()
    }

    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = preview?.bitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                imageVector = Icons.Default.Face,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val COVER_DIMENSION = 128
