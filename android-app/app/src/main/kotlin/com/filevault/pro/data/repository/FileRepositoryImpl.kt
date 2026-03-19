package com.filevault.pro.data.repository

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.os.Build
import android.os.Environment
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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
        if (filter.searchQuery.isBlank()) fileEntryDao.getAllPhotosFlow()
            .map { list -> list.map { it.toDomain() }.applySortAndFilter(sortOrder, filter) }
        else fileEntryDao.searchPhotos(filter.searchQuery)
            .map { list -> list.map { it.toDomain() }.applySortAndFilter(sortOrder, filter) }

    override fun getAllVideos(sortOrder: SortOrder, filter: FileFilter): Flow<List<FileEntry>> =
        if (filter.searchQuery.isBlank()) fileEntryDao.getAllVideosFlow()
            .map { list -> list.map { it.toDomain() }.applySortAndFilter(sortOrder, filter) }
        else fileEntryDao.searchVideos(filter.searchQuery)
            .map { list -> list.map { it.toDomain() }.applySortAndFilter(sortOrder, filter) }

    override fun getAllFiles(sortOrder: SortOrder, filter: FileFilter): Flow<List<FileEntry>> =
        fileEntryDao.searchFiles(filter.searchQuery)
            .map { list -> list.map { it.toDomain() }.applySortAndFilter(sortOrder, filter) }

    override fun getStats(): Flow<CatalogStats> {
        return combine(
            fileEntryDao.getTotalCount(),
            fileEntryDao.getPhotoCount(),
            fileEntryDao.getVideoCount(),
            fileEntryDao.getAudioCount(),
            fileEntryDao.getDocumentCount(),
            fileEntryDao.getTotalSizeBytes()
        ) { values ->
            CatalogStats(
                totalFiles = values[0] as Int,
                totalPhotos = values[1] as Int,
                totalVideos = values[2] as Int,
                totalAudio = values[3] as Int,
                totalDocuments = values[4] as Int,
                totalOther = (values[0] as Int) - (values[1] as Int) - (values[2] as Int) -
                        (values[3] as Int) - (values[4] as Int),
                totalSizeBytes = (values[5] as Long?) ?: 0L,
                lastScanAt = null,
                lastSyncAt = null
            )
        }
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

        // Phase 1 — Images (DATE_TAKEN gives better sort-by-date)
        total += queryAndStore(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            baseProjection(MediaStore.Images.ImageColumns.DATE_TAKEN),
            FileType.PHOTO
        )

        // Phase 2 — Videos (DURATION + DATE_TAKEN)
        total += queryAndStore(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            baseProjection(
                MediaStore.Video.VideoColumns.DURATION,
                MediaStore.Images.ImageColumns.DATE_TAKEN
            ),
            FileType.VIDEO
        )

        // Phase 3 — Audio (DURATION)
        total += queryAndStore(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            baseProjection(MediaStore.Audio.AudioColumns.DURATION),
            FileType.AUDIO
        )

        // Phase 4 — All files (catches documents, APKs, archives, etc.)
        // ⚠ NO SIZE > 0 filter: Android 15 can store NULL size before full indexing,
        //   and NULL > 0 is FALSE in SQL — silently excluding ALL unindexed files.
        // Already-seen paths (from phases 1-3) are skipped via seenPaths dedup.
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
                Log.w(
                    TAG,
                    "performFileSystemWalk: MANAGE_EXTERNAL_STORAGE not granted at runtime. " +
                        "Skipping file system walk. Go to Settings > Apps > FileVaultPro > " +
                        "Permissions and enable 'All files access'."
                )
                return@withContext 0
            }
        }

        var count = 0
        val excluded = excludedFolderDao.getAllPaths().toSet()
        val roots = FileUtils.getExternalStorageRoots(context)

        if (roots.isEmpty()) {
            Log.w(TAG, "performFileSystemWalk: no accessible storage roots found — nothing to walk")
            return@withContext 0
        }

        Log.d(TAG, "performFileSystemWalk: walking ${roots.size} root(s): ${roots.map { it.absolutePath }}")

        for (root in roots) {
            if (!root.exists() || !root.canRead()) {
                Log.w(TAG, "performFileSystemWalk: skipping root ${root.absolutePath} — not accessible")
                continue
            }

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
