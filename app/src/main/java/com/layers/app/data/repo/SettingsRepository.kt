package com.layers.app.data.repo

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class SortMode { MODIFIED, CREATED, TITLE }
enum class ViewMode { GRID, LIST }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val appLock: Boolean = false,
    val viewMode: ViewMode = ViewMode.GRID,
    val sortMode: SortMode = SortMode.MODIFIED,
    val autoBackup: Boolean = false,
    val autoBackupTreeUri: String? = null,
    val hasBackupPassword: Boolean = false,
    val lastBackupAt: Long = 0L,
    val lastBackupError: String? = null,
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val appLock = booleanPreferencesKey("app_lock")
        val viewMode = stringPreferencesKey("view_mode")
        val sortMode = stringPreferencesKey("sort_mode")
        val autoBackup = booleanPreferencesKey("auto_backup")
        val autoBackupTree = stringPreferencesKey("auto_backup_tree")
        val backupPassword = stringPreferencesKey("backup_password_enc")
        val lastBackup = longPreferencesKey("last_backup_at")
        val lastBackupError = stringPreferencesKey("last_backup_error")
        val recentSearches = stringPreferencesKey("recent_searches")
        val recentFolders = stringPreferencesKey("recent_folders")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.map { p ->
        AppSettings(
            themeMode = enumOr(p[Keys.theme], ThemeMode.SYSTEM),
            dynamicColor = p[Keys.dynamic] ?: false,
            appLock = p[Keys.appLock] ?: false,
            viewMode = enumOr(p[Keys.viewMode], ViewMode.GRID),
            sortMode = enumOr(p[Keys.sortMode], SortMode.MODIFIED),
            autoBackup = p[Keys.autoBackup] ?: false,
            autoBackupTreeUri = p[Keys.autoBackupTree],
            hasBackupPassword = p[Keys.backupPassword] != null,
            lastBackupAt = p[Keys.lastBackup] ?: 0L,
            lastBackupError = p[Keys.lastBackupError],
        )
    }

    suspend fun current(): AppSettings = settings.first()

    val recentSearches: Flow<List<String>> = context.settingsStore.data.map { decodeList(it[Keys.recentSearches]) }
    val recentFolders: Flow<List<String>> = context.settingsStore.data.map { decodeList(it[Keys.recentFolders]) }

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.theme] = mode.name }
    suspend fun setDynamicColor(on: Boolean) = edit { it[Keys.dynamic] = on }
    suspend fun setAppLock(on: Boolean) = edit { it[Keys.appLock] = on }
    suspend fun setViewMode(mode: ViewMode) = edit { it[Keys.viewMode] = mode.name }
    suspend fun setSortMode(mode: SortMode) = edit { it[Keys.sortMode] = mode.name }
    suspend fun setAutoBackup(on: Boolean) = edit { it[Keys.autoBackup] = on }
    suspend fun setAutoBackupTree(uri: String?) = edit {
        if (uri == null) { it.remove(Keys.autoBackupTree) } else { it[Keys.autoBackupTree] = uri }
    }

    /** Stores the auto-backup password already encrypted with a Keystore key. */
    suspend fun setEncryptedBackupPassword(value: String?) = edit {
        if (value == null) { it.remove(Keys.backupPassword) } else { it[Keys.backupPassword] = value }
    }

    suspend fun encryptedBackupPassword(): String? = context.settingsStore.data.first()[Keys.backupPassword]

    suspend fun setLastBackup(at: Long, error: String?) = edit {
        it[Keys.lastBackup] = at
        if (error == null) { it.remove(Keys.lastBackupError) } else { it[Keys.lastBackupError] = error }
    }

    suspend fun setBackupError(error: String) = edit { it[Keys.lastBackupError] = error }

    suspend fun addRecentSearch(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        edit {
            val list = listOf(q) + decodeList(it[Keys.recentSearches]).filter { s -> !s.equals(q, ignoreCase = true) }
            it[Keys.recentSearches] = encodeList(list.take(10))
        }
    }

    suspend fun clearRecentSearches() = edit { it.remove(Keys.recentSearches) }

    suspend fun touchRecentFolder(folderId: String?) {
        val id = folderId ?: HOME
        edit {
            val list = listOf(id) + decodeList(it[Keys.recentFolders]).filter { f -> f != id }
            it[Keys.recentFolders] = encodeList(list.take(8))
        }
    }

    /** Everything that a backup should carry, as simple strings. */
    suspend fun exportForBackup(): Map<String, String> {
        val s = current()
        return mapOf(
            "theme" to s.themeMode.name,
            "dynamic_color" to s.dynamicColor.toString(),
            "app_lock" to s.appLock.toString(),
            "view_mode" to s.viewMode.name,
            "sort_mode" to s.sortMode.name,
        )
    }

    suspend fun importFromBackup(values: Map<String, String>) = edit { p ->
        values["theme"]?.let { p[Keys.theme] = it }
        values["dynamic_color"]?.let { p[Keys.dynamic] = it.toBoolean() }
        values["app_lock"]?.let { p[Keys.appLock] = it.toBoolean() }
        values["view_mode"]?.let { p[Keys.viewMode] = it }
        values["sort_mode"]?.let { p[Keys.sortMode] = it }
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        context.settingsStore.edit { block(it) }
    }

    private fun decodeList(raw: String?): List<String> =
        raw?.split(SEP)?.filter { it.isNotEmpty() } ?: emptyList()

    private fun encodeList(list: List<String>): String = list.joinToString(SEP)

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default

    companion object {
        const val HOME = "__home__"
        private const val SEP = "\u001F"
    }
}
