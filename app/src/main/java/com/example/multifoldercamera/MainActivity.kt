package com.example.multifoldercamera

import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.multifoldercamera.data.AppDatabase
import com.example.multifoldercamera.data.FolderEntry
import com.example.multifoldercamera.data.FolderRepository
import com.example.multifoldercamera.databinding.ActivityMainBinding
import com.example.multifoldercamera.util.ShortcutHelper
import kotlinx.coroutines.launch
import java.util.Random

/**
 * 入口管理页：列出所有拍照入口，支持新增、删除，
 * 并把每个入口「固定」到桌面形成独立图标。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: FolderRepository
    private val palette = listOf(
        0xFF2E7D32.toInt(), 0xFF1565C0.toInt(), 0xFFC62828.toInt(),
        0xFF6A1B9A.toInt(), 0xFFEF6C00.toInt(), 0xFF00838F.toInt()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = FolderRepository(AppDatabase.getDatabase(this).folderEntryDao())

        binding.fabAdd.setOnClickListener { showAddDialog() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.allEntries.collect { renderList(it) }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!ShortcutHelper.canPinShortcut(this)) {
                Toast.makeText(this, "当前启动器不支持固定快捷方式", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "Android 8.0 以下不支持桌面快捷方式", Toast.LENGTH_LONG).show()
        }
    }

    private fun renderList(entries: List<FolderEntry>) {
        binding.listContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (entry in entries) {
            val item = inflater.inflate(R.layout.item_folder, binding.listContainer, false)
            item.findViewById<TextView>(R.id.tvName).text = entry.name
            item.findViewById<TextView>(R.id.tvPath).text =
                "Pictures/${entry.rootDir}/${entry.name}"
            item.findViewById<Button>(R.id.btnPin).setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ShortcutHelper.pinShortcut(this, entry)
                } else {
                    Toast.makeText(this, "系统版本过低，不支持", Toast.LENGTH_SHORT).show()
                }
            }
            item.findViewById<Button>(R.id.btnDelete).setOnClickListener {
                lifecycleScope.launch { repo.delete(entry) }
            }
            binding.listContainer.addView(item)
        }
    }

    private fun showAddDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_folder, null)
        val nameEdit = view.findViewById<EditText>(R.id.etName)
        val rootEdit = view.findViewById<EditText>(R.id.etRoot)
        AlertDialog.Builder(this)
            .setTitle("新建拍照入口")
            .setView(view)
            .setPositiveButton("确定") { _, _ ->
                val name = nameEdit.text.toString().trim()
                val root = rootEdit.text.toString().trim().ifEmpty { "FolderCamera" }
                if (name.isEmpty()) {
                    Toast.makeText(this, "请填写名称", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val color = palette[Random().nextInt(palette.size)]
                lifecycleScope.launch {
                    repo.insert(FolderEntry(name = name, rootDir = root, color = color))
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
