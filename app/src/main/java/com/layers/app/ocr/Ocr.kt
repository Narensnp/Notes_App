package com.layers.app.ocr

import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.layers.app.util.BitmapUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** On-device text recognition with ML Kit's bundled Latin model. */
object Ocr {

    suspend fun recognize(file: File): String {
        val bitmap = withContext(Dispatchers.IO) { BitmapUtil.decodeSampled(file, 2400) } ?: return ""
        val rotation = withContext(Dispatchers.IO) { BitmapUtil.exifRotation(file) }
        val image = InputImage.fromBitmap(bitmap, rotation)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            recognizer.process(image).await().text
        } finally {
            recognizer.close()
            bitmap.recycle()
        }
    }
}

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
    addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
    addOnCanceledListener { cont.cancel() }
}
