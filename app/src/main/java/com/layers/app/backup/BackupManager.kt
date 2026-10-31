package com.layers.app.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import com.layers.app.data.db.BlockEntity
import com.layers.app.data.db.ExtractedTextEntity
import com.layers.app.data.db.FolderEntity
import com.layers.app.data.db.LayersDatabase
import com.layers.app.data.db.NoteEntity
import com.layers.app.data.db.NoteTagCrossRef
import com.layers.app.data.db.PinEntity
import com.layers.app.data.db.TagEntity
import com.layers.app.data.db.TaskEntity
import com.layers.app.data.files.AttachmentStore
import com.layers.app.data.model.LayersJson
import com.layers.app.data.repo.BlockCodec
import com.layers.app.data.repo.LockManager
import com.layers.app.data.repo.SearchIndexer
import com.layers.app.data.repo.SettingsRepository
import com.layers.app.data.repo.TaskRepository
import com.layers.app.reminders.ReminderScheduler
import com.layers.app.security.ChunkedGcm
import com.layers.app.security.KeystoreCrypto
import com.layers.app.security.PasswordCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException

@Serializable
data class BackupData(
    val version: Int = 1,
    val createdAt: Long,
    val folders: List<FolderEntity>,
    val notes: List<NoteEntity>,
    val blocks: List<BlockEntity>,
    val pins: List<PinEntity>,
    val tasks: List<TaskEntity>,
    val tags: List<TagEntity>,
    val noteTags: List<NoteTagCrossRef>,
    val extractedText: List<ExtractedTextEntity>,
    val settings: Map<String, String>,
)

data class RestoreSummary(val folders: Int, val notes: Int, val attachments: Int)

class WrongPasswordException : Exception("Wrong password, or the backup file is damaged.")

/**
 * Backup file layout:
 *   "LAYERSBK" magic, version byte, PBKDF2 iteration count (int), 16-byte salt,
 *   then a chunked AES-256-GCM stream (see ChunkedGcm) containing a ZIP with data.json and attachments/.
 * Locked notes are decrypted with this phone's Keystore key and re-encrypted with the backup
 * password, so the file can be restored on a new phone. On restore, locked notes are encrypted again
 * with the new phone's Keystore key.
 */
