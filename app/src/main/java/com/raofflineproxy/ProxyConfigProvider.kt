package com.raofflineproxy

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.raofflineproxy.service.ProxyService

class ProxyConfigProvider : ContentProvider() {

    companion object {
        const val COLUMN_PROXY_RUNNING = "proxy_running"
        const val COLUMN_PROXY_HOST = "proxy_host"
        const val COLUMN_PROXY_PORT = "proxy_port"
        const val COLUMN_PROXY_VALUE = "proxy_value"
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val ctx = context ?: return MatrixCursor(emptyArray())
        val running = ProxyService.isRunning(ctx)
        val port = proxyPort(ctx)
        val columns = arrayOf(COLUMN_PROXY_RUNNING, COLUMN_PROXY_HOST, COLUMN_PROXY_PORT, COLUMN_PROXY_VALUE)
        val cursor = MatrixCursor(columns)
        cursor.addRow(arrayOf<Any>(
            if (running) 1 else 0,
            proxyHost(),
            port,
            proxyValue(port)
        ))
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.com.raofflineproxy.config"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
