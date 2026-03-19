package com.filevault.pro.data.repository

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.filevault.pro.data.local.dao.ExcludedFolderDao
import com.filevault.pro.data.local.dao.FileEntryDao
import com.filevault.pro.data.local.entity.FileEntryEntity
import com.filevault.pro.data.local.entity.toDomain
import com.filevault.pro.data.local.entity.toEntity
import com.filevault.pro.domain.model.CatalogStats
import com.filevault.pro.domain.model.DuplicateGroup
import com.filevault.pro.domain.model.FileEntry
import com.filevault.pro.domain.model.FileFilter
import com.filevault.pro.domain.model.FileType
import com.filevault.pro.domain.model.FolderInfo
import com.filevault.pro.domain.model.SortField
import com.filevault.pro.domain.model.SortOrder
import com.filevault.pro.domain.repository.FileRepository
import com.filevault.pro.util.FileUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileEntryDao: FileEntryDao,
    private val excludedFolderDao: ExcludedFolderDao
) : FileRepository {

    private companion object {
        const val TAG = "FileRepository"
    }

    override fun getAllPhotos(sortOrder: SortOrder, filter: FileFilter): Flow<List<FileEntry>> =
        callbackFlow {
            fun doQuery(): List<FileEntry> {
                val list = mutableListOf<FileEntry>()
                val sortCol = when (sortOrder.field) {
                    SortField.DATE_TAKEN -> MediaStore.Images.Media.DATE_TAKEN
                    SortField.DATE_ADDED -> MediaStore.Images.Media.DATE_ADDED
                    SortField.NAME -> MediaStore.Images.Media.DISPLAY_NAME
                    SortField.SIZE -> MediaStore.Images.Media.SIZE
                    else -> MediaStore.Images.Media.DATE_MODIFIED
                }
                val sortDir = if (sortOrder.ascending) "ASC" else "DESC"
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATE_MODIFIED,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.MIME_TYPE,
                    MediaStore.Images.Media.WIDTH,
                    MediaStore.Images.Media.HEIGHT,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_TAKEN
                )
                try {
                    context.contentResolver.query(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        projection, null, null, "$sortCol $sortDir"
                    )?.use { cursor ->
                        val idxData = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                        val idxName = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                        val idxSize = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                        val idxMod = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
                        val idxAdd = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                        val idxMime = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                        val idxW = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
                        val idxH = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
                        val idxBkt = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                        val idxTkn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                        while (cursor.moveToNext()) {
                            val path = if (idxData >= 0) cursor.getString(idxData) else null
                            if (path.isNullOrBlank()) continue
                            val name = if (idxName >= 0) cursor.getString(idxName)
                                ?: File(path).name else File(path).name
                            if (!filter.showHidden && (name.startsWith(".") || path.contains("/."))) continue
                            val q = filter.searchQuery
                            if (q.isNotBlank() && !name.contains(q, ignoreCase = true)) continue
                            val size = if (idxSize >= 0) cursor.getLong(idxSize) else 0L
                            val mod = if (idxMod >= 0) cursor.getLong(idxMod) * 1000L else 0L
                            val added = if (idxAdd >= 0) cursor.getLong(idxAdd) * 1000L
                                else System.currentTimeMillis()
                            val mime = if (idxMime >= 0) cursor.getString(idxMime).orEmpty() else ""
                            val width = if (idxW >= 0) cursor.getInt(idxW).takeIf { it > 0 } else null
                            val height = if (idxH >= 0) cursor.getInt(idxH).takeIf { it > 0 } else null
                            val bucket = if (idxBkt >= 0) cursor.getString(idxBkt).orEmpty() else ""
                            val dateTaken = if (idxTkn >= 0) cursor.getLong(idxTkn).takeIf { it > 0 } else null
                            list += FileEntry(
                                path = path, name = name,
                                folderPath = File(path).parent ?: "", folderName = bucket,
                                sizeBytes = size, lastModified = mod, mimeType = mime,
                                fileType = FileType.PHOTO,
                                width = width, height = height, durationMs = null,
                                orientation = null, cameraMake = null, cameraModel = null,
                                hasGps = false, dateTaken = dateTaken, dateAdded = added,
                                isHidden = name.startsWith("."),
                                contentHash = null, thumbnailCachePath = null,
                                isSyncIgnored = false, lastSyncedAt = null,
                                isDeletedFromDevice = false
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "getAllPhotos MediaStore query failed: ${e.message}", e)
                }
                return list
            }

            launch(Dispatchers.IO) {
                try { send(doQuery()) } catch (e: Exception) { Log.e(TAG, "send photos failed", e) }
            }

            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    launch(Dispatchers.IO) {
                        try { trySend(doQuery()) } catch (e: Exception) { Log.e(TAG, "trySend photos failed", e) }
                    }
                }
            }
            context.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer
            )
            awaitClose { context.contentResolver.unregisterContentObserver(observer) }
        }

    override fun getAllVideos(sortOrder: SortOrder, filter: FileFilter): Flow<List<FileEntry>> =
        callbackFlow {
            fun doQuery(): List<FileEntry> {
                val list = mutableListOf<FileEntry>()
                val sortCol = when (sortOrder.field) {
                    SortField.DATE_ADDED -> MediaStore.Video.Media.DATE_ADDED
                    SortField.NAME -> MediaStore.Video.Media.DISPLAY_NAME
                    SortField.SIZE -> MediaStore.Video.Media.SIZE
                    SortField.DURATION -> MediaStore.Video.Media.DURATION
                    else -> MediaStore.Video.Media.DATE_MODIFIED
                }
                val sortDir = if (sortOrder.ascending) "ASC" else "DESC"
                val projection = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DATA,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DATE_MODIFIED,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.Video.Media.MIME_TYPE,
                    MediaStore.Video.Media.WIDTH,
                    MediaStore.Video.Media.HEIGHT,
                    MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION
                )
                try {
                    context.contentResolver.query(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        projection, null, null, "$sortCol $sortDir"
                    )?.use { cursor ->
                        val idxData = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                        val idxName = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                        val idxSize = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                        val idxMod = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
                        val idxAdd = cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
                        val idxMime = cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)
                        val idxW = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
                        val idxH = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)
                        val idxBkt = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                        val idxDur = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                        while (cursor.moveToNext()) {
                            val path = if (idxData >= 0) cursor.getString(idxData) else null
                            if (path.isNullOrBlank()) continue
                            val name = if (idxName >= 0) cursor.getString(idxName)
                                ?: File(path).name else File(path).name
                            if (!filter.showHidden && (name.startsWith(".") || path.contains("/."))) continue
                            val q = filter.searchQuery
                            if (q.isNotBlank() && !name.contains(q, ignoreCase = true)) continue
                            val size = if (idxSize >= 0) cursor.getLong(idxSize) else 0L
                            val mod = if (idxMod >= 0) cursor.getLong(idxMod) * 1000L else 0L
                            val added = if (idxAdd >= 0) cursor.getLong(idxAdd) * 1000L
                                else System.currentTimeMillis()
                            val mime = if (idxMime >= 0) cursor.getString(idxMime).orEmpty() else ""
                            val width = if (idxW >= 0) cursor.getInt(idxW).takeIf { it > 0 } else null
                            val height = if (idxH >= 0) cursor.getInt(idxH).takeIf { it > 0 } else null
                            val bucket = if (idxBkt >= 0) cursor.getString(idxBkt).orEmpty() else ""
                            val duration = if (idxDur >= 0) cursor.getLong(idxDur).takeIf { it > 0 } else null
                            list += FileEntry(
                                path = path, name = name,
                                folderPath = File(path).parent ?: "", folderName = bucket,
                                sizeBytes = size, lastModified = mod, mimeType = mime,
                                fileType = FileType.VIDEO,
                                width = width, height = height, durationMs = duration,
                                orientation = null, cameraMake = null, cameraModel = null,
                                hasGps = false, dateTaken = null, dateAdded = added,
                                isHidden = name.startsWith("."),
                                contentHash = null, thumbnailCachePath = null,
                                isSyncIgnored = false, lastSyncedAt = null,
                                isDeletedFromDevice = false
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "getAllVideos MediaStore query failed: ${e.message}", e)
                }
                return list
            }

            launch(Dispatchers.IO) {
                try { send(doQuery()) } catch (e: Exception) { Log.e(TAG, "send videos failed", e) }
            }

            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    launch(Dispatchers.IO) {
                        try { trySend(doQuery()) } catch (e: Exception) { Log.e(TAG, "trySend videos failed", e) }
                    }
                }
            }
            context.contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer
            )
            awaitClose { context.contentResolver.unregisterContentObserver(observer) }
        }

    override fun getAllFiles(sortOrder: SortOrder, filter: FileFilter): Flow<List<FileEntry>> =
        callbackFlow {
            fun doQuery(): List<FileEntry> {
                val list = mutableListOf<FileEntry>()
                val sortCol = when (sortOrder.field) {
                    SortField.DATE_ADDED -> MediaStore.Files.FileColumns.DATE_ADDED
                    SortField.NAME -> MediaStore.Files.FileColumns.DISPLAY_NAME
                    SortField.SIZE -> MediaStore.Files.FileColumns.SIZE
                    else -> MediaStore.Files.FileColumns.DATE_MODIFIED
                }
                val sortDir = if (sortOrder.ascending) "ASC" else "DESC"
                val projection = buildList {
                    add(MediaStore.Files.FileColumns._ID)
                    add(MediaStore.Files.FileColumns.DATA)
                    add(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    add(MediaStore.Files.FileColumns.SIZE)
                    add(MediaStore.Files.FileColumns.DATE_MODIFIED)
                    add(MediaStore.Files.FileColumns.DATE_ADDED)
                    add(MediaStore.Files.FileColumns.MIME_TYPE)
                    add(MediaStore.Files.FileColumns.MEDIA_TYPE)
                    add(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        add(MediaStore.Files.FileColumns.RELATIVE_PATH)
                    }
                }.toTypedArray()

                val seenPaths = HashSet<String>()
                try {
                    context.contentResolver.query(
                        MediaStore.Files.getContentUri("external"),
                        projection,
                        "${MediaStore.Files.FileColumns.SIZE} > 0",
                        null,
                        "$sortCol $sortDir"
                    )?.use { cursor ->
                        val idxData = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                        val idxName = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                        val idxSize = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                        val idxMod = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
                        val idxAdd = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_ADDED)
                        val idxMime = cursor.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)
                        val idxMediaType = cursor.getColumnIndex(MediaStore.Files.FileColumns.MEDIA_TYPE)
                        val idxBkt = cursor.getColumnIndex(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
                        while (cursor.moveToNext()) {
                            val path = if (idxData >= 0) cursor.getString(idxData) else null
                            if (path.isNullOrBlank() || path in seenPaths) continue
                            seenPaths += path
                            val name = if (idxName >= 0) cursor.getString(idxName) ?: File(path).name
                                else File(path).name
                            if (!filter.showHidden && (name.startsWith(".") || path.contains("/."))) continue
                            val q = filter.searchQuery
                            if (q.isNotBlank() && !name.contains(q, ignoreCase = true)) continue
                            val size = if (idxSize >= 0) cursor.getLong(idxSize) else 0L
                            val mod = if (idxMod >= 0) cursor.getLong(idxMod) * 1000L else 0L
                            val added = if (idxAdd >= 0) cursor.getLong(idxAdd) * 1000L
                                else System.currentTimeMillis()
                            val mime = if (idxMime >= 0) cursor.getString(idxMime).orEmpty() else ""
                            val bucket = if (idxBkt >= 0) cursor.getString(idxBkt).orEmpty() else ""
                            val mediaType = if (idxMediaType >= 0) cursor.getInt(idxMediaType) else 0
                            val fileType = when {
                                mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> FileType.PHOTO
                                mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> FileType.VIDEO
                                mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> FileType.AUDIO
                                mime.isNotBlank() -> FileType.fromMimeType(mime)
                                else -> FileType.fromExtension(File(path).extension)
                            }
                            if (filter.fileTypes.isNotEmpty() && fileType !in filter.fileTypes) continue
                            list += FileEntry(
                                path = path, name = name,
                                folderPath = File(path).parent ?: "", folderName = bucket,
                                sizeBytes = size, lastModified = mod, mimeType = mime,
                                fileType = fileType, width = null, height = null, durationMs = null,
                                orientation = null, cameraMake = null, cameraModel = null,
                                hasGps = false, dateTaken = null, dateAdded = added,
                                isHidden = name.startsWith("."),
                                contentHash = null, thumbnailCachePath = null,
                                isSyncIgnored = false, lastSyncedAt = null,
                                isDeletedFromDevice = false
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "getAllFiles MediaStore query failed: ${e.message}", e)
                }
                val sorted = when (sortOrder.field) {
                    SortField.DATE_MODIFIED -> list.sortedBy { it.lastModified }
                    SortField.DATE_ADDED -> list.sortedBy { it.dateAdded }
                    SortField.NAME -> list.sortedBy { it.name.lowercase() }
                    SortField.SIZE -> list.sortedBy { it.sizeBytes }
                    SortField.FOLDER -> list.sortedBy { it.folderName.lowercase() }
                    else -> list
                }
                return if (sortOrder.ascending) sorted else sorted.reversed()
            }

            launch(Dispatchers.IO) {
                try { send(doQuery()) } catch (e: Exception) { Log.e(TAG, "send files failed", e) }
            }

            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    launch(Dispatchers.IO) {
                        try { trySend(doQuery()) } catch (e: Exception) { Log.e(TAG, "trySend files failed", e) }
                    }
                }
            }
            context.contentResolver.registerContentObserver(
                MediaStore.Files.getContentUri("external"), true, observer
            )
            awaitClose { context.contentResolver.unregisterContentObserver(observer) }
        }

    override fun getStats(): Flow<CatalogStats> = callbackFlow {
        fun doQuery(): CatalogStats {
            fun qCount(uri: Uri): Int {
                val c = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                val n = c?.count ?: 0; c?.close(); return n
            }
            return try {
                val photos = qCount(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                val videos = qCount(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                val audio = qCount(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
                val total = qCount(MediaStore.Files.getContentUri("external"))
                val docs = (total - photos - videos - audio).coerceAtLeast(0)
                CatalogStats(
                    totalFiles = total, totalPhotos = photos, totalVideos = videos,
                    totalAudio = audio, totalDocuments = docs, totalOther = 0,
                    totalSizeBytes = 0L, lastScanAt = null, lastSyncAt = null
                )
            } catch (e: Exception) {
                Log.e(TAG, "getStats MediaStore query failed: ${e.message}", e)
                CatalogStats(0, 0, 0, 0, 0, 0, 0L, null, null)
            }
        }

        launch(Dispatchers.IO) { send(doQuery()) }

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                launch(Dispatchers.IO) { trySend(doQuery()) }
            }
        }
        context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        context.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }

    override fun getFolders(): Flow<List<FolderInfo>> =
        fileEntryDao.getAllFolders().map { rows ->
            rows.map { row ->
                FolderInfo(
                    path = row.folderPath,
                    name = row.folderName,
                    fileCount = 0,
                    totalSizeBytes = 0L,
                    lastModified = 0L
                )
            }
        }

    override suspend fun upsertFile(file: FileEntry) = fileEntryDao.upsert(file.toEntity())

    override suspend fun upsertFiles(files: List<FileEntry>) =
        fileEntryDao.upsertAll(files.map { it.toEntity() })

    override suspend fun markDeleted(path: String) = fileEntryDao.markDeleted(path)

    override suspend fun markSynced(paths: List<String>, syncedAt: Long) =
        fileEntryDao.markSynced(paths, syncedAt)

    override suspend fun setSyncIgnored(path: String, ignored: Boolean) =
        fileEntryDao.setSyncIgnored(path, ignored)

    override suspend fun getUnsyncedFiles(types: List<FileType>): List<FileEntry> =
        if (types.isEmpty()) fileEntryDao.getUnsyncedFiles().map { it.toDomain() }
        else fileEntryDao.getUnsyncedFilesByType(types.map { it.name }).map { it.toDomain() }

    override suspend fun getDuplicates(): List<DuplicateGroup> {
        val hashCounts = fileEntryDao.getDuplicateHashes()
        return hashCounts.mapNotNull { hc ->
            val files = fileEntryDao.getFilesByHash(hc.contentHash).map { it.toDomain() }
            if (files.size > 1) DuplicateGroup(hc.contentHash, files.first().sizeBytes, files)
            else null
        }
    }

    override suspend fun performMediaStoreScan(): Int = withContext(Dispatchers.IO) {
        val seenPaths = HashSet<String>(1024)
        val excluded  = excludedFolderDao.getAllPaths().toSet()
        var total = 0

        fun baseProjection(vararg extra: String): Array<String> = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DATA)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            add(MediaStore.MediaColumns.DATE_ADDED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
            }
            addAll(extra)
        }.distinct().toTypedArray()

        fun Cursor.extractEntities(defaultType: FileType?): List<FileEntryEntity> {
            val list   = mutableListOf<FileEntryEntity>()
            val idxId  = getColumnIndex(MediaStore.MediaColumns._ID)
            val idxData= getColumnIndex(MediaStore.MediaColumns.DATA)
            val idxName= getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val idxSize= getColumnIndex(MediaStore.MediaColumns.SIZE)
            val idxMod = getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            val idxMime= getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val idxBkt = getColumnIndex(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
            val idxW   = getColumnIndex(MediaStore.MediaColumns.WIDTH)
            val idxH   = getColumnIndex(MediaStore.MediaColumns.HEIGHT)
            val idxAdd = getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
            val idxRel = getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            val idxDur = getColumnIndex(MediaStore.Video.VideoColumns.DURATION)
            val idxTkn = getColumnIndex(MediaStore.Images.ImageColumns.DATE_TAKEN)

            while (moveToNext()) {
                val rawPath  = if (idxData >= 0) getString(idxData) else null
                val relPath  = if (idxRel  >= 0) getString(idxRel)  else null
                val dispName = if (idxName >= 0) getString(idxName) else null

                val path = when {
                    !rawPath.isNullOrBlank() -> rawPath
                    !relPath.isNullOrBlank() && !dispName.isNullOrBlank() -> {
                        val r = relPath.trimEnd('/') + "/"
                        "${Environment.getExternalStorageDirectory().absolutePath}/${r}${dispName}"
                    }
                    idxId >= 0 -> ContentUris.withAppendedId(
                        MediaStore.Files.getContentUri("external"), getLong(idxId)
                    ).toString()
                    else -> ""
                }

                if (path.isBlank() || path in seenPaths) continue

                val isContentUri = path.startsWith("content://")
                val parentPath   = if (!isContentUri) File(path).parent else null
                if (parentPath != null && excluded.any { path.startsWith(it) }) continue

                seenPaths += path

                val name      = dispName ?: if (!isContentUri) File(path).name else "file"
                val size      = if (idxSize >= 0) getLong(idxSize) else 0L
                val modified  = if (idxMod  >= 0) getLong(idxMod) * 1000L else 0L
                val mimeRaw   = if (idxMime >= 0) getString(idxMime).orEmpty() else ""
                val mime      = mimeRaw.ifBlank {
                    if (!isContentUri) FileUtils.getMimeType(File(path)) else ""
                }
                val bucket    = if (idxBkt >= 0) getString(idxBkt).orEmpty()
                                else parentPath?.let { File(it).name } ?: ""
                val width     = if (idxW  >= 0) getInt(idxW).takeIf  { it > 0 } else null
                val height    = if (idxH  >= 0) getInt(idxH).takeIf  { it > 0 } else null
                val dateAdded = if (idxAdd >= 0) getLong(idxAdd) * 1000L else System.currentTimeMillis()
                val duration  = if (idxDur >= 0) getLong(idxDur).takeIf { it > 0 } else null
                val dateTaken = if (idxTkn >= 0) getLong(idxTkn).takeIf { it > 0 } else null

                val fileType = when {
                    defaultType != null   -> defaultType
                    mime.isNotBlank()     -> FileType.fromMimeType(mime)
                    !isContentUri         -> FileType.fromExtension(File(path).extension)
                    else                  -> FileType.OTHER
                }

                list += FileEntryEntity(
                    path = path,
                    name = name,
                    folderPath = parentPath ?: "",
                    folderName = bucket,
                    sizeBytes = size,
                    lastModified = modified,
                    mimeType = mime,
                    fileType = fileType.name,
                    width = width,
                    height = height,
                    durationMs = duration,
                    orientation = null,
                    cameraMake = null,
                    cameraModel = null,
                    hasGps = false,
                    dateTaken = dateTaken,
                    dateAdded = dateAdded,
                    isHidden = if (!isContentUri) FileUtils.isHidden(File(path)) else false,
                    contentHash = null,
                    thumbnailCachePath = null,
                    isSyncIgnored = false,
                    lastSyncedAt = null,
                    isDeletedFromDevice = false
                )
            }
            return list
        }

        suspend fun queryAndStore(
            uri: android.net.Uri,
            proj: Array<String>,
            defaultType: FileType?
        ): Int {
            val cursor = try {
                context.contentResolver.query(
                    uri, proj, null, null,
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                )
            } catch (e: Exception) {
                Log.e(TAG, "MediaStore query failed for $uri: ${e.message}", e)
                return 0
            }
            if (cursor == null) {
                Log.w(TAG, "Null cursor for $uri — READ_MEDIA_* permissions may be missing")
                return 0
            }
            var n = 0
            cursor.use {
                val entities = it.extractEntities(defaultType)
                entities.chunked(500).forEach { chunk ->
                    fileEntryDao.upsertAll(chunk)
                    n += chunk.size
                }
            }
            Log.d(TAG, "  $uri → $n new entries (dedup pool: ${seenPaths.size})")
            return n
        }

        Log.d(TAG, "performMediaStoreScan: starting 4-phase scan")

        total += queryAndStore(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            baseProjection(MediaStore.Images.ImageColumns.DATE_TAKEN),
            FileType.PHOTO
        )

        total += queryAndStore(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            baseProjection(
                MediaStore.Video.VideoColumns.DURATION,
                MediaStore.Images.ImageColumns.DATE_TAKEN
            ),
            FileType.VIDEO
        )

        total += queryAndStore(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            baseProjection(MediaStore.Audio.AudioColumns.DURATION),
            FileType.AUDIO
        )

        total += queryAndStore(
            MediaStore.Files.getContentUri("external"),
            baseProjection(),
            null
        )

        Log.d(TAG, "performMediaStoreScan: complete — indexed $total files")
        total
    }

    override suspend fun performFileSystemWalk(
        onProgress: suspend (folder: String, count: Int) -> Unit
    ): Int = withContext(Dispatchers.IO) {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Log.w(TAG, "performFileSystemWalk: MANAGE_EXTERNAL_STORAGE not granted — skipping")
                return@withContext 0
            }
        }

        var count = 0
        val excluded = excludedFolderDao.getAllPaths().toSet()
        val roots = FileUtils.getExternalStorageRoots(context)

        if (roots.isEmpty()) {
            Log.w(TAG, "performFileSystemWalk: no accessible storage roots found")
            return@withContext 0
        }

        for (root in roots) {
            if (!root.exists() || !root.canRead()) continue

            val buffer = mutableListOf<FileEntryEntity>()

            root.walkTopDown()
                .onEnter { dir ->
                    excluded.none { ex -> dir.absolutePath.startsWith(ex) } &&
                            !dir.name.startsWith(".thumbnails") &&
                            !dir.name.startsWith("thumbnails") &&
                            dir.canRead()
                }
                .filter { it.isFile && it.length() > 0 }
                .forEach { file ->
                    if (excluded.any { ex -> file.absolutePath.startsWith(ex) }) return@forEach
                    val mime = FileUtils.getMimeType(file)
                    val fileType = FileType.fromExtension(file.extension)
                    buffer.add(
                        FileEntryEntity(
                            path = file.absolutePath,
                            name = file.name,
                            folderPath = file.parent ?: "",
                            folderName = file.parentFile?.name ?: "",
                            sizeBytes = file.length(),
                            lastModified = file.lastModified(),
                            mimeType = mime,
                            fileType = fileType.name,
                            width = null, height = null, durationMs = null, orientation = null,
                            cameraMake = null, cameraModel = null, hasGps = false, dateTaken = null,
                            dateAdded = System.currentTimeMillis(),
                            isHidden = FileUtils.isHidden(file),
                            contentHash = null, thumbnailCachePath = null,
                            isSyncIgnored = false, lastSyncedAt = null,
                            isDeletedFromDevice = false
                        )
                    )
                    if (buffer.size >= 500) {
                        fileEntryDao.upsertAll(buffer.toList())
                        count += buffer.size
                        onProgress(file.parent ?: "", count)
                        buffer.clear()
                    }
                }

            if (buffer.isNotEmpty()) {
                fileEntryDao.upsertAll(buffer.toList())
                count += buffer.size
                onProgress("", count)
                buffer.clear()
            }
        }

        Log.d(TAG, "performFileSystemWalk: indexed $count files into Room")
        count
    }

    private fun List<FileEntry>.applySortAndFilter(sort: SortOrder, filter: FileFilter): List<FileEntry> {
        var result = this
        if (filter.fileTypes.isNotEmpty()) result = result.filter { it.fileType in filter.fileTypes }
        if (filter.folderPaths.isNotEmpty()) result = result.filter { it.folderPath in filter.folderPaths }
        if (filter.dateFrom != null) result = result.filter { it.lastModified >= filter.dateFrom }
        if (filter.dateTo != null) result = result.filter { it.lastModified <= filter.dateTo }
        if (!filter.showHidden) result = result.filter { !it.isHidden }
        if (!filter.showDeleted) result = result.filter { !it.isDeletedFromDevice }
        if (filter.hasGpsOnly) result = result.filter { it.hasGps }
        if (filter.cameraMake != null) result = result.filter {
            it.cameraMake?.contains(filter.cameraMake, ignoreCase = true) == true
        }
        if (filter.minSizeBytes != null) result = result.filter { it.sizeBytes >= filter.minSizeBytes }
        if (filter.maxSizeBytes != null) result = result.filter { it.sizeBytes <= filter.maxSizeBytes }

        val sorted = when (sort.field) {
            SortField.DATE_MODIFIED -> result.sortedBy { it.lastModified }
            SortField.DATE_ADDED -> result.sortedBy { it.dateAdded }
            SortField.NAME -> result.sortedBy { it.name.lowercase() }
            SortField.SIZE -> result.sortedBy { it.sizeBytes }
            SortField.FOLDER -> result.sortedBy { it.folderName.lowercase() }
            SortField.DATE_TAKEN -> result.sortedBy { it.dateTaken ?: it.lastModified }
            SortField.DURATION -> result.sortedBy { it.durationMs ?: 0L }
        }
        return if (sort.ascending) sorted else sorted.reversed()
    }
}
