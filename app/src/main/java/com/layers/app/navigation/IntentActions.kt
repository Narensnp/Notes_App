package com.layers.app.navigation

/** Intent actions used by shortcuts, tiles, the widget and notifications to open parts of the app. */
object IntentActions {
    const val NEW_NOTE = "com.layers.app.action.NEW_NOTE"
    const val NEW_TASK = "com.layers.app.action.NEW_TASK"
    const val SEARCH = "com.layers.app.action.SEARCH"
    const val SCREENSHOT_NOTE = "com.layers.app.action.SCREENSHOT_NOTE"
    const val OPEN_NOTE = "com.layers.app.action.OPEN_NOTE"

    const val EXTRA_NOTE_ID = "noteId"
    const val EXTRA_BLOCK_ID = "blockId"
}
