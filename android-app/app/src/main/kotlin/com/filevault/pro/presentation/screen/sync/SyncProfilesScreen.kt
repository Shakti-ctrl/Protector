package com.filevault.pro.presentation.screen.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.filevault.pro.domain.model.SyncProfile
import com.filevault.pro.domain.model.SyncType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SyncProfilesScreen(
    viewModel: SyncViewModel = hiltViewModel(),
    onAddProfile: () -> Unit,
    onEditProfile: (Long) -> Unit,
    onViewHistory: (Long) -> Unit,
    onBack: () -> Unit
) {
    val profiles by viewModel.profiles.collectAsState()
    var deleteTarget by remember { mutableStateOf<SyncProfile?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync Profiles", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddProfile,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Add Profile") }
            )
        }
    ) { padding ->
        if (profiles.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Sync, null, Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(0.2f))
                    Spacer(Modifier.height(16.dp))
                    Text("No sync profiles yet", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(0.4f))
                    Spacer(Modifier.height(8.dp))
                    Text("Tap + to add a Telegram or Email sync profile",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(0.3f))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(profiles, key = { it.id }) { profile ->
                    SyncProfileCard(
                        profile = profile,
                        onEdit = { onEditProfile(profile.id) },
                        onDelete = { deleteTarget = profile },
                        onToggleActive = { viewModel.setProfileActive(profile.id, !profile.isActive) },
                        onViewHistory = { onViewHistory(profile.id) },
                        onSyncNow = { viewModel.syncNow(profile.id) }
                    )
                }
            }
        }
    }

    deleteTarget?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete Profile") },
            text = { Text("Delete \"${profile.name}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.deleteProfile(profile.id); deleteTarget = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SyncProfileCard(
    profile: SyncProfile,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleActive: () -> Unit,
    onViewHistory: () -> Unit,
    onSyncNow: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (profile.isActive)
                MaterialTheme.colorScheme.primaryContainer.copy(0.2f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                        .background(
                            if (profile.type == SyncType.TELEGRAM) Color(0xFF0088CC).copy(0.2f)
                            else Color(0xFFEA4335).copy(0.2f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (profile.type == SyncType.TELEGRAM) Icons.Default.Send else Icons.Default.Email,
                        null, modifier = Modifier.size(24.dp),
                        tint = if (profile.type == SyncType.TELEGRAM) Color(0xFF0088CC) else Color(0xFFEA4335)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        buildString {
                            append(profile.type.name.lowercase().replaceFirstChar { it.uppercase() })
                            append(" · ")
                            append(if (profile.intervalHours == 0) "Manual" else "Every ${profile.intervalHours}h")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(0.5f)
                    )
                }
                Switch(checked = profile.isActive, onCheckedChange = { onToggleActive() })
            }

            if (profile.lastSyncAt != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Last sync: ${SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(profile.lastSyncAt))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(0.5f)
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(0.3f))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSyncNow, modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)) {
                    Icon(Icons.Default.Sync, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Sync Now")
                }
                IconButton(onClick = onViewHistory) { Icon(Icons.Default.History, null) }
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, null) }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
