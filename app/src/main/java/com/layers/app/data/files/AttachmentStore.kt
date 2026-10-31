package com.layers.app.data.files

import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import coil.Coil
import com.layers.app.security.ChunkedGcm
import com.layers.app.security.KeystoreCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.UUID

data class ImportedFile(
    val relativePath: String,
    val displayName: String,
    val mime: String,
    val size: Long,
)

/**
 * Every attachment is copied into internal storage at filesDir/attachments/<uuid>.
 * The database only ever stores the relative path ("attachments/<uuid>").
 * Files of locked notes are encrypted in place; [readable] hands out a decrypted copy in the cache,
 * and that cache is wiped every time the app re-locks.
 */
class AttachmentStore(
    private val context: Context,
    private val crypto: KeystoreCrypto,
) {
    private val attachmentsDir: File get() = File(context.filesDir, DIR).apply { mkdirs() }
    private val unlockedDir: File get() = File(context.cacheDir, "unlocked").apply { mkdirs() }
    private val sharedDir: File get() = File(context.cacheDir, "shared").apply { mkdirs() }

    val authority: String get() = context.packageName + ".fileprovider"

    fun newRelativePath(): String = "$DIR/${UUID.randomUUID()}"

    fun file(relativePath: String): File = File(context.filesDir, relativePath)

    /** Creates an empty attachment file, e.g. as the target for a camera photo. */
    fun createEmpty(): Pair<String, File> {
        attachmentsDir
        val rel = newRelativePath()
        val f = file(rel)
        f.createNewFile()
        return rel to f
    }

    fun uriForAttachment(relativePath: String): Uri =
        FileProvider.getUriForFile(context, authority, file(relativePath))

    suspend fun importUri(uri: Uri, fallbackName: String = "Attachment"): ImportedFile = withContext(Dispatchers.IO) {
        attachmentsDir
        val resolver = context.contentResolver
        var name = fallbackName
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        if (name == fallbackName) uri.lastPathSegment?.substringAfterLast('/')?.let { if (it.isNotBlank()) name = it }
        var mime = resolver.getType(uri)
        if (mime == null || mime == "application/octet-stream") {
            val ext = name.substringAfterLast('.', "").lowercase()
            mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: mime ?: "application/octet-stream"
        }
        val rel = newRelativePath()
        val target = file(rel)
        val input = resolver.openInputStream(uri) ?: throw IllegalStateException("Cannot read $uri")
        input.use { inp -> target.outputStream().use { out -> inp.copyTo(out) } }
        if (size < 0) size = target.length()
        ImportedFile(rel, name, mime, size)
    }

    suspend fun importFile(source: File, name: String, mime: String): ImportedFile = withContext(Dispatchers.IO) {
        attachmentsDir
        val rel = newRelativePath()
        val target = file(rel)
        source.inputStream().use { inp -> target.outputStream().use { out -> inp.copyTo(out) } }
        ImportedFile(rel, name, mime, target.length())
    }

    /** Pixel size of an image, corrected for EXIF rotation. */
    suspend fun imageSize(relativePath: String, encrypted: Boolean): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val f = readable(relativePath, encrypted)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, opts)
        var w = opts.outWidth.coerceAtLeast(1)
        var h = opts.outHeight.coerceAtLeast(1)
        val rotation = runCatching {
            ExifInterface(f.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        if (rotation == ExifInterface.ORIENTATION_ROTATE_90 || rotation == ExifInterface.ORIENTATION_ROTATE_270 ||
            rotation == ExifInterface.ORIENTATION_TRANSPOSE || rotation == ExifInterface.ORIENTATION_TRANSVERSE
        ) {
            val t = w; w = h; h = t
        }
        w to h
    }

    fun isEncryptedFile(relativePath: String): Boolean {
        val f = file(relativePath)
        if (!f.exists() || f.length() < 5) return false
        val header = ByteArray(5)
        FileInputStream(f).use { it.read(header) }
        return ChunkedGcm.isChunkedEncrypted(header)
    }

    suspend fun encryptInPlace(relativePath: String) = withContext(Dispatchers.IO) {
        val f = file(relativePath)
        if (!f.exists() || isEncryptedFile(relativePath)) return@withContext
        val tmp = File(f.parentFile, f.name + ".tmp")
        f.inputStream().use { inp -> tmp.outputStream().use { out -> crypto.encryptStream(inp, out) } }
        if (!tmp.renameTo(f)) {
            tmp.copyTo(f, overwrite = true)
            tmp.delete()
        }
    }

    suspend fun decryptInPlace(relativePath: String) = withContext(Dispatchers.IO) {
        val f = file(relativePath)
        if (!f.exists() || !isEncryptedFile(relativePath)) return@withContext
        val tmp = File(f.parentFile, f.name + ".tmp")
        f.inputStream().use { inp -> tmp.outputStream().use { out -> crypto.decryptStream(inp, out) } }
        if (!tmp.renameTo(f)) {
            tmp.copyTo(f, overwrite = true)
            tmp.delete()
        }
    }

    /**
     * A file the app can read. For plain attachments this is the file itself; for encrypted ones it is
     * a decrypted copy in the cache (deleted when the app re-locks). Must not run on the main thread.
     */
    fun readable(relativePath: String, encrypted: Boolean): File {
        val f = file(relativePath)
        if (!encrypted || !isEncryptedFile(relativePath)) return f
        val out = File(unlockedDir, f.name)
        if (out.exists() && out.length() > 0) return out
        val tmp = File(unlockedDir, f.name + ".part")
        f.inputStream().use { inp -> tmp.outputStream().use { o -> crypto.decryptStream(inp, o) } }
        tmp.renameTo(out)
        return out
    }

    suspend fun readableAsync(relativePath: String, encrypted: Boolean): File =
        withContext(Dispatchers.IO) { readable(relativePath, encrypted) }

    /** Copies (and decrypts) an attachment into cache/shared/ with a friendly name and returns a content URI. */
    suspend fun shareUri(relativePath: String, encrypted: Boolean, displayName: String): Uri = withContext(Dispatchers.IO) {
        val source = readable(relativePath, encrypted)
        val safe = displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "attachment" }
        val dir = File(sharedDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val out = File(dir, safe)
        source.copyTo(out, overwrite = true)
        FileProvider.getUriForFile(context, authority, out)
    }

    fun sharedFile(name: String): File {
        val dir = File(sharedDir, UUID.randomUUID().toString()).apply { mkdirs() }
        return File(dir, name)
    }

    fun uriForShared(file: File): Uri = FileProvider.getUriForFile(context, authority, file)

    fun delete(relativePath: String) {
        file(relativePath).delete()
        File(unlockedDir, File(relativePath).name).delete()
    }

    fun allAttachmentFiles(): List<File> = attachmentsDir.listFiles()?.filter { it.isFile } ?: emptyList()

    fun deleteAllAttachments() {
        attachmentsDir.listFiles()?.forEach { it.delete() }
        clearDecryptedCache()
    }

    /** Wipes decrypted copies and the in-memory image cache (called when the app re-locks). */
    fun clearDecryptedCache() {
        unlockedDir.listFiles()?.forEach { it.delete() }
        runCatching { Coil.imageLoader(context).memoryCache?.clear() }
    }

    fun clearSharedCache() {
        sharedDir.deleteRecursively()
    }

    companion object {
        const val DIR = "attachments"
    }
}
