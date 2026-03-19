package com.filevault.pro.presentation.screen.sync

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.filevault.pro.data.preferences.EncryptedPrefs
import com.filevault.pro.domain.model.FileType
import com.filevault.pro.domain.model.SyncProfile
import com.filevault.pro.domain.model.SyncType

@Composable
fun AddSyncProfileScreen(
    profileId: Long?,
    viewModel: SyncViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val encryptedPrefs = remember { EncryptedPrefs(context) }
    var name by remember { mutableStateOf("") }
    var syncType by remember { mutableStateOf(SyncType.TELEGRAM) }
    var intervalHours by remember { mutableStateOf(24) }
    var selectedTypes by remember { mutableStateOf(setOf<FileType>()) }

    // Telegram fields
    var botToken by remember { mutableStateOf("") }
    var chatId by remember { mutableStateOf("") }
    var captionTemplate by remember { mutableStateOf("{filename} | {date}") }

    // Email fields
    var smtpHost by remember { mutableStateOf("") }
    var smtpPort by remember { mutableStateOf("587") }
    var smtpUsername by remember { mutableStateOf("") }
    var smtpPassword by remember { mutableStateOf("") }
    var emailRecipient by remember { mutableStateOf("") }
    var subjectTemplate by remember { mutableStateOf("[FileVault] Sync {date} - {filecount} files") }
    var showPassword by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(profileId) {
        if (profileId != null) {
            val profile = viewModel.getProfileById(profileId) ?: return@LaunchedEffect
            name = profile.name
            syncType = profile.type
            intervalHours = profile.intervalHours
            selectedTypes = profile.fileTypeScope.toSet()
            when (profile.type) {
                SyncType.TELEGRAM -> {
                    botToken = profile.telegramBotTokenKey?.let { encryptedPrefs.get(it) } ?: ""
                    chatId = profile.telegramChatId ?: ""
                    captionTemplate = profile.telegramCaptionTemplate ?: "{filename} | {date}"
                }
                SyncType.EMAIL -> {
                    smtpHost = profile.smtpHost ?: ""
                    smtpPort = profile.smtpPort?.toString() ?: "587"
                    smtpUsername = profile.smtpUsername ?: ""
                    smtpPassword = profile.smtpPasswordKey?.let { encryptedPrefs.get(it) } ?: ""
                    emailRecipient = profile.emailRecipient ?: ""
                    subjectTemplate = profile.emailSubjectTemplate ?: "[FileVault] Sync {date}"
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (profileId == null) "Add Profile" else "Edit Profile", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                actions = {
                    if (isSaving) CircularProgressIndicator(Modifier.size(24.dp).padding(end = 16.dp), strokeWidth = 2.dp)
                    else TextButton(
                        onClick = {
                            isSaving = true
                            val profile = when (syncType) {
                                SyncType.TELEGRAM -> {
                                    val tokenKey = encryptedPrefs.generateKey("tg_token")
                                    encryptedPrefs.put(tokenKey, botToken)
                                    SyncProfile(
                                        id = profileId ?: 0L,
                                        name = name,
                                        type = SyncType.TELEGRAM,
                                        isActive = true,
                                        intervalHours = intervalHours,
                                        fileTypeScope = selectedTypes.toList(),
                                        telegramBotTokenKey = tokenKey,
                                        telegramChatId = chatId,
                                        telegramCaptionTemplate = captionTemplate
                                    )
                                }
                                SyncType.EMAIL -> {
                                    val pwKey = encryptedPrefs.generateKey("email_pw")
                                    encryptedPrefs.put(pwKey, smtpPassword)
                                    SyncProfile(
                                        id = profileId ?: 0L,
                                        name = name,
                                        type = SyncType.EMAIL,
                                        isActive = true,
                                        intervalHours = intervalHours,
                                        fileTypeScope = selectedTypes.toList(),
                                        smtpHost = smtpHost,
                                        smtpPort = smtpPort.toIntOrNull() ?: 587,
                                        smtpUsername = smtpUsername,
                                        smtpPasswordKey = pwKey,
                                        emailRecipient = emailRecipient,
                                        emailSubjectTemplate = subjectTemplate
                                    )
                                }
                            }
                            viewModel.saveProfile(profile) { onBack() }
                        },
                        enabled = name.isNotBlank()
                    ) {
                        Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Save")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize()
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AppTextField(label = "Profile Name", value = name, onValueChange = { name = it },
                placeholder = "e.g. My Telegram Backup")

            SectionCard(title = "Sync Type") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(SyncType.TELEGRAM, SyncType.EMAIL).forEach { type ->
                        FilterChip(
                            selected = syncType == type,
                            onClick = { syncType = type },
                            label = { Text(type.name.lowercase().replaceFirstChar { it.uppercase() }) },
                            leadingIcon = {
                                if (syncType == type) Icon(Icons.Default.Check, null, Modifier.size(14.dp))
                            }
                        )
                    }
                }
            }

            SectionCard(title = "Sync Schedule") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Manual", 1 to "1h", 6 to "6h", 12 to "12h", 24 to "24h").forEach { (h, label) ->
                        FilterChip(
                            selected = intervalHours == h,
                            onClick = { intervalHours = h },
                            label = { Text(label) }
                        )
                    }
                }
            }

            SectionCard(title = "File Types to Sync") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row {
                        OutlinedButton(
                            onClick = {
                                selectedTypes = if (selectedTypes.size == FileType.values().size) emptySet()
                                              else FileType.values().toSet()
                            },
                            shape = RoundedCornerShape(8.dp), modifier = Modifier.height(36.dp)
                        ) {
                            Text(if (selectedTypes.size == FileType.values().size) "Deselect All" else "All Types",
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FileType.values().take(4).forEach { type ->
                            FilterChip(
                                selected = type in selectedTypes,
                                onClick = {
                                    selectedTypes = if (type in selectedTypes) selectedTypes - type else selectedTypes + type
                                },
                                label = { Text(type.name.lowercase().take(5), style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FileType.values().drop(4).forEach { type ->
                            FilterChip(
                                selected = type in selectedTypes,
                                onClick = {
                                    selectedTypes = if (type in selectedTypes) selectedTypes - type else selectedTypes + type
                                },
                                label = { Text(type.name.lowercase().take(5), style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            }

            when (syncType) {
                SyncType.TELEGRAM -> {
                    SectionCard(title = "Telegram Bot Settings") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            AppTextField("Bot Token", botToken, { botToken = it }, "1234567890:ABC...",
                                visualTransformation = PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { showPassword = !showPassword }) {
                                        Icon(if (showPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff, null)
                                    }
                                })
                            AppTextField("Chat ID", chatId, { chatId = it }, "-1001234567890 or @username")
                            AppTextField("Caption Template", captionTemplate, { captionTemplate = it },
                                "{filename} | {date} | {size}")
                            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(0.4f)) {
                                Text(
                                    "Tokens: {filename} {date} {size} {folder} {type}",
                                    modifier = Modifier.padding(8.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(0.7f)
                                )
                            }
                        }
                    }
                }
                SyncType.EMAIL -> {
                    SectionCard(title = "SMTP Settings") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                AppTextField("SMTP Host", smtpHost, { smtpHost = it }, "smtp.gmail.com", modifier = Modifier.weight(2f))
                                AppTextField("Port", smtpPort, { smtpPort = it }, "587",
                                    keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                            }
                            AppTextField("Username", smtpUsername, { smtpUsername = it }, "user@gmail.com",
                                keyboardType = KeyboardType.Email)
                            AppTextField(
                                "Password", smtpPassword, { smtpPassword = it }, "App Password",
                                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { showPassword = !showPassword }) {
                                        Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                                    }
                                }
                            )
                            AppTextField("Recipient", emailRecipient, { emailRecipient = it }, "recipient@example.com",
                                keyboardType = KeyboardType.Email)
                            AppTextField("Subject Template", subjectTemplate, { subjectTemplate = it })
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun AppTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurface.copy(0.3f)) },
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        singleLine = true
    )
}

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(0.4f))
        ) {
            Column(modifier = Modifier.padding(16.dp), content = content)
        }
    }
}