class BackupManager(
    private val context: Context,
    private val db: LayersDatabase,
    private val codec: BlockCodec,
    private val attachments: AttachmentStore,
    private val settings: SettingsRepository,
    private val indexer: SearchIndexer,
    private val tasks: TaskRepository,
    private val lockManager: LockManager,
    private val scheduler: ReminderScheduler,
    private val crypto: KeystoreCrypto,
) {
    suspend fun exportTo(target: Uri, password: CharArray) = withContext(Dispatchers.IO) {
        val out = runCatching { context.contentResolver.openOutputStream(target, "wt") }.getOrNull()
            ?: context.contentResolver.openOutputStream(target)
            ?: throw IOException("Cannot write to the selected file")
        out.use { writeBackup(it, password) }
    }

    private suspend fun writeBackup(output: OutputStream, password: CharArray) {
        val zip = File(context.cacheDir, "backup-${System.nanoTime()}.zip")
        try {
            buildZip(zip)
            val salt = PasswordCrypto.newSalt()
            val key = PasswordCrypto.deriveKey(password, salt, PasswordCrypto.ITERATIONS)
            val header = DataOutputStream(output)
            header.write(MAGIC)
            header.writeByte(VERSION)
            header.writeInt(PasswordCrypto.ITERATIONS)
            header.write(salt)
            header.flush()
            zip.inputStream().use { inp ->
                ChunkedGcm.encrypt(inp, output) { PasswordCrypto.encryptCipher(key) }
            }
            output.flush()
        } finally {
            zip.delete()
        }
    }

    private suspend fun buildZip(zipFile: File) {
        val blocks = db.blocks().getAll().map { codec.decryptEntity(it) }
        val notes = db.notes().getAll().map { it.copy(encrypted = false) }
        val encryptedNoteIds = db.notes().getAll().filter { it.encrypted }.map { it.id }.toSet()
        val data = BackupData(
            createdAt = System.currentTimeMillis(),
            folders = db.folders().getAll(),
            notes = notes,
            blocks = blocks,
            pins = db.pins().getAll(),
            tasks = db.tasks().getAll(),
            tags = db.tags().getAll(),
            noteTags = db.tags().getAllRefs(),
            extractedText = db.extractedText().getAll(),
            settings = settings.exportForBackup(),
        )
        ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
            zos.putNextEntry(ZipEntry(DATA_ENTRY))
            zos.write(LayersJson.encodeToString(BackupData.serializer(), data).toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            val written = HashSet<String>()
            for (b in blocks) {
                val rel = b.filePath ?: continue
                if (!written.add(rel)) continue
                val source = attachments.file(rel)
                if (!source.exists()) continue
                val readable = if (b.noteId in encryptedNoteIds || attachments.isEncryptedFile(rel)) {
                    attachments.readable(rel, true)
                } else {
                    source
                }
                zos.putNextEntry(ZipEntry(rel))
                readable.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }

    suspend fun restoreFrom(source: Uri, password: CharArray): RestoreSummary = withContext(Dispatchers.IO) {
        val zip = File(context.cacheDir, "restore-${System.nanoTime()}.zip")
        try {
            val input = context.contentResolver.openInputStream(source) ?: throw IOException("Cannot read the selected file")
            input.use { decryptBackup(BufferedInputStream(it), zip, password) }
            restoreZip(zip)
        } finally {
            zip.delete()
        }
    }

    private fun decryptBackup(input: InputStream, target: File, password: CharArray) {
        val din = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        din.readFully(magic)
        if (!magic.contentEquals(MAGIC)) throw IOException("This is not a Layers backup file.")
        val version = din.readByte().toInt()
        if (version != VERSION) throw IOException("This backup was made by a newer version of Layers.")
        val iterations = din.readInt()
        if (iterations < 10_000 || iterations > 10_000_000) throw IOException("The backup file is damaged.")
        val salt = ByteArray(PasswordCrypto.SALT_BYTES)
        din.readFully(salt)
        val key = PasswordCrypto.deriveKey(password, salt, iterations)
        try {
            target.outputStream().use { out ->
                ChunkedGcm.decrypt(din, out) { iv -> PasswordCrypto.decryptCipher(key, iv) }
            }
        } catch (e: AEADBadTagException) {
            throw WrongPasswordException()
        } catch (e: javax.crypto.BadPaddingException) {
            throw WrongPasswordException()
        }
    }

    private suspend fun restoreZip(zip: File): RestoreSummary {
        // 1) Read data.json first, so a broken file never wipes existing notes.
        var data: BackupData? = null
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == DATA_ENTRY) {
                    data = LayersJson.decodeFromString(BackupData.serializer(), zis.readBytes().toString(Charsets.UTF_8))
                }
                entry = zis.nextEntry
            }
        }
        val backup = data ?: throw IOException("The backup file is incomplete.")

        // 2) Cancel reminders of the data that is about to be replaced.
        db.tasks().getAll().forEach { scheduler.cancel(it.blockId) }

        // 3) Replace attachments.
        attachments.deleteAllAttachments()
        var fileCount = 0
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory && name.startsWith(AttachmentStore.DIR + "/") && !name.contains("..")) {
                    val target = attachments.file(name)
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zis.copyTo(it) }
                    fileCount++
                }
                entry = zis.nextEntry
            }
        }

        // 4) Replace the database contents (everything stored in plain form first).
        db.withTransaction {
            db.search().deleteAll()
            db.extractedText().deleteAll()
            db.tags().deleteAllRefs()
            db.tags().deleteAll()
            db.tasks().deleteAll()
            db.pins().deleteAll()
            db.blocks().deleteAll()
            db.notes().deleteAll()
            db.folders().deleteAll()

            db.folders().upsertAll(backup.folders)
            db.notes().upsertAll(backup.notes.map { it.copy(encrypted = false) })
            db.blocks().upsertAll(backup.blocks)
            db.pins().upsertAll(backup.pins)
            db.tasks().upsertAll(backup.tasks)
            db.tags().upsertAll(backup.tags)
            db.tags().addRefs(backup.noteTags)
            db.extractedText().upsertAll(backup.extractedText)
        }

        // 5) Re-encrypt locked notes with this phone's key, rebuild the index and reminders.
        lockManager.reconcile(backup.notes.map { it.id })
        indexer.rebuildAll()
        db.notes().getAll().forEach { tasks.resyncFromStorage(it.id) }
        tasks.rescheduleAll()
        settings.importFromBackup(backup.settings)

        return RestoreSummary(
            folders = backup.folders.count { it.deletedAt == null },
            notes = backup.notes.count { it.deletedAt == null && it.parentNoteId == null },
            attachments = fileCount,
        )
    }

    // ---------- Auto backup ----------

    suspend fun saveAutoBackupPassword(password: String) {
        settings.setEncryptedBackupPassword(crypto.encryptString(password, KeystoreCrypto.BACKUP_PASSWORD_KEY))
    }

    /** Writes a dated backup into the folder the user picked and keeps the five newest. */
    suspend fun runAutoBackup(): String = withContext(Dispatchers.IO) {
        val s = settings.current()
        val tree = s.autoBackupTreeUri?.let { Uri.parse(it) } ?: throw IOException("No backup folder chosen")
        val encPassword = settings.encryptedBackupPassword() ?: throw IOException("No backup password set")
        val password = crypto.decryptString(encPassword, KeystoreCrypto.BACKUP_PASSWORD_KEY).toCharArray()
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = "Layers-backup-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")) + ".layersbak"
        val doc = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name)
            ?: throw IOException("Could not create the backup file")
        try {
            exportTo(doc, password)
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, doc) }
            throw e
        } finally {
            password.fill('\u0000')
        }
        pruneOldBackups(tree)
        settings.setLastBackup(System.currentTimeMillis(), null)
        name
    }

    private fun pruneOldBackups(tree: Uri) {
        val resolver = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val found = ArrayList<Pair<String, String>>()
        runCatching {
            resolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val display = c.getString(1) ?: continue
                    if (display.startsWith("Layers-backup-") && display.endsWith(".layersbak")) found += id to display
                }
            }
        }
        found.sortedByDescending { it.second }.drop(KEEP_AUTO_BACKUPS).forEach { (id, _) ->
            runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id)) }
        }
    }

    companion object {
        private val MAGIC = "LAYERSBK".toByteArray(Charsets.US_ASCII)
        private const val VERSION = 1
        private const val DATA_ENTRY = "data.json"
        private const val KEEP_AUTO_BACKUPS = 5

        fun suggestedFileName(): String =
            "Layers-backup-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + ".layersbak"
    }
}
