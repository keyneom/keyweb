package app.keyweb.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.keyweb.vault.Attachment
import app.keyweb.vault.ItemRecord
import app.keyweb.vault.VaultState
import app.keyweb.vault.attachments
import app.keyweb.vault.field

/**
 * Files kept on a password, and looking at one.
 *
 * The counterpart of the web's `FileViewer.tsx` and its file rows. Images get a
 * thumbnail because a column of identical paperclips is useless for telling one
 * scan from another, and a scan is the thing people most often keep here.
 */

/** Decoded once per set of bytes rather than on every recomposition. */
@Composable
private fun rememberBitmap(data: String?): androidx.compose.ui.graphics.ImageBitmap? =
    remember(data) {
        if (data.isNullOrEmpty()) {
            null
        } else {
            runCatching {
                val bytes = Base64.decode(data, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }

/**
 * SVG is deliberately absent from what counts as viewable.
 *
 * It is a document that can carry script and fetch remote content, and the
 * platform decoder will not render it anyway — so it is offered as a file to
 * save rather than something the app draws.
 */
private fun isViewableImage(type: String?): Boolean =
    type != null && type.startsWith("image/") && type != "image/svg+xml"

@Composable
fun FilesSection(
    item: ItemRecord,
    state: VaultState,
    onOpen: (String) -> Unit,
    onAttach: () -> Unit,
    onRemove: (String) -> Unit,
) {
    val colors = LocalKeywebStatus.current
    val files = item.attachments()

    if (files.isNotEmpty()) {
        Text("Files", style = MaterialTheme.typography.labelLarge)
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
            Column {
                files.forEach { file ->
                    FileRow(file, state, onOpen, onRemove)
                    HorizontalDivider(color = colors.line)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }

    SecondaryButton(
        if (files.isEmpty()) "Attach a file" else "Attach another file",
        onAttach,
    )
    Text(
        "Scans, recovery-code sheets, key files. They are locked with everything else and go " +
            "wherever this keyring goes.",
        color = colors.muted,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
    )
}

@Composable
private fun FileRow(
    file: Attachment,
    state: VaultState,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val colors = LocalKeywebStatus.current
    val blob = state.items[file.blobId]
    val type = blob?.field("type")
    val bitmap = rememberBitmap(if (isViewableImage(type)) blob?.field("secret:data") else null)
    val bytes = blob?.field("size")?.toLongOrNull() ?: 0L

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = file.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)),
            )
        } else {
            Text(
                file.name.substringAfterLast('.', "").take(3).uppercase().ifEmpty { "FILE" },
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.size(42.dp).wrapText(),
            )
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(file.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (blob == null) "Still arriving from your other device" else humanSize(bytes),
                color = colors.muted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        TextButton(onClick = { onOpen(file.blobId) }, enabled = blob != null) { Text("Open") }
        TextButton(onClick = { onRemove(file.blobId) }) {
            Text("Remove", color = colors.risk)
        }
    }
}

/** Keeps a short label centred in the space a thumbnail would take. */
private fun Modifier.wrapText(): Modifier = this.padding(top = 12.dp)

private fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes bytes"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

/**
 * Looking at a file kept in the vault.
 *
 * Buttons as well as pinch. A zoom that can only be reached by pinching does
 * not exist for somebody using one hand, and reading a scanned document is
 * exactly when it is needed.
 */
@Composable
fun FileViewerScreen(
    name: String,
    type: String?,
    data: String?,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    val colors = LocalKeywebStatus.current
    val bitmap = rememberBitmap(if (isViewableImage(type)) data else null)
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            TextButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Text(" Back", style = MaterialTheme.typography.bodyLarge)
            }
            Text(name, style = MaterialTheme.typography.titleLarge)
            Text(
                if (bitmap != null) "${(scale * 100).toInt()}%" else "Kept in your vault",
                color = colors.muted,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            if (bitmap != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 6f)
                                if (scale > 1f) {
                                    offsetX += pan.x
                                    offsetY += pan.y
                                } else {
                                    offsetX = 0f
                                    offsetY = 0f
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY,
                        ),
                    )
                }
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Make it bigger", { scale = (scale * 1.5f).coerceAtMost(6f) })
                Spacer(Modifier.height(8.dp))
                SecondaryButton(
                    "Make it smaller",
                    {
                        scale = (scale / 1.5f).coerceAtLeast(1f)
                        if (scale == 1f) {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    },
                )
            } else {
                Text(
                    "Keyweb can't show this kind of file. It is safely in your vault — save it " +
                        "to this phone to open it in whatever app normally handles it.",
                    color = colors.muted,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            Spacer(Modifier.height(12.dp))
            PrimaryButton("Save it to this phone", onSave)
            Text(
                "Saved files leave the vault. Anything that can read your downloads can read " +
                    "this.",
                color = colors.attention,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp, bottom = 24.dp),
            )
        }
    }
}
