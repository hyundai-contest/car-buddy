package ai.carbuddy.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import timber.log.Timber

/** Sharing without the companion server: a picture in the car's gallery, or a text QR for the phone. */
object ShareImage {

    /** Saves [bitmap] as a PNG under Pictures/CarBuddy. @return its uri, or null (API < 29, no storage). */
    fun saveToPictures(ctx: Context, bitmap: Bitmap, name: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CarBuddy")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val r = ctx.contentResolver
            val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            r.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: return null
            r.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri
        }.onFailure { Timber.tag("CB/share").w(it, "image save failed") }.getOrNull()
    }

    /** QR code for plain UTF-8 text (a phone camera reads it and offers to copy / share the text). */
    fun qr(text: String): BitMatrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1),
    )
}
