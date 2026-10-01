package com.raofflineproxy

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import androidx.core.net.toUri
import com.raofflineproxy.service.ProxyControl
import com.raofflineproxy.service.ProxyService

class ProxyConfigProvider : ContentProvider() {

    companion object {
        const val COLUMN_PROXY_RUNNING = "proxy_running"
        const val COLUMN_PROXY_HOST = "proxy_host"
        const val COLUMN_PROXY_PORT = "proxy_port"
        const val COLUMN_PROXY_VALUE = "proxy_value"

        const val METHOD_START = "start"
        const val METHOD_STOP = "stop"
        const val METHOD_STATUS = "status"
        const val EXTRA_RESULT = "result"
        const val EXTRA_STATUS = "status"

        fun controlPermission(context: Context): String = "${context.packageName}.permission.CONTROL_PROXY"

        fun contentUri(context: Context): Uri = "content://${context.packageName}.config".toUri()

        fun notifyStatusChanged(context: Context) {
            context.contentResolver.notifyChange(contentUri(context), null, 0)
        }
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

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        if (method != METHOD_STATUS && method != METHOD_START && method != METHOD_STOP) return null
        if (method != METHOD_STATUS) enforceControlPermission(ctx)

        // Services are started and stopped as this app, not as the calling one.
        val identity = Binder.clearCallingIdentity()
        try {
            val result = when (method) {
                METHOD_START -> ProxyControl.start(ctx)
                METHOD_STOP -> ProxyControl.stop(ctx)
                else -> null
            }
            return Bundle().apply {
                result?.let { putString(EXTRA_RESULT, it.wire) }
                putString(EXTRA_STATUS, ProxyControl.status(ctx).toJson())
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun enforceControlPermission(context: Context) {
        val permission = controlPermission(context)
        if (context.checkCallingOrSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Controlling the proxy requires $permission")
        }
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.com.raofflineproxy.config"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
