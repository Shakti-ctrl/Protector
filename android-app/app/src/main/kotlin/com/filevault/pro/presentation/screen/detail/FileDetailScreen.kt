package com.filevault.pro.presentation.screen.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.filevault.pro.domain.model.FileEntry
import com.filevault.pro.domain.model.FileType
import com.filevault.pro.util.FileUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FileDetailScreen(
    path: String,
    viewModel: FileDetailViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val file by viewModel.file.collectAsState()

    LaunchedEffect(path) { viewModel.loadFile(path) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file?.name ?: "Details", fontWeight = FontWeight.SemiBold, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    file?.let { f ->
                        IconButton(onClick = {
                            val fileUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(f.path))
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = f.mimeType
                                putExtra(Intent.EXTRA_STREAM, fileUri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share ${f.name}"))
                        }) { Icon(Icons.Default.Share, "Share") }

                        IconButton(onClick = {
                            val fileUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(f.path))
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(fileUri, f.mimeType)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching { context.startActivity(intent) }
                        }) { Icon(Icons.Default.OpenInNew, "Open") }
                    }
                }
            )
        }
    ) { padding ->
        if (file == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val f = file!!
            Column(
                modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
            ) {
                // Preview area
                PreviewSection(f)

                // Metadata
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(f.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(f.folderPath, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(0.5f))
                    Spacer(Modifier.height(20.dp))

                    MetadataSection(
                        items = buildList {
                            add(MetadataItem("Size", FileUtils.formatSize(f.sizeBytes), Icons.Default.Storage))
                            add(MetadataItem("Type", f.mimeType, Icons.Default.Description))
                            add(MetadataItem("Modified", formatDate(f.lastModified), Icons.Default.Schedule))
                            add(MetadataItem("Added", formatDate(f.dateAdded), Icons.Default.AddCircle))
                            if (f.width != null && f.height != null) {
                                add(MetadataItem("Dimensions", "${f.width} × ${f.height} px", Icons.Default.AspectRatio))
                            }
                            if (f.durationMs != null) {
                                add(MetadataItem("Duration", FileUtils.formatDuration(f.durationMs), Icons.Default.Timer))
                            }
                            if (f.cameraMake != null || f.cameraModel != null) {
                                add(MetadataItem("Camera", "${f.cameraMake ?: ""} ${f.cameraModel ?: ""}".trim(), Icons.Default.Camera))
                            }
                            if (f.dateTaken != null) {
                                add(MetadataItem("Date Taken", formatDate(f.dateTaken), Icons.Default.CalendarToday))
                            }
                            if (f.hasGps) add(MetadataItem("GPS", "Location data available", Icons.Default.LocationOn))
                        }
                    )

                    Spacer(Modifier.height(20.dp))
                    SyncStatusCard(file = f, onToggleIgnore = { viewModel.toggleSyncIgnore(f.path, !f.isSyncIgnored) })

                    if (f.isDeletedFromDevice) {
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Warning, null, Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("File Missing from Device",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onErrorContainer)
                                    Text(
                                        if (f.lastSyncedAt != null)
                                            "This file has been deleted from your device. It may be available in your sync destination."
                                        else
                                            "This file has been deleted from your device and has not been synced.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer.copy(0.8f)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    if (f.isHidden) {
                        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.VisibilityOff, null, Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer)
                                Spacer(Modifier.width(6.dp))
                                Text("Hidden file", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer)
                            }
                        }
                    }
                    Spacer(Modifier.height(40.dp))
                }
            }
        }
    }
}

@Composable
private fun PreviewSection(file: FileEntry) {
    Box(
        modifier = Modifier.fillMaxWidth().height(280.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        when (file.fileType) {
            FileType.PHOTO, FileType.VIDEO -> {
                AsyncImage(
                    model = File(file.path),
                    contentDescription = file.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
                if (file.fileType == FileType.VIDEO) {
                    Box(
                        modifier = Modifier.size(64.dp).clip(CircleShape)
                            .background(Color.Black.copy(0.55f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(40.dp))
                    }
                }
            }
            else -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier.size(80.dp).clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            file.name.substringAfterLast(".").uppercase().take(4),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(file.fileType.name, style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(0.5f))
                }
            }
        }
    }
}

@Composable
private fun MetadataSection(items: List<MetadataItem>) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(0.5f))
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            items.forEachIndexed { index, item ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(item.icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(item.label, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(0.5f),
                        modifier = Modifier.width(90.dp))
                    Text(item.value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f))
                }
                if (index < items.size - 1) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(0.3f))
                }
            }
        }
    }
}

@Composable
private fun SyncStatusCard(file: FileEntry, onToggleIgnore: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (file.lastSyncedAt != null)
                Color(0xFF006A4E).copy(0.1f) else MaterialTheme.colorScheme.surfaceVariant.copy(0.5f)
        )
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (file.lastSyncedAt != null) Icons.Default.CloudDone else Icons.Default.CloudOff,
                null, modifier = Modifier.size(24.dp),
                tint = if (file.lastSyncedAt != null) Color(0xFF00AA44) else MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (file.lastSyncedAt != null) "Synced" else "Not synced",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold
                )
                if (file.lastSyncedAt != null) {
                    Text(formatDate(file.lastSyncedAt), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(0.5f))
                }
            }
            TextButton(onClick = onToggleIgnore) {
                Text(if (file.isSyncIgnored) "Allow Sync" else "Ignore",
                    color = if (file.isSyncIgnored) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
        }
    }
}

data class MetadataItem(val label: String, val value: String, val icon: ImageVector)

private fun formatDate(ms: Long): String =
    SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(ms))
