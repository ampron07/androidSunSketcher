package com.wkuxr.sunsketcher.database

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

// Android version of the iOS exportMetadataToJSON() / exportMetadataToCSV() functions.
// Writes metadata.json and metadata.csv to the phone's shared "Documents/SunSketcher" folder,
// where they can be seen in the Files app or over USB.
object MetadataExporter {

    private const val TAG = "MetadataExporter"
    private val FOLDER = Environment.DIRECTORY_DOCUMENTS + "/SunSketcher/"

    // Call this once the photo sequence is finished.
    fun exportAll(context: Context) {
        val metas = MetadataDB.createDB(context).getMetadata()
        writeFile(context, "metadata.json", "application/json", buildJson(metas))
        writeFile(context, "metadata.csv", "text/csv", buildCsv(metas))
    }

    // Same field names as the iOS JSON so both platforms produce matching files.
    private fun buildJson(metas: List<Metadata>): String {
        val array = JSONArray()
        for (m in metas) {
            val obj = JSONObject()
            obj.put("id", m.id)
            obj.put("latitude", m.latitude)
            obj.put("longitude", m.longitude)
            obj.put("altitude", m.altitude)
            obj.put("filepath", m.filepath)
            obj.put("captureTime", m.captureTime)
            obj.put("aperture", m.fstop)          // Android calls this "fstop"
            obj.put("iso", m.iso)
            obj.put("exposureTime", m.exposure)   // Android calls this "exposure"
            obj.put("whiteBalance", m.whiteBalance)
            obj.put("focalDistance", m.focalDistance)
            obj.put("isCropped", if (m.isCropped) 1 else 0)
            array.put(obj)
        }
        return array.toString(2) // 2 = pretty-print with 2-space indent
    }

    private fun buildCsv(metas: List<Metadata>): String {
        val sb = StringBuilder()
        sb.append("id,latitude,longitude,altitude,filepath,captureTime,aperture,iso,exposureTime,whiteBalance,focalDistance,isCropped\n")
        for (m in metas) {
            sb.append(listOf(
                m.id.toString(),
                m.latitude.toString(),
                m.longitude.toString(),
                m.altitude.toString(),
                csvEscape(m.filepath),
                m.captureTime.toString(),
                m.fstop.toString(),
                m.iso.toString(),
                m.exposure.toString(),
                m.whiteBalance.toString(),
                csvEscape(m.focalDistance),
                if (m.isCropped) "1" else "0"
            ).joinToString(",")).append("\n")
        }
        return sb.toString()
    }

    // Wrap a value in quotes if it contains a comma, quote, or newline (same rule as iOS).
    private fun csvEscape(value: String): String {
        val v = value.replace("\"", "\"\"")
        return if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) "\"$v\"" else v
    }

    // Saves text to Documents/SunSketcher/<name> through MediaStore.
    // If the file already exists (from an earlier export), it is overwritten instead of duplicated.
    private fun writeFile(context: Context, name: String, mimeType: String, text: String) {
        try {
            val resolver = context.contentResolver
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

            val uri: Uri = findExisting(context, collection, name) ?: run {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
                }
                resolver.insert(collection, values) ?: throw IllegalStateException("MediaStore insert returned null")
            }

            // "wt" = open for writing and truncate (erase old contents first)
            resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            Log.d(TAG, "Exported $name to $FOLDER")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export $name", e)
        }
    }

    private fun findExisting(context: Context, collection: Uri, name: String): Uri? {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        context.contentResolver.query(collection, projection, selection, arrayOf(name, FOLDER), null)?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                return Uri.withAppendedPath(collection, id.toString())
            }
        }
        return null
    }
}