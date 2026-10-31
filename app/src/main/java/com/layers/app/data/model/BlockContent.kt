package com.layers.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** JSON settings shared by the whole app (blocks, strokes, backups). */
val LayersJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "t"
}

enum class Repeat { NONE, DAILY, WEEKLY, MONTHLY }

enum class InkTool { PEN, HIGHLIGHTER }

/**
 * One ink stroke. [points] is a flat list of (x, y, pressure) triples. x and y are divided by the
 * canvas width, so a drawing looks identical at any size.
 */
@Serializable
data class InkStroke(
    val tool: InkTool = InkTool.PEN,
    val color: Long,
    val width: Float,
    val points: List<Float>,
)

/** What a block contains. Each block type is one subclass; it is stored as JSON in the database. */
@Serializable
sealed interface BlockContent

@Serializable
@SerialName("text")
data class TextContent(val html: String = "") : BlockContent

@Serializable
@SerialName("check")
data class ChecklistContent(
    val text: String = "",
    val done: Boolean = false,
    /** Due date (and time, if [hasTime]) in epoch millis. Null = plain checklist item, not a task. */
    val dueAt: Long? = null,
    val hasTime: Boolean = false,
    /** Minutes before [dueAt] to remind. Null = no reminder. */
    val reminderOffsetMin: Int? = null,
    val repeat: Repeat = Repeat.NONE,
) : BlockContent

@Serializable
@SerialName("image")
data class ImageContent(
    val path: String,
    val width: Int,
    val height: Int,
    val showPins: Boolean = true,
) : BlockContent

@Serializable
@SerialName("drawing")
data class DrawingContent(
    val strokes: List<InkStroke> = emptyList(),
    /** height / width of the drawing page. */
    val aspect: Float = 1.0f,
) : BlockContent

@Serializable
@SerialName("audio")
data class AudioContent(
    val path: String,
    val durationMs: Long,
    val waveform: List<Float> = emptyList(),
) : BlockContent

@Serializable
@SerialName("pdf")
data class PdfContent(
    val path: String,
    val name: String,
    val pageCount: Int,
    /** Ink annotations per page index, kept separately from the original PDF file. */
    val annotations: Map<Int, List<InkStroke>> = emptyMap(),
) : BlockContent

@Serializable
@SerialName("file")
data class FileContent(
    val path: String,
    val name: String,
    val mime: String,
    val size: Long,
) : BlockContent

/** A block as the app works with it: decrypted and decoded. */
data class Block(
    val id: String,
    val content: BlockContent,
)

object BlockTypes {
    const val TEXT = "text"
    const val CHECK = "check"
    const val IMAGE = "image"
    const val DRAWING = "drawing"
    const val AUDIO = "audio"
    const val PDF = "pdf"
    const val FILE = "file"
}

val BlockContent.typeName: String
    get() = when (this) {
        is TextContent -> BlockTypes.TEXT
        is ChecklistContent -> BlockTypes.CHECK
        is ImageContent -> BlockTypes.IMAGE
        is DrawingContent -> BlockTypes.DRAWING
        is AudioContent -> BlockTypes.AUDIO
        is PdfContent -> BlockTypes.PDF
        is FileContent -> BlockTypes.FILE
    }

/** The attachment file this block owns, if any (relative to filesDir). */
val BlockContent.filePath: String?
    get() = when (this) {
        is ImageContent -> path
        is AudioContent -> path
        is PdfContent -> path
        is FileContent -> path
        else -> null
    }

/** Human label used in breadcrumbs ("Photo", "Drawing", "PDF"…). */
val BlockContent.label: String
    get() = when (this) {
        is TextContent -> "Text"
        is ChecklistContent -> "Task"
        is ImageContent -> "Photo"
        is DrawingContent -> "Drawing"
        is AudioContent -> "Recording"
        is PdfContent -> name.ifBlank { "PDF" }
        is FileContent -> name.ifBlank { "File" }
    }

/** Blocks that can carry sub-layers. */
val BlockContent.supportsLayers: Boolean
    get() = this is ImageContent || this is DrawingContent || this is PdfContent ||
        this is FileContent || this is AudioContent

fun encodeContent(content: BlockContent): String = LayersJson.encodeToString<BlockContent>(content)

fun decodeContent(json: String): BlockContent = LayersJson.decodeFromString<BlockContent>(json)
