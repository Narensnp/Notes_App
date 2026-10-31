package com.layers.app.navigation

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Asks the Notes tab to show a particular folder (used by breadcrumbs in the editor and search). */
data class FolderRequest(val folderId: String?, val nonce: Long = System.nanoTime())

class NavRequests {
    private val _folder = MutableStateFlow<FolderRequest?>(null)
    val folder: StateFlow<FolderRequest?> = _folder.asStateFlow()

    fun showFolder(folderId: String?) {
        _folder.value = FolderRequest(folderId)
    }

    fun consumeFolder() {
        _folder.value = null
    }

    /** A PDF opened from another app ("Open with Layers"), shown by the external viewer route. */
    @Volatile
    var externalPdf: Uri? = null
}
