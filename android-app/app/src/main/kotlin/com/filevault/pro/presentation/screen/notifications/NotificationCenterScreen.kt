package com.filevault.pro.presentation.screen.notifications

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filevault.pro.domain.model.AppNotification
import com.filevault.pro.domain.model.NotificationType
import com.filevault.pro.domain.repository.NotificationRepository
import com.filevault.pro.domain.repository.SyncRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import androidx.hilt.navigation.compose.hiltViewModel

object NotificationStore {
    var repository: NotificationRepository? = null
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )

    fun add(notification: AppNotification) {
        val repo = repository ?: return
        scope.launch {
            repo.add(notification)
        }
    }
}

@HiltViewModel
class NotificationCenterViewModel @Inject constructor(
    private val notificationRepository: NotificationRepository,
    private val syncRepository: SyncRepository
) : ViewModel() {

    val notifications: StateFlow<List<AppNotification>> = notificationRepository.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unreadCount: StateFlow<Int> = notificationRepository.getUnreadCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    init {
        NotificationStore.repository = notificationRepository

        viewModelScope.launch {
            syncRepository.getAllHistory().collectLatest { historyList ->
                val existing = notifications.value.map { it.id }.toSet()
                historyList.forEach { history ->
                    if (history.completedAt != null && history.startedAt !in existing) {
                        val type = when (history.status.name) {
                            "SUCCESS" -> NotificationType.SYNC
                            "FAILED" -> NotificationType.ERROR
                            else -> NotificationType.SYNC
                        }
                        notificationRepository.add(
                            AppNotification(
                                id = history.startedAt,
                                type = type,
                                title = "Sync: ${history.profileName}",
                                message = when (history.status.name) {
                                    "SUCCESS" -> "${history.filesSynced} files synced successfully"
                                    "FAILED" -> "Sync failed: ${history.errorMessage ?: "Unknown error"}"
                                    "PARTIAL" -> "${history.filesSynced} synced, ${history.filesFailed} failed"
                                    else -> "Sync completed"
                                },
                                timestamp = history.completedAt ?: history.startedAt
                            )
                        )
                    }
                }
            }
        }
    }

    fun markAllRead() = viewModelScope.launch { notificationRepository.markAllRead() }
    fun clearAll() = viewModelScope.launch { notificationRepository.clearAll() }
}

@Composable
fun NotificationCenterScreen(
    viewModel: NotificationCenterViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val notifications by viewModel.notifications.collectAsState()

    LaunchedEffect(Unit) { viewModel.markAllRead() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notifications", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                },
                actions = {
                    if (notifications.isNotEmpty()) {
                        TextButton(onClick = viewModel::clearAll) {
                            Text("Clear All")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (notifications.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.NotificationsNone,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(0.2f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "No notifications yet",
                        color = MaterialTheme.colorScheme.onSurface.copy(0.4f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        "Scan and sync activity will appear here",
                        color = MaterialTheme.colorScheme.onSurface.copy(0.3f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(notifications, key = { it.id }) { notification ->
                    NotificationCard(notification)
                }
            }
        }
    }
}

@Composable
private fun NotificationCard(notification: AppNotification) {
    val (icon, color) = when (notification.type) {
        NotificationType.SCAN -> Icons.Default.Search to MaterialTheme.colorScheme.primary
        NotificationType.SYNC -> Icons.Default.Sync to MaterialTheme.colorScheme.secondary
        NotificationType.ERROR -> Icons.Default.Error to MaterialTheme.colorScheme.error
        NotificationType.INFO -> Icons.Default.Info to MaterialTheme.colorScheme.tertiary
    }
    val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (!notification.isRead)
                MaterialTheme.colorScheme.primaryContainer.copy(0.3f)
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(color.copy(0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        notification.title,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        sdf.format(Date(notification.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(0.5f),
                        fontSize = 10.sp
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    notification.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(0.7f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
