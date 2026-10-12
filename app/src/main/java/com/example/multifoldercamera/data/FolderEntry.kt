package com.example.multifoldercamera.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一个「拍照入口」对应一个目标文件夹。
 * @param id        自增主键，同时用作桌面快捷方式的唯一 id
 * @param name      入口显示名称（如「家人」「票据」），也用于生成文件名前缀
 * @param rootDir   顶级目录名，例如 "FolderCamera"，最终路径为 Pictures/<rootDir>/<name>
 * @param color     图标颜色（ARGB），用于在桌面图标上区分不同入口
 */
@Entity(tableName = "folder_entries")
data class FolderEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val rootDir: String,
    val color: Int,
    val createdAt: Long = System.currentTimeMillis()
)
