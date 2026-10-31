package com.layers.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        FolderEntity::class,
        NoteEntity::class,
        BlockEntity::class,
        PinEntity::class,
        TaskEntity::class,
        TagEntity::class,
        NoteTagCrossRef::class,
        ExtractedTextEntity::class,
        SearchFtsEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class LayersDatabase : RoomDatabase() {
    abstract fun folders(): FolderDao
    abstract fun notes(): NoteDao
    abstract fun blocks(): BlockDao
    abstract fun pins(): PinDao
    abstract fun tasks(): TaskDao
    abstract fun tags(): TagDao
    abstract fun extractedText(): ExtractedTextDao
    abstract fun search(): SearchDao

    companion object {
        fun build(context: Context): LayersDatabase =
            Room.databaseBuilder(context.applicationContext, LayersDatabase::class.java, "layers.db")
                .build()
    }
}
