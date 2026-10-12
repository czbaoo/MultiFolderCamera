package com.example.multifoldercamera.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.os.Build
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import com.example.multifoldercamera.CameraActivity
import com.example.multifoldercamera.data.FolderEntry

/**
 * 负责把每个「拍照入口」钉（Pin）到桌面，形成多个独立图标。
 * 使用 Pinned Shortcut，数量无硬限制，且每个 Intent 携带 folderId 区分目标文件夹。
 */
object ShortcutHelper {

    /** 当前启动器是否支持固定快捷方式（Android 8.0+ 才有） */
    @RequiresApi(Build.VERSION_CODES.O)
    fun canPinShortcut(context: Context): Boolean {
        val sm = context.getSystemService<ShortcutManager>() ?: return false
        return sm.isRequestPinShortcutSupported
    }

    /** 请求把某个入口的固定快捷方式添加到桌面 */
    @RequiresApi(Build.VERSION_CODES.O)
    fun pinShortcut(context: Context, entry: FolderEntry) {
        val sm = context.getSystemService<ShortcutManager>() ?: return

        val intent = Intent(context, CameraActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            // 关键点：携带 folderId，CameraActivity 据此决定保存到哪个文件夹
            putExtra(CameraActivity.EXTRA_FOLDER_ID, entry.id)
        }

        val info = ShortcutInfo.Builder(context, "shot_${entry.id}")
            .setShortLabel(entry.name)
            .setLongLabel(entry.name)
            .setIcon(Icon.createWithBitmap(makeIcon(entry.name, entry.color)))
            .setIntent(intent)
            .build()

        // requestPinShortcut 必须由用户前台交互触发（如点击按钮），符合 Android 12+ 规范
        val ok = sm.requestPinShortcut(info, null)
        Toast.makeText(
            context,
            if (ok) "已发送到桌面，请确认添加" else "添加失败，启动器可能不支持",
            Toast.LENGTH_SHORT
        ).show()
    }

    /** 用入口名称首字母 + 颜色生成一张可区分的方形图标 */
    private fun makeIcon(name: String, color: Int): Bitmap {
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val bg = Paint().apply { this.color = color; isAntiAlias = true }
        canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), 36f, 36f, bg)

        val letter = name.firstOrNull()?.uppercase() ?: "#"
        val text = Paint().apply {
            this.color = Color.WHITE
            textSize = 110f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        val y = (size / 2f) - ((text.descent() + text.ascent()) / 2f)
        canvas.drawText(letter, size / 2f, y, text)

        return bmp
    }
}
