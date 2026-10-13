package com.quickanswer.app

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/**
 * 本地 OCR（ML Kit 中文识别，离线可用，无需联网）。
 * 长生命周期复用同一识别器，由调用方负责 [close]。
 */
class OcrEngine {

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    fun recognize(
        bitmap: Bitmap,
        onSuccess: (String) -> Unit,
        onFailure: (String) -> Unit
    ) {
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result -> onSuccess(result.text.trim()) }
            .addOnFailureListener { e -> onFailure(e.message ?: "识别失败") }
    }

    fun close() {
        runCatching { recognizer.close() }
    }
}
