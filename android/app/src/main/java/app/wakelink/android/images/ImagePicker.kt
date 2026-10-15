package app.wakelink.android.images

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.HideImage
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.wakelink.android.R
import app.wakelink.android.crypto.MediaKeyStore
import app.wakelink.android.network.MediaView
import app.wakelink.android.network.WakeLinkApi
import app.wakelink.android.network.orThrow
import app.wakelink.android.network.toUserMessage
import app.wakelink.android.sounds.ImageCache
import app.wakelink.android.sounds.SoundRef
import app.wakelink.android.sounds.isEncrypted
import app.wakelink.android.ui.components.StatusKind
import app.wakelink.android.ui.components.StatusPill
import java.io.File
import kotlinx.coroutines.launch

/**
 * Bottom sheet for choosing the picture shown on a contact's alarm screen:
 * none, one of this user's uploaded images, a new one from the gallery
 * (photos or GIFs), or a photo taken right now with the camera.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ImagePickerSheet(
    title: String,
    selected: String?,
    api: WakeLinkApi,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mediaKeys = remember { MediaKeyStore(context) }
    var images by remember { mutableStateOf<List<MediaView>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        try {
            images = api.listImages()
        } catch (e: Exception) {
            error = context.getString(R.string.images_load_failed, e.toUserMessage(context))
        }
    }
    LaunchedEffect(Unit) { reload() }

    fun uploadFrom(uri: Uri) {
        busy = true
        error = null
        scope.launch {
            try {
                when (val prepared = ImageUploads.prepare(context, uri)) {
                    is ImageUploads.Prepared.TooLarge -> error = context.getString(R.string.images_too_large)
                    is ImageUploads.Prepared.Unreadable -> error = context.getString(R.string.images_unreadable)
                    is ImageUploads.Prepared.Ok -> {
                        val created = ImageUploads.upload(context, api, prepared.name, prepared.bytes, prepared.mime)
                        reload()
                        onSelect(SoundRef.Custom(created.id, null).key)
                    }
                }
            } catch (e: Exception) {
                error = context.getString(R.string.images_upload_failed, e.toUserMessage(context))
            } finally {
                busy = false
            }
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) uploadFrom(uri)
    }
    val captureUri = remember {
        FileProvider.getUriForFile(context, "${context.packageName}.updates", ImageUploads.captureFile(context))
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (taken) uploadFrom(captureUri)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.images_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NoImageTile(selected == null) { onSelect(null) }
                images?.forEach { img ->
                    val ref = SoundRef.Custom(img.id, null)
                    val usable = !img.isEncrypted() || mediaKeys.get(img.id) != null
                    ImageTile(
                        image = img,
                        contentKey = mediaKeys.get(img.id),
                        checked = selected == ref.key,
                        onClick = { if (usable) onSelect(ref.key) },
                        onDelete = {
                            scope.launch {
                                try {
                                    api.deleteImage(img.id).orThrow()
                                    mediaKeys.remove(img.id)
                                    if (selected == ref.key) onSelect(null)
                                    reload()
                                } catch (e: Exception) {
                                    error = context.getString(R.string.images_delete_failed, e.toUserMessage(context))
                                }
                            }
                        },
                    )
                }
            }
            if (images == null && error == null) CircularProgressIndicator(modifier = Modifier.size(24.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.images_gallery), modifier = Modifier.padding(start = 6.dp))
                }
                OutlinedButton(enabled = !busy, onClick = { camera.launch(captureUri) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.images_camera), modifier = Modifier.padding(start = 6.dp))
                }
            }
            if (busy) CircularProgressIndicator(modifier = Modifier.size(24.dp))
            error?.let { StatusPill(StatusKind.ERROR, it, Modifier.fillMaxWidth()) }
        }
    }
}

private val TILE = 96.dp

@Composable
private fun tileBorder(checked: Boolean) =
    if (checked) BorderStroke(3.dp, MaterialTheme.colorScheme.secondary)
    else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun NoImageTile(checked: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(TILE)
            .clip(RoundedCornerShape(14.dp))
            .border(tileBorder(checked), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.HideImage, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.images_none), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ImageTile(image: MediaView, contentKey: ByteArray?, checked: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var file by remember(image.sha256) { mutableStateOf<File?>(null) }
    LaunchedEffect(image.sha256) {
        file = ImageCache.obtain(context, SoundRef.Custom(image.id, image.sha256), 15_000, contentKey)
    }
    val thumb = rememberThumbnail(file)
    Box(
        modifier = Modifier
            .size(TILE)
            .clip(RoundedCornerShape(14.dp))
            .border(tileBorder(checked), RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        thumb?.let { Image(it, contentDescription = image.name, contentScale = ContentScale.Crop, modifier = Modifier.size(TILE)) }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                .clickable(onClick = onDelete),
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = stringResource(R.string.images_delete),
                tint = MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
