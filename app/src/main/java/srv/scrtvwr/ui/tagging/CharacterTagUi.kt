package srv.scrtvwr.ui.tagging

import android.graphics.RectF
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
import srv.scrtvwr.tagging.FaceBounds
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

/** A thumbnail for one tagged image, used in a character's photo grid. */
@Composable
fun TaggedImageThumbnail(
    path: String,
    modifiedMillis: Long,
    modifier: Modifier = Modifier,
    dimension: Int = THUMBNAIL_DIMENSION,
) {
    val preview by produceState<PreviewResult?>(initialValue = null, key1 = path, key2 = modifiedMillis) {
        value = MediaPreviewLoader.loadImage(path, modifiedMillis, dimension)
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

/**
 * A character's avatar: their face, cut out of the image they were found in. The whole photo would be
 * the wrong picture at this size - a group shot squeezed into 48 dp says nothing about who this is -
 * so it is cropped to where tagging saw them, and shown whole only for covers tagged before boxes
 * were recorded.
 */
@Composable
fun CharacterCover(
    profile: CharacterProfile,
    modifier: Modifier = Modifier.size(48.dp),
) {
    val coverPath = profile.coverPath
    val coverBounds = profile.coverBounds
    val preview by produceState<PreviewResult?>(
        initialValue = null,
        key1 = coverPath,
        key2 = profile.coverModifiedMillis,
        key3 = coverBounds,
    ) {
        value = coverPath?.let { path ->
            if (coverBounds != null && coverBounds.isUsable) {
                MediaPreviewLoader.loadFaceCrop(
                    path = path,
                    modified = profile.coverModifiedMillis,
                    face = coverBounds.toRectF(),
                    maxDimension = COVER_DIMENSION,
                )
            } else {
                MediaPreviewLoader.loadImage(path, profile.coverModifiedMillis, COVER_DIMENSION)
            }
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

private fun FaceBounds.toRectF(): RectF = RectF(left, top, right, bottom)

private const val COVER_DIMENSION = 128
private const val THUMBNAIL_DIMENSION = 192
