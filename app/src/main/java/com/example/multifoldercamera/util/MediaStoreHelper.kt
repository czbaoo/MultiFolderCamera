package com.example.multifoldercamera.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.multifoldercamera.data.FolderEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 使用 MediaStore 把照片写入指定子目录（分区存储适配）。
 * 最终路径：Pictures/<rootDir>/<name>/<name>_时间戳.jpg
 * 该路径会被系统相册识别并按子目录归类。
 */
object MediaStoreHelper {

    fun saveToFolder(context: Context, bytes: ByteArray, entry: FolderEntry): Uri? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val displayName = "${entry.name}_$timeStamp.jpg"
        val relativePath = "Pictures/${entry.rootDir}/${entry.name}"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                put(MediaStore.Images.Media.DATA, "$dir/$relativePath/$displayName")
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val uri = context.contentResolver.insert(collection, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        }

        // 主动通知媒体库扫描，避免部分机型相册延迟显示
        MediaScannerConnection.scanFile(context, arrayOf(uri.toString()), arrayOf("image/jpeg"), null)
        return uri
    }

    /**
     * 为「系统相机」模式预先创建一个输出 URI。
     * 不设置 IS_PENDING，以便相机应用（其它进程）能够直接写入。
     */
    fun createOutputUri(context: Context, entry: FolderEntry): Uri? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val displayName = "${entry.name}_$timeStamp.jpg"
        val relativePath = "Pictures/${entry.rootDir}/${entry.name}"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                put(MediaStore.Images.Media.DATA, "$dir/$relativePath/$displayName")
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        return context.contentResolver.insert(collection, values)
    }
}
