package com.karen.flymetool.hook.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

class CapturedUpdateProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.karen.flymetool.captured_update_provider"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/update")
        const val PREFS_NAME = "captured_update_prefs"

        private const val CODE_UPDATE = 1
        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "update", CODE_UPDATE)
        }

        private val COLUMNS = arrayOf(
            "updateUrl", "latestVersion", "fileSize",
            "systemVersion", "verType", "packageType", "timestamp"
        )
    }

    private var prefs: android.content.SharedPreferences? = null

    override fun onCreate(): Boolean {
        prefs = context?.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        if (uriMatcher.match(uri) != CODE_UPDATE) return null
        val sp = prefs ?: return null

        val cursor = MatrixCursor(COLUMNS)
        if (sp.contains("updateUrl")) {
            cursor.newRow().apply {
                add(sp.getString("updateUrl", ""))
                add(sp.getString("latestVersion", ""))
                add(sp.getString("fileSize", ""))
                add(sp.getString("systemVersion", ""))
                add(sp.getString("verType", ""))
                add(sp.getInt("packageType", 0))
                add(sp.getLong("timestamp", 0L))
            }
        }
        cursor.setNotificationUri(context?.contentResolver, uri)
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (uriMatcher.match(uri) != CODE_UPDATE) return null
        if (values == null) return null
        val sp = prefs ?: return null

        sp.edit().apply {
            putString("updateUrl", values.getAsString("updateUrl"))
            putString("latestVersion", values.getAsString("latestVersion"))
            putString("fileSize", values.getAsString("fileSize"))
            putString("systemVersion", values.getAsString("systemVersion"))
            putString("verType", values.getAsString("verType"))
            putInt("packageType", values.getAsInteger("packageType") ?: 0)
            putLong("timestamp", values.getAsLong("timestamp") ?: System.currentTimeMillis())
            apply()
        }

        context?.contentResolver?.notifyChange(uri, null)
        return uri
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        if (uriMatcher.match(uri) != CODE_UPDATE) return 0
        val sp = prefs ?: return 0
        sp.edit().clear().apply()
        context?.contentResolver?.notifyChange(uri, null)
        return 1
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        if (insert(uri, values) != null) return 1
        return 0
    }

    override fun getType(uri: Uri): String? {
        return when (uriMatcher.match(uri)) {
            CODE_UPDATE -> "vnd.android.cursor.item/vnd.$AUTHORITY.update"
            else -> null
        }
    }
}
