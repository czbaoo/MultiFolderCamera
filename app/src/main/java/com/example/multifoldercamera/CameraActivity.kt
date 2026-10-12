package com.example.multifoldercamera

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.multifoldercamera.data.AppDatabase
import com.example.multifoldercamera.data.FolderEntry
import com.example.multifoldercamera.data.FolderRepository
import com.example.multifoldercamera.databinding.ActivityCameraBinding
import com.example.multifoldercamera.util.MediaStoreHelper
import com.example.multifoldercamera.util.PrefsHelper
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 拍照页：由桌面快捷方式或列表按钮携带 folderId 启动。
 *
 * - 内置相机模式（默认）：拍完**不退出**，可连续拍摄，左下角显示缩略图与计数。
 * - 系统相机模式：点快门调起系统相机 App，拍完返回后**同样不退出**，可继续点快门连拍。
 */
class CameraActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FOLDER_ID = "folder_id"
    }

    private lateinit var binding: ActivityCameraBinding
    private lateinit var repo: FolderRepository
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var currentEntry: FolderEntry? = null
    private var useSystem = false
    private var shotCount = 0
    private var pendingUri: Uri? = null

    private val systemCameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = pendingUri
        if (result.resultCode == RESULT_OK && uri != null) {
            shotCount++
            updateCountView()
            showThumbnail(uri)
        } else if (uri != null) {
            // 取消拍摄，清理占位文件
            runCatching { contentResolver.delete(uri, null, null) }
        }
        // 关键：不退出，用户可继续拍摄
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
            } else {
                startCamera()
            }
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

    /* ---------------- 内置相机：连续拍摄 ---------------- */

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder().build()
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
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
