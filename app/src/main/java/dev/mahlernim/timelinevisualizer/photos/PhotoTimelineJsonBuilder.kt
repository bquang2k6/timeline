package dev.mahlernim.timelinevisualizer.photos

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.mahlernim.timelinevisualizer.model.GeoPoint
import java.time.Instant

object PhotoTimelineJsonBuilder {
    /**
     * Build a Timeline JSON string that TimelineParser can read.
     * Uses one semanticSegments entry per contiguous group (gap < 24h).
     * Each photo becomes a point in timelinePath. Also adds rawSignals for completeness.
     */
    fun build(points: List<PhotoPoint>): String {
        val sorted = points.sortedBy { it.instant }
        return buildFromGeoPoints(sorted.map { GeoPoint(it.instant, it.latitude, it.longitude) })
    }

    fun buildFromGeoPoints(geoPoints: List<GeoPoint>): String {
        val gson = GsonBuilder().create()
        val root = JsonObject()
        val semanticSegments = JsonArray()
        val rawSignals = JsonArray()

        if (geoPoints.isEmpty()) {
            root.add("semanticSegments", semanticSegments)
            return gson.toJson(root)
        }

        // Group by gap > 24h to create separate segments (transfer)
        val groups = mutableListOf<MutableList<GeoPoint>>()
        var current = mutableListOf<GeoPoint>()
        current.add(geoPoints[0])
        for (i in 1 until geoPoints.size) {
            val prev = geoPoints[i - 1]
            val cur = geoPoints[i]
            val gapHours = (cur.instant.toEpochMilli() - prev.instant.toEpochMilli()) / 3_600_000.0
            if (gapHours > 24.0) {
                groups.add(current)
                current = mutableListOf()
            }
            current.add(cur)
        }
        groups.add(current)

        for (group in groups) {
            val segment = JsonObject()
            segment.addProperty("startTime", group.first().instant.toString())
            segment.addProperty("endTime", group.last().instant.toString())
            val path = JsonArray()
            for (p in group) {
                val entry = JsonObject()
                entry.addProperty("point", "${p.latitude},${p.longitude}")
                entry.addProperty("time", p.instant.toString())
                path.add(entry)
            }
            segment.add("timelinePath", path)
            semanticSegments.add(segment)
        }

        for (p in geoPoints) {
            val raw = JsonObject()
            val pos = JsonObject()
            pos.addProperty("LatLng", "${p.latitude},${p.longitude}")
            pos.addProperty("timestamp", p.instant.toString())
            pos.addProperty("accuracyMeters", 15.0)
            raw.add("position", pos)
            rawSignals.add(raw)
        }

        root.add("semanticSegments", semanticSegments)
        root.add("rawSignals", rawSignals)
        return GsonBuilder().setPrettyPrinting().create().toJson(root)
    }

    /**
     * Merge existing GeoPoints with photo points: deduplicate by instant+coordinate and sort.
     */
    fun merge(existing: List<GeoPoint>, photoPoints: List<PhotoPoint>): String {
        val merged = (existing + photoPoints.map { GeoPoint(it.instant, it.latitude, it.longitude) })
            .distinctBy { "${it.instant.toEpochMilli()}:${it.latitude}:${it.longitude}" }
            .sortedBy { it.instant }
        return buildFromGeoPoints(merged)
    }

    fun buildMergedJson(existingJson: String?, photoPoints: List<PhotoPoint>, parser: dev.mahlernim.timelinevisualizer.data.TimelineParser): String {
        if (existingJson == null || photoPoints.isEmpty()) return build(photoPoints)
        return try {
            val input = existingJson.byteInputStream()
            val parsed = parser.parseWithRawSignals(input)
            val existingPoints = parsed.timeline?.points ?: emptyList()
            // also include rawSignals as points if timeline empty
            val rawAsGeo = parsed.rawSignals.map { it.point }
            val allExisting = (existingPoints + rawAsGeo).distinctBy { it.instant to it.latitude }
            merge(allExisting, photoPoints)
        } catch (_: Exception) {
            build(photoPoints)
        }
    }
}
