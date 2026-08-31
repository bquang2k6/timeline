package dev.mahlernim.timelinevisualizer.photos

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.drew.metadata.exif.GpsDirectory
import com.drew.imaging.ImageMetadataReader
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object PhotoExifReader {
    private val exifDateFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
    private val exifDateFormatterWithMs = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss.SSS")

    /**
     * On Android 10+ the system redacts GPS EXIF if caller lacks ACCESS_MEDIA_LOCATION.
     * Even when permission is granted, callers must use MediaStore.setRequireOriginal()
     * to obtain unredacted bytes. See exiftoolwrapper-android/MainActivity.requestPermissions
     * and https://developer.android.com/reference/android/provider/MediaStore#setRequireOriginal
     */
    private fun requireOriginal(uri: Uri): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.setRequireOriginal(uri) else uri

    private fun hasMediaLocationPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_MEDIA_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true

    private fun openInputStreamUnredacted(context: Context, uri: Uri) =
        context.contentResolver.openInputStream(requireOriginal(uri))
            ?: context.contentResolver.openInputStream(uri)

    private fun openFileDescriptorUnredacted(context: Context, uri: Uri) = try {
        context.contentResolver.openFileDescriptor(requireOriginal(uri), "r")
            ?: context.contentResolver.openFileDescriptor(uri, "r")
    } catch (_: Exception) {
        try { context.contentResolver.openFileDescriptor(uri, "r") } catch (_: Exception) { null }
    }

    fun extract(context: Context, uri: Uri, fallbackInstant: Instant?): PhotoPoint? {
        // Try MediaStore LATITUDE/LONGITUDE columns first (deprecated but still present on some ROMs)
        try {
            val queryUri = requireOriginal(uri)
            context.contentResolver.query(queryUri, arrayOf(MediaStore.Images.Media.LATITUDE, MediaStore.Images.Media.LONGITUDE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val latIdx = c.getColumnIndex(MediaStore.Images.Media.LATITUDE)
                    val lonIdx = c.getColumnIndex(MediaStore.Images.Media.LONGITUDE)
                    if (latIdx >= 0 && lonIdx >= 0 && !c.isNull(latIdx) && !c.isNull(lonIdx)) {
                        val lat = c.getDouble(latIdx)
                        val lon = c.getDouble(lonIdx)
                        if (lat != 0.0 || lon != 0.0) {
                            val inst = parseInstantFromExifStream(context, uri) ?: fallbackInstant
                            if (inst != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                                return PhotoPoint(uri, lat, lon, inst, null)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // Copy to temp file first - Xiaomi HDR+ needs file path for correct GPS parsing (MPF)
        // MUST use unredacted stream, otherwise GPS is 0/1,0/1,0/1 on Android 10+ if permission missing
        var tempFile: java.io.File? = null
        try {
            openInputStreamUnredacted(context, uri)?.use { input ->
                tempFile = java.io.File.createTempFile("photo_exif_", ".jpg", context.cacheDir)
                tempFile!!.outputStream().use { out -> input.copyTo(out) }
            }
        } catch (_: Exception) {}
        var lat: Double? = null
        var lon: Double? = null
        var instant: Instant? = null
        // 1. Try androidx.exifinterface via file path (more reliable than InputStream for HDR+)
        if (tempFile != null && tempFile!!.exists()) {
            try {
                val exif = ExifInterface(tempFile!!.absolutePath)
                var latLong = exif.latLong
                if (latLong == null) latLong = parseLatLongManually(exif)
                if (latLong != null) {
                    lat = latLong[0]; lon = latLong[1]
                }
                instant = parseInstant(exif)
            } catch (_: Exception) {}
        }
        if (lat == null || lon == null || lat == 0.0 && lon == 0.0) {
            try {
                val exif = try {
                    openFileDescriptorUnredacted(context, uri)?.use { pfd ->
                        ExifInterface(pfd.fileDescriptor)
                    }
                } catch (_: Exception) { null } ?: openInputStreamUnredacted(context, uri)?.use { input ->
                    ExifInterface(input)
                }
                if (exif != null) {
                    var latLong = exif.latLong
                    if (latLong == null) latLong = parseLatLongManually(exif)
                    if (latLong != null && (lat == null || lat == 0.0 && lon == 0.0)) {
                        lat = latLong[0]; lon = latLong[1]
                    }
                    if (instant == null) instant = parseInstant(exif)
                }
            } catch (_: Exception) {}
        }

        // 2. Fallback to metadata-extractor via temp file (handles Xiaomi HDR+, MPF, XMP where ExifInterface fails)
        if (lat == null || lon == null || lat == 0.0 && lon == 0.0) {
            try {
                val fileForMeta = tempFile ?: run {
                    val tf = java.io.File.createTempFile("photo_meta_", ".jpg", context.cacheDir)
                    openInputStreamUnredacted(context, uri)?.use { input -> tf.outputStream().use { out -> input.copyTo(out) } }
                    tf
                }
                if (fileForMeta.exists() && fileForMeta.length() > 0) {
                    val metadata = ImageMetadataReader.readMetadata(fileForMeta)
                    for (dir in metadata.directories) {
                        if (dir is GpsDirectory) {
                            val geo = dir.geoLocation
                            if (geo != null && !geo.isZero) {
                                lat = geo.latitude; lon = geo.longitude
                                break
                            }
                        }
                    }
                }
                if ((fileForMeta != tempFile) && fileForMeta.exists()) {
                    try { fileForMeta.delete() } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }

        // 3. Last fallback to exiftool binary if bundled (e.g., when app bundles exiftoolwrapper native)
        if ((lat == null || lon == null || lat == 0.0 && lon == 0.0) && isExifToolAvailable(context)) {
            val tt = runExifToolLatLong(context, uri)
            if (tt != null) { lat = tt.first; lon = tt.second }
        }

        try { tempFile?.delete() } catch (_: Exception) {}
        if (lat == null || lon == null) return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || (lat == 0.0 && lon == 0.0)) return null
        // If result is still 0,0 but we lacked ACCESS_MEDIA_LOCATION, caller diagnose will hint to grant it
        val finalInstant = instant ?: fallbackInstant ?: return null
        return PhotoPoint(uri, lat, lon, finalInstant, null)
    }

    private fun parseInstantFromExifStream(context: Context, uri: Uri): Instant? {
        return try {
            val exif = openInputStreamUnredacted(context, uri)?.use { ExifInterface(it) } ?: return null
            parseInstant(exif)
        } catch (_: Exception) { null }
    }

    private fun parseLatLongManually(exif: ExifInterface): DoubleArray? {
        return try {
            val latStr = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE) ?: return null
            val latRef = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF) ?: "N"
            val lonStr = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE) ?: return null
            val lonRef = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF) ?: "E"
            val lat = dmsToDecimal(latStr) ?: return null
            val lon = dmsToDecimal(lonStr) ?: return null
            doubleArrayOf(
                if (latRef == "S") -lat else lat,
                if (lonRef == "W") -lon else lon
            )
        } catch (_: Exception) { null }
    }

    private fun dmsToDecimal(dms: String): Double? {
        // Format: "deg/1, min/1, sec/1" e.g. "21/1,1/1,19247/1000"
        return try {
            val parts = dms.split(",").map { it.trim() }
            if (parts.size != 3) return null
            fun rational(s: String): Double {
                val p = s.split("/")
                return if (p.size == 2) p[0].toDouble() / p[1].toDouble() else s.toDouble()
            }
            rational(parts[0]) + rational(parts[1]) / 60.0 + rational(parts[2]) / 3600.0
        } catch (_: Exception) { null }
    }

    private fun parseInstant(exif: ExifInterface): Instant? {
        // Prefer GPS timestamp (UTC) when available
        val gpsDate = exif.getAttribute(ExifInterface.TAG_GPS_DATESTAMP)
        val gpsTime = exif.getAttribute(ExifInterface.TAG_GPS_TIMESTAMP)
        if (gpsDate != null && gpsTime != null) {
            try {
                val dateTimeStr = "$gpsDate $gpsTime"
                val formatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
                val ldt = LocalDateTime.parse(dateTimeStr, formatter)
                return ldt.atZone(ZoneId.of("UTC")).toInstant()
            } catch (_: Exception) { }
        }
        // Try DATETIME_ORIGINAL with optional subseconds
        val dateOriginal = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            ?: exif.getAttribute(ExifInterface.TAG_GPS_DATESTAMP)
        if (dateOriginal != null) {
            for (fmt in arrayOf(exifDateFormatter, exifDateFormatterWithMs)) {
                try {
                    val cleaned = dateOriginal.substringBefore(".").let { if (it.contains(".")) it else dateOriginal }
                    // exif may include milliseconds after dot
                    val ldt = try { LocalDateTime.parse(dateOriginal, fmt) } catch (_: Exception) { LocalDateTime.parse(cleaned, exifDateFormatter) }
                    return ldt.atZone(ZoneId.systemDefault()).toInstant()
                } catch (_: Exception) {}
            }
        }
        // Fallback to MediaStore will be handled by caller via fallbackInstant
        return null
    }

    private fun isExifToolAvailable(context: Context): Boolean {
        return try {
            val perl = "${context.applicationInfo.nativeLibraryDir}/libperl.so"
            java.io.File(perl).exists()
        } catch (_: Exception) { false }
    }

    private fun runExifToolLatLong(context: Context, uri: Uri): Pair<Double, Double>? {
        return try {
            // Copy URI to cache for exiftool – use unredacted
            val cache = java.io.File(context.cacheDir, "exiftool_tmp_${System.currentTimeMillis()}.jpg")
            openInputStreamUnredacted(context, uri)?.use { input ->
                cache.outputStream().use { out -> input.copyTo(out) }
            } ?: return null
            val perl = "${context.applicationInfo.nativeLibraryDir}/libperl.so"
            val perl5 = java.io.File(context.filesDir, "perl5").absolutePath
            // Only run if perl5 extracted (from exiftoolwrapper)
            if (!java.io.File("$perl5/exiftool").exists()) {
                cache.delete(); return null
            }
            val cmd = listOf(perl, "-I", "$perl5/arch", "-I", "$perl5/lib", "$perl5/exiftool", "-n", "-s", "-s", "-s", "-GPSLatitude", "-GPSLongitude", cache.absolutePath)
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            cache.delete()
            val lines = out.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.size >= 2) {
                val lat = lines[0].toDoubleOrNull()
                val lon = lines[1].toDoubleOrNull()
                if (lat != null && lon != null) lat to lon else null
            } else null
        } catch (_: Exception) { null }
    }
}
