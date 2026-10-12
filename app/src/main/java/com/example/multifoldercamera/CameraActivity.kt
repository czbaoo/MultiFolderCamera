package com.example.multifoldercamera

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.Size
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.multifoldercamera.data.AppDatabase
import com.example.multifoldercamera.data.FolderEntry
import com.example.multifoldercamera.data.FolderRepository
import com.example.multifoldercamera.databinding.ActivityCameraBinding
import com.example.multifoldercamera.util.MediaStoreHelper
import com.example.multifoldercamera.util.PrefsHelper
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 拍照页：由桌面快捷方式或列表按钮携带 folderId 启动。
 *
 * - 内置相机模式（默认）：拍完**不退出**，可连续拍摄；顶部可切换相机扩展模式
 *   （自动 / HDR / 夜景 / 人像 / 美颜，取决于设备厂商支持情况）。
 * - 系统相机模式：点快门调起系统相机 App，拍完返回后**同样不退出**，可继续连拍。
 * - 进入前会申请「相机」权限（Android 9 及以下额外申请存储权限）。
 */
class CameraActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FOLDER_ID = "folder_id"
        private const val MODE_NONE = -1
    }

    private lateinit var binding: ActivityCameraBinding
    private lateinit var repo: FolderRepository
    private lateinit var cameraExecutor: ExecutorService

    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null
    private var imageCapture: ImageCapture? = null
    private var currentEntry: FolderEntry? = null
    private var useSystem = false
    private var shotCount = 0
    private var pendingUri: Uri? = null
    private var currentMode = MODE_NONE

    /** 系统相机拍摄结果回调 */
    private val systemCameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = pendingUri
        if (result.resultCode == RESULT_OK && uri != null) {
            shotCount++
            updateCountView()
            showThumbnail(uri)
        } else if (uri != null) {
            runCatching { contentResolver.delete(uri, null, null) }
        }
        // 关键：不退出，用户可继续拍摄
    }

    /** 权限申请结果回调 */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.CAMERA] == true) {
            initCamera()
        } else {
            showPermissionDeniedDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = FolderRepository(AppDatabase.getDatabase(this).folderEntryDao())
        cameraExecutor = Executors.newSingleThreadExecutor()
        useSystem = PrefsHelper.useSystemCamera(this)

        binding.btnShutter.setOnClickListener { onShutterClicked() }
        binding.btnClose.setOnClickListener { finish() }

        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            initCamera()
        } else {
            Toast.makeText(this, "拍照需要使用相机权限，请点击「允许」", Toast.LENGTH_LONG).show()
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun requiredPermissions(): List<String> {
        val list = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        return list
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要相机权限")
            .setMessage("拍照功能需要相机权限。请在系统设置中允许「相机」权限后重试。")
            .setCancelable(false)
            .setPositiveButton("去设置") { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)
                    )
                )
                finish()
            }
            .setNegativeButton("取消") { _, _ -> finish() }
            .show()
    }

    private fun initCamera() {
        val folderId = intent.getLongExtra(EXTRA_FOLDER_ID, -1L)
        lifecycleScope.launch {
            currentEntry = repo.getById(folderId)
            val entry = currentEntry
            if (entry == null) {
                Toast.makeText(this@CameraActivity, "未找到文件夹配置", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            binding.tvHint.text = entry.name
            binding.dotColor.backgroundTintList = ColorStateList.valueOf(entry.color)
            if (useSystem) {
                binding.viewFinder.visibility = View.GONE
                binding.systemHint.visibility = View.VISIBLE
                binding.modeScroll.visibility = View.GONE
            } else {
                setupCamera()
            }
        }
    }

    /* ---------------- 内置相机 + CameraX Extensions ---------------- */

    private fun setupCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                // 查询设备支持的扩展模式（自动/HDR/夜景/人像/美颜）
                val extFuture = ExtensionsManager.getInstanceAsync(this, provider)
                extFuture.addListener({
                    extensionsManager = runCatching { extFuture.get() }.getOrNull()
                    buildModeChips()
                    bindWithMode(MODE_NONE)
                }, ContextCompat.getMainExecutor(this))
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "相机初始化失败", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** 根据设备支持的扩展模式生成模式切换条 */
    private fun buildModeChips() {
        val ext = extensionsManager
        val items = mutableListOf("普通" to MODE_NONE)
        if (ext != null) {
            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            fun addIfAvailable(mode: Int, label: String) {
                if (ext.isExtensionAvailable(selector, mode)) items.add(label to mode)
            }
            addIfAvailable(ExtensionMode.AUTO, "自动")
            addIfAvailable(ExtensionMode.HDR, "HDR")
            addIfAvailable(ExtensionMode.NIGHT, "夜景")
            addIfAvailable(ExtensionMode.BOKEH, "人像")
            addIfAvailable(ExtensionMode.FACE_RETOUCH, "美颜")
        }

        binding.modeGroup.removeAllViews()
        items.forEach { (label, mode) ->
            val chip = Chip(this).apply {
                text = label
                isCheckable = true
                isChecked = mode == currentMode
                setOnClickListener { if (mode != currentMode) bindWithMode(mode) }
            }
            binding.modeGroup.addView(chip)
        }
        // 只有设备支持扩展时才显示模式条
        binding.modeScroll.visibility = if (items.size > 1) View.VISIBLE else View.GONE
    }

    /** 以指定模式绑定相机（mode = MODE_NONE 表示不启用扩展） */
    private fun bindWithMode(mode: Int) {
        val provider = cameraProvider ?: return
        currentMode = mode
        val baseSelector = CameraSelector.DEFAULT_BACK_CAMERA
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
        imageCapture = capture
        provider.unbindAll()
        try {
            val ext = extensionsManager
            val selector = if (mode != MODE_NONE && ext != null &&
                ext.isExtensionAvailable(baseSelector, mode)
            ) {
                ext.getExtensionEnabledCameraSelector(baseSelector, mode)
            } else {
                baseSelector
            }
            provider.bindToLifecycle(this, selector, preview, capture)
        } catch (e: Exception) {
            e.printStackTrace()
            // 扩展绑定失败则回退到普通模式
            runCatching { provider.bindToLifecycle(this, baseSelector, preview, capture) }
        }
    }

    private fun onShutterClicked() {
        val entry = currentEntry ?: return
        if (useSystem) startSystemCameraFlow(entry) else takePhoto(entry)
    }

    private fun updateCountView() {
        binding.tvCount.visibility = View.VISIBLE
        binding.tvCount.text = "已拍 $shotCount 张"
    }

    private fun takePhoto(entry: FolderEntry) {
        val capture = imageCapture ?: run {
            Toast.makeText(this, "相机未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnShutter.isEnabled = false
        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()
                val uri = MediaStoreHelper.saveToFolder(this@CameraActivity, bytes, entry)
                runOnUiThread {
                    binding.btnShutter.isEnabled = true
                    if (uri != null) {
                        shotCount++
                        updateCountView()
                        showThumbnail(uri)
                    } else {
                        Toast.makeText(this@CameraActivity, "保存失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            override fun onError(exc: ImageCaptureException) {
                runOnUiThread {
                    binding.btnShutter.isEnabled = true
                    Toast.makeText(this@CameraActivity, "拍摄失败", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    /** 显示最近一张照片的缩略图（左下角） */
    private fun showThumbnail(uri: Uri) {
        try {
            val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.loadThumbnail(uri, Size(160, 160), null)
            } else {
                val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
                contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, opts)
                }
            }
            binding.ivThumb.setImageBitmap(bmp)
            binding.ivThumb.visibility = View.VISIBLE
        } catch (e: Exception) {
            // 缩略图失败不影响主流程
        }
    }

    /* ---------------- 系统相机模式（可连拍） ---------------- */

    private fun startSystemCameraFlow(entry: FolderEntry) {
        val uri = MediaStoreHelper.createOutputUri(this, entry)
        if (uri == null) {
            Toast.makeText(this, "无法创建保存文件", Toast.LENGTH_SHORT).show()
            return
        }
        pendingUri = uri
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            systemCameraLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "未找到系统相机应用", Toast.LENGTH_SHORT).show()
            runCatching { contentResolver.delete(uri, null, null) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
