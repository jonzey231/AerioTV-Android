package com.aeriotv.android.core.data

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.aeriotv.android.core.data.db.dao.LocalRecordingDao
import com.aeriotv.android.core.data.db.entity.LocalRecordingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Apple's RecordingPathRules (AerioTV 743bd6b), pure so it is unit tested:
 * an on-device recording must never sit on the device with no way to reach
 * or remove it, and a row must never hide a lost file.
 */
object LocalRecordingRules {
    /** Media files a recording can be (what an orphan file must be to be adopted). */
    val MEDIA_EXTENSIONS: Set<String> = setOf("ts", "mp4", "m4v", "mkv", "mov")

    /** A file in AerioTV's recordings folder: [key] is how a row would store it. */
    data class Candidate(val key: String, val name: String)

    /**
     * The files no row points at, by name: never a hidden file, only media
     * files, each once ([known] holds the keys rows store).
     */
    fun orphans(files: List<Candidate>, known: Set<String>): List<Candidate> =
        files.filter { f ->
            !f.name.startsWith(".") && f.key !in known &&
                f.name.substringAfterLast('.', "").lowercase() in MEDIA_EXTENSIONS &&
                f.name.contains('.')
        }.distinctBy { it.key }.sortedBy { it.name }

    /** Statuses whose file should be on the device (a finished capture). */
    private val FINISHED = setOf("completed", "stopped", "interrupted")

    /**
     * A finished recording on this device whose file is not there (removed
     * outside the app, or a location nothing holds): shown as missing, with
     * no Play, and still deletable.
     */
    fun isMissing(status: String, filePath: String?, exists: (String) -> Boolean): Boolean {
        if (status.lowercase() !in FINISHED) return false
        val p = filePath?.takeIf { it.isNotBlank() } ?: return true
        return !exists(p)
    }

    /**
     * An adopted file's title: Apple's "Recording from <date and time>", the
     * date in the medium style and the time in the short one, joined as
     * Apple's DateFormatter joins them ("Nov 13, 2026 at 9:52 AM" in US
     * English).
     */
    fun adoptedTitle(createdMillis: Long, locale: Locale = Locale.getDefault(), zone: TimeZone = TimeZone.getDefault()): String {
        val d = DateFormat.getDateInstance(DateFormat.MEDIUM, locale).apply { timeZone = zone }
        val t = DateFormat.getTimeInstance(DateFormat.SHORT, locale).apply { timeZone = zone }
        val at = Date(createdMillis)
        return "Recording from ${d.format(at)} at ${t.format(at)}"
    }

    /**
     * The channel an on-device recording shows (Program Info's Channel row,
     * the DVR card's meta line): the name saved with it, as Apple shows a
     * recording's own channelName. Null when none was saved (an adopted file)
     * or when what was saved only repeats the shown [title]: Save to Device
     * and the capture's fallback stored the title there, and a channel named
     * like its program needs no second line.
     */
    fun shownChannelName(savedChannelName: String?, title: String): String? {
        val name = savedChannelName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val shownTitle = title.trim().ifEmpty { name }
        return name.takeUnless { it == shownTitle }
    }
}

/**
 * Launch, after the interrupted-capture sweep: every recording file in
 * AerioTV's own recordings folder that no row points at becomes a row under
 * "Saved on this device", so it can be played or deleted (Apple 743bd6b,
 * RecordingCoordinator.adoptOrphanRecordingFiles). Named from the file's
 * dates.
 *
 * AerioTV's own folder on Android is where the default recording location
 * writes: the public Downloads/AerioTV folder through MediaStore (Android 10
 * and later; only items AerioTV itself created are listed, MediaStore shows
 * an app no one else's), and the app-private Recordings folder used before
 * Android 10 and when MediaStore is unavailable. A folder picked under
 * Settings > DVR may hold anything, so nothing there is adopted. Items still
 * pending (a capture cut short) are the interrupted-capture sweep's.
 */
object LocalRecordingAdoption {
    private const val TAG = "LocalRecordingService"

    /** How many files were adopted. */
    suspend fun adopt(context: Context, dao: LocalRecordingDao): Int = withContext(Dispatchers.IO) {
        val stored = dao.allFilePaths()
        val known = stored.toHashSet()
        // A MediaStore row matches by its item id too, whatever volume name
        // its URI was written with.
        val knownMediaIds = stored.mapNotNull { mediaId(it) }.toHashSet()
        val found = ArrayList<Pair<LocalRecordingRules.Candidate, FileFacts>>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) found += downloadsItems(context)
        found += appPrivateFiles(context)
        val facts = found.associate { it.first.key to it.second }
        val candidates = found.map { it.first }.filter { c -> mediaId(c.key)?.let { it !in knownMediaIds } ?: true }
        val orphans = LocalRecordingRules.orphans(candidates, known)
        var adopted = 0
        for (o in orphans) {
            val f = facts[o.key] ?: continue
            if (f.bytes <= 0L) continue
            val created = minOf(f.createdAt, f.modifiedAt)
            dao.insert(
                LocalRecordingEntity(
                    channelName = "",
                    title = LocalRecordingRules.adoptedTitle(created),
                    filePath = o.key,
                    startedAt = created,
                    endedAt = f.modifiedAt,
                    byteSize = f.bytes,
                    status = "completed",
                    playlistId = null,
                    profileScope = "",
                ),
            )
            adopted++
        }
        if (adopted > 0) {
            Log.i(TAG, "[DVR-PATHS] adopted $adopted recording file(s) no row pointed at; they show under Saved on this device")
        }
        adopted
    }

    private data class FileFacts(val bytes: Long, val createdAt: Long, val modifiedAt: Long)

    private fun mediaId(key: String): Long? = runCatching {
        val uri = android.net.Uri.parse(key)
        if (uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY) ContentUris.parseId(uri) else null
    }.getOrNull()?.takeIf { it >= 0L }

    /** AerioTV's own items in Downloads/AerioTV, published ones only. */
    private fun downloadsItems(context: Context): List<Pair<LocalRecordingRules.Candidate, FileFacts>> = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@runCatching emptyList()
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val out = ArrayList<Pair<LocalRecordingRules.Candidate, FileFacts>>()
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
            arrayOf("Download/AerioTV/", context.packageName),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: continue
                val bytes = c.getLong(2)
                val added = c.getLong(3) * 1000L
                val modified = c.getLong(4) * 1000L
                val uri = ContentUris.withAppendedId(collection, id).toString()
                out += LocalRecordingRules.Candidate(uri, name) to FileFacts(bytes, if (added > 0L) added else modified, modified)
            }
        }
        out
    }.getOrElse {
        Log.w(TAG, "[DVR-PATHS] Downloads/AerioTV listing failed: ${it.message}")
        emptyList()
    }

    /** The app-private Recordings folder (before Android 10, or MediaStore unavailable). */
    private fun appPrivateFiles(context: Context): List<Pair<LocalRecordingRules.Candidate, FileFacts>> = runCatching {
        val dir = File(context.getExternalFilesDir(null) ?: return@runCatching emptyList(), "Recordings")
        dir.listFiles().orEmpty().filter { it.isFile }.map { f ->
            val modified = f.lastModified()
            val created = runCatching {
                java.nio.file.Files.readAttributes(f.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
                    .creationTime().toMillis()
            }.getOrDefault(modified)
            LocalRecordingRules.Candidate(f.absolutePath, f.name) to FileFacts(f.length(), created, modified)
        }
    }.getOrElse { emptyList() }
}
