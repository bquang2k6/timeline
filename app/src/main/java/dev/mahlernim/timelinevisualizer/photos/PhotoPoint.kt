package dev.mahlernim.timelinevisualizer.photos

import android.net.Uri
import java.time.Instant

data class PhotoPoint(
    val uri: Uri,
    val latitude: Double,
    val longitude: Double,
    val instant: Instant,
    val accuracyMeters: Double? = null,
)
