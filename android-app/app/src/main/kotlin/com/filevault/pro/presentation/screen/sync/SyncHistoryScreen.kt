package com.filevault.pro.presentation.screen.sync

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.filevault.pro.domain.model.SyncHistory
import com.filevault.pro.domain.model.SyncStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SyncHistoryScreen(
    profileId: Long,
    viewModel: SyncViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val history by viewModel.getHistoryForProfile(profileId).collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync History", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }
            )
        }
    ) { padding ->
        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.History, null, Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(0.2f))
                    Spacer(Modifier.height(12.dp))
                    Text("No sync history yet", color = MaterialTheme.colorScheme.onSurface.copy(0.4f))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(history) { item ->
                    SyncHistoryCard(item)
                }
            }
        }
    }
}

@Composable
private fun SyncHistoryCard(history: SyncHistory) {
    val statusColor = when (history.status) {
        SyncStatus.SUCCESS -> Color(0xFF00AA44)
        SyncStatus.PARTIAL -> Color(0xFFFF8800)
        SyncStatus.FAILED -> MaterialTheme.colorScheme.error
        SyncStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (history.status) {
                    SyncStatus.SUCCESS -> Icons.Default.CheckCircle
                    SyncStatus.PARTIAL -> Icons.Default.Warning
                    SyncStatus.FAILED -> Icons.Default.Error
                    SyncStatus.IN_PROGRESS -> Icons.Default.Sync
                },
                null, modifier = Modifier.size(32.dp), tint = statusColor
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    history.status.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor
                )
                Text(
                    SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(history.startedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(0.5f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (history.filesSynced > 0) {
                        Text("✓ ${history.filesSynced} synced",
                            style = MaterialTheme.typography.labelSmall, color = Color(0xFF00AA44))
                    }
                    if (history.filesFailed > 0) {
                        Text("✗ ${history.filesFailed} failed",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (!history.errorMessage.isNullOrBlank()) {
                    Text(history.errorMessage, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error.copy(0.8f), maxLines = 2)
                }
            }
        }
    }
}
