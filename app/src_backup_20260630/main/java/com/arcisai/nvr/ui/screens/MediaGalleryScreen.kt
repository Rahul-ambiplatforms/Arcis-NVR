package com.arcisai.nvr.ui.screens

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class GalleryItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val dateMs: Long,
    val isVideo: Boolean,
    val mimeType: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaGalleryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        items = withContext(Dispatchers.IO) { queryArcisMedia(context) }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Screenshots & Recordings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when {
            loading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            items.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp),
                ) {
                    Icon(
                        Icons.Default.Cameraswitch,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("No snapshots yet", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Use the camera and record buttons on the live screen to save snapshots and recordings here.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(items, key = { "${it.id}_${it.isVideo}" }) { item ->
                    GalleryCell(item) {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(item.uri, item.mimeType)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching { context.startActivity(intent) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryCell(item: GalleryItem, onClick: () -> Unit) {
    val context = LocalContext.current
    var thumb by remember(item.id, item.isVideo) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(item.id, item.isVideo) {
        thumb = withContext(Dispatchers.IO) { loadThumb(context, item) }
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(Color(0xFF1A1A1A))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = thumb
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = item.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        if (item.isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.55f), shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Icon(
                    Icons.Default.PlayCircle,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

private fun loadThumb(context: Context, item: GalleryItem): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.contentResolver.loadThumbnail(item.uri, Size(200, 200), null)
    } else {
        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
        context.contentResolver.openInputStream(item.uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }
}.getOrNull()

private fun queryArcisMedia(context: Context): List<GalleryItem> {
    val result = mutableListOf<GalleryItem>()

    // Photos — Pictures/ArcisNVR
    val imgProjection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.MIME_TYPE,
    )
    val pathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA
    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        imgProjection,
        "$pathCol LIKE ?", arrayOf("%ArcisNVR%"),
        "${MediaStore.Images.Media.DATE_ADDED} DESC",
    )?.use { c ->
        val iId   = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        val iDate = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
        val iMime = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
        while (c.moveToNext()) {
            val id  = c.getLong(iId)
            result += GalleryItem(
                id       = id,
                uri      = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                name     = c.getString(iName) ?: "",
                dateMs   = c.getLong(iDate) * 1_000L,
                isVideo  = false,
                mimeType = c.getString(iMime) ?: "image/jpeg",
            )
        }
    }

    // Videos — Movies/ArcisNVR
    val vidProjection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DATE_ADDED,
        MediaStore.Video.Media.MIME_TYPE,
    )
    val vidPathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        MediaStore.Video.Media.RELATIVE_PATH else MediaStore.Video.Media.DATA
    context.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        vidProjection,
        "$vidPathCol LIKE ?", arrayOf("%ArcisNVR%"),
        "${MediaStore.Video.Media.DATE_ADDED} DESC",
    )?.use { c ->
        val iId   = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
        val iDate = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
        val iMime = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
        while (c.moveToNext()) {
            val id  = c.getLong(iId)
            result += GalleryItem(
                id       = id,
                uri      = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                name     = c.getString(iName) ?: "",
                dateMs   = c.getLong(iDate) * 1_000L,
                isVideo  = true,
                mimeType = c.getString(iMime) ?: "video/mp4",
            )
        }
    }

    return result.sortedByDescending { it.dateMs }
}
