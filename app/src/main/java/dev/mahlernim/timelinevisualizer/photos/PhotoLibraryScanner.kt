package dev.mahlernim.timelinevisualizer.photos

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.time.Instant

class PhotoLibraryScanner(private val context: Context) {
    data class ScanResult(
        val points: List<PhotoPoint>,
        val scannedCount: Int,
        val withoutLocationCount: Int,
    )

    private fun requireOriginal(uri: android.net.Uri): android.net.Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.setRequireOriginal(uri) else uri

    private fun hasMediaLocationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_MEDIA_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true

    suspend fun countTotal(): Int = withContext(Dispatchers.IO) {
        try {
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            context.contentResolver.query(collection, projection, null, null, null)?.use { c -> c.count } ?: 0
        } catch (_: Exception) { 0 }
    }

    suspend fun scan(
        onProgress: ((scanned: Int, withLocation: Int) -> Unit)? = null,
    ): ScanResult = scanWithProgress { scanned, withLocation, _ -> onProgress?.invoke(scanned, withLocation) }

    suspend fun scanWithProgress(
        onProgress: ((scanned: Int, withLocation: Int, total: Int) -> Unit)? = null,
    ): ScanResult = withContext(Dispatchers.IO) {
        // Warn early if GPS will be redacted
        if (!hasMediaLocationPermission()) {
            android.util.Log.w("PhotoScanner", "ACCESS_MEDIA_LOCATION not granted - GPS EXIF will be redacted to 0/0 on this device (see exiftoolwrapper-android issue #8). Callers should request it first.")
        }
        val points = mutableListOf<PhotoPoint>()
        var scanned = 0
        var withoutLocation = 0
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DISPLAY_NAME,
        )
        // Use EXTERNAL_CONTENT_URI to cover all volumes on all API levels
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        // Also scan Video media for geotagged videos (optional)
        val videoCollection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        var cursorError: Exception? = null
        try {
            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_TAKEN} ASC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateTakenColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                val dateAddedColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val dateModifiedColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
                val total = cursor.count
                // Initial progress with total for notification percent
                if (total > 0) onProgress?.invoke(0, 0, total)
                while (cursor.moveToNext()) {
                    // Cooperative cancellation – lets PhotoScanService cancel foreground scan like VideoExportService
                    ensureActive()
                    val id = cursor.getLong(idColumn)
                    val dateTaken = if (dateTakenColumn >= 0 && !cursor.isNull(dateTakenColumn)) cursor.getLong(dateTakenColumn) else 0L
                    val dateAdded = if (dateAddedColumn >= 0 && !cursor.isNull(dateAddedColumn)) cursor.getLong(dateAddedColumn) else 0L
                    val dateModified = if (dateModifiedColumn >= 0 && !cursor.isNull(dateModifiedColumn)) cursor.getLong(dateModifiedColumn) else 0L
                    val fallbackInstant = when {
                        dateTaken > 0 -> Instant.ofEpochMilli(dateTaken)
                        dateAdded > 0 -> Instant.ofEpochMilli(dateAdded * 1000L)
                        dateModified > 0 -> Instant.ofEpochMilli(dateModified * 1000L)
                        else -> null
                    }
                    val uri = android.content.ContentUris.withAppendedId(collection, id)
                    val point = try {
                        PhotoExifReader.extract(context, uri, fallbackInstant)
                    } catch (e: Exception) {
                        android.util.Log.w("PhotoScanner", "EXIF fail for $uri", e)
                        null
                    }
                    scanned++
                    if (point != null) points.add(point) else withoutLocation++
                    // Throttle UI to every 1 item for small libs, every 5 for large; service throttles notification itself
                    if (scanned % 5 == 0 || scanned == total) onProgress?.invoke(scanned, points.size, total)
                    else if (scanned % 20 == 0) onProgress?.invoke(scanned, points.size, total)
                }
            }
            // Debug: also try MediaStore.Files for images on all volumes (Android 10+)
            if (scanned == 0) {
                android.util.Log.w("PhotoScanner", "No images via EXTERNAL_CONTENT_URI, trying FILES")
            }
        } catch (e: Exception) {
            cursorError = e
            android.util.Log.e("PhotoScanner", "Query failed", e)
        }
        android.util.Log.i("PhotoScanner", "Scan done: scanned=$scanned withLocation=${points.size} error=$cursorError hasMediaLocationPerm=${hasMediaLocationPermission()}")
        points.sortBy { it.instant }
        ScanResult(points, scanned, withoutLocation)
    }

    suspend fun diagnoseSingle(uri: android.net.Uri): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        try {
            val hasLocPerm = hasMediaLocationPermission()
            sb.append("ACCESS_MEDIA_LOCATION granted=").append(hasLocPerm).append("\n")
            if (!hasLocPerm) {
                sb.append("⚠️ Thiếu quyền ACCESS_MEDIA_LOCATION nên hệ thống sẽ che GPS (0/1,0/1,0/1) dù file gốc có GPS. Hãy cấp quyền này rồi chẩn đoán lại.\n")
                sb.append("  -> Giống exiftoolwrapper-android/MainActivity.requestPermissions: cần READ_MEDIA_IMAGES + ACCESS_MEDIA_LOCATION\n")
            }
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                sb.append("MediaStore columns: ")
                c.columnNames.forEach { sb.append(it).append(",") }
                sb.append("\n")
                if (c.moveToFirst()) {
                    for (name in c.columnNames) {
                        val idx = c.getColumnIndex(name)
                        sb.append(name).append("=").append(if (c.isNull(idx)) "null" else c.getString(idx).take(120)).append("; ")
                    }
                    sb.append("\n")
                }
            }
            // Try both plain and requireOriginal to show redaction difference
            for ((label, u) in listOf("plain" to uri, "requireOriginal" to requireOriginal(uri))) {
                val tmp = java.io.File.createTempFile("diag_${label}_", ".jpg", context.cacheDir)
                try {
                    context.contentResolver.openInputStream(u)?.use { input -> tmp.outputStream().use { out -> input.copyTo(out) } }
                    sb.append("--- $label (via ${if (label=="requireOriginal") "MediaStore.setRequireOriginal" else "plain openInputStream"}) size=${tmp.length()} ---\n")
                    try {
                        val exif = androidx.exifinterface.media.ExifInterface(tmp.absolutePath)
                        sb.append("ExifInterface latLong=").append(exif.latLong?.joinToString(",")).append("\n")
                        sb.append(" GPS_LAT=").append(exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_GPS_LATITUDE)).append("\n")
                        sb.append(" GPS_LON=").append(exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_GPS_LONGITUDE)).append("\n")
                        sb.append(" GPS_LAT_REF=").append(exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_GPS_LATITUDE_REF)).append("\n")
                        sb.append(" GPS_LON_REF=").append(exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_GPS_LONGITUDE_REF)).append("\n")
                    } catch (e: Exception) { sb.append("ExifInterface error: ").append(e.message).append("\n") }
                    try {
                        val metadata = com.drew.imaging.ImageMetadataReader.readMetadata(tmp)
                        val gps = metadata.getFirstDirectoryOfType(com.drew.metadata.exif.GpsDirectory::class.java)
                        sb.append("metadata-extractor GPS=").append(gps?.geoLocation?.let { "${it.latitude},${it.longitude} isZero=${it.isZero}" } ?: "null").append("\n")
                        if (gps != null) {
                            for (tag in gps.tags) sb.append("  ${tag.tagName}=${tag.description}\n")
                        }
                        sb.append("All dirs: ").append(metadata.directories.joinToString { it.name }).append("\n")
                    } catch (e: Exception) { sb.append("metadata-extractor error: ").append(e.message).append("\n") }
                } finally { tmp.delete() }
            }
            try {
                val pt = PhotoExifReader.extract(context, uri, null)
                sb.append("PhotoExifReader.extract=").append(pt?.let { "${it.latitude},${it.longitude} @ ${it.instant}" } ?: "null").append("\n")
                if (pt == null && !hasLocPerm) {
                    sb.append("Gợi ý: Cấp quyền vị trí media (ACCESS_MEDIA_LOCATION) trong Cài đặt -> Ứng dụng -> Timeline Visualizer -> Quyền, rồi quét lại. Hoặc mở exiftoolwrapper-android để đối chiếu.\n")
                }
            } catch (e: Exception) { sb.append("extract error: ").append(e.message).append("\n") }
        } catch (e: Exception) {
            sb.append("Error: ").append(e.message)
        }
        sb.toString()
    }
}
