package com.filevault.pro.presentation.screen.duplicates

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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.filevault.pro.domain.model.DuplicateGroup
import com.filevault.pro.domain.model.FileType
import com.filevault.pro.domain.repository.FileRepository
import com.filevault.pro.util.FileUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class DuplicatesViewModel @Inject constructor(
    private val fileRepository: FileRepository
) : ViewModel() {

    private val _duplicates = MutableStateFlow<List<DuplicateGroup>>(emptyList())
    val duplicates: StateFlow<List<DuplicateGroup>> = _duplicates.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun findDuplicates() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _duplicates.value = fileRepository.getDuplicates()
            } catch (_: Exception) {}
            _isLoading.value = false
        }
    }
}

@Composable
fun DuplicatesScreen(
    viewModel: DuplicatesViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val duplicates by viewModel.duplicates.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Duplicate Finder", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                actions = {
                    TextButton(onClick = viewModel::findDuplicates) {
                        Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Scan")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading -> {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("Scanning for duplicates…", color = MaterialTheme.colorScheme.onSurface.copy(0.5f))
                    }
                }
                duplicates.isEmpty() -> {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Default.CompareArrows, null, Modifier.size(72.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(0.2f))
                        Spacer(Modifier.height(16.dp))
                        Text("No duplicates found", color = MaterialTheme.colorScheme.onSurface.copy(0.4f))
                        Spacer(Modifier.height(8.dp))
                        Text("Tap Scan to check for duplicate files",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(0.3f))
                    }
                }
                else -> {
                    LazyColumn(contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            Surface(shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(0.4f)) {
                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Info, null, Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.error)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Found ${duplicates.size} duplicate groups (${duplicates.sumOf { it.files.size }} files)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                        }
                        items(duplicates) { group ->
                            DuplicateGroupCard(group)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DuplicateGroupCard(group: DuplicateGroup) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CopyAll, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text("${group.files.size} identical files",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(FileUtils.formatSize(group.sizeBytes), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(0.5f))
            }
            Spacer(Modifier.height(12.dp))
            group.files.forEach { file ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (file.fileType == FileType.PHOTO) {
                        Box(Modifier.size(40.dp)) {
                            AsyncImage(File(file.path), null, contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize())
                        }
                    } else {
                        Icon(Icons.Default.InsertDriveFile, null, Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.outline)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(file.name, style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text(file.folderPath, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(0.4f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (file != group.files.last()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(0.3f))
                }
            }
        }
    }
}
