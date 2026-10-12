package com.example.multifoldercamera

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.multifoldercamera.data.AppDatabase
import com.example.multifoldercamera.data.FolderEntry
import com.example.multifoldercamera.data.FolderRepository
import com.example.multifoldercamera.databinding.ActivityCameraBinding
import com.example.multifoldercamera.util.MediaStoreHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 拍照页：由桌面快捷方式携带 folderId 启动，
 * 拍完直接将照片写入该入口对应的文件夹。
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = FolderRepository(AppDatabase.getDatabase(this).folderEntryDao())
        cameraExecutor = Executors.newSingleThreadExecutor()

        val folderId = intent.getLongExtra(EXTRA_FOLDER_ID, -1L)
        CoroutineScope(Dispatchers.IO).launch {
            currentEntry = repo.getById(folderId)
            withContext(Dispatchers.Main) {
                binding.tvHint.text = currentEntry?.let { "保存到：${it.name}" } ?: "未指定文件夹"
            }
        }

        binding.btnShutter.setOnClickListener { takePhoto() }
        binding.btnClose.setOnClickListener { finish() }

        startCamera()
    }

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

    private fun takePhoto() {
        val entry = currentEntry
        val capture = imageCapture
        if (entry == null || capture == null) {
            Toast.makeText(this, "相机未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()
                val uri = MediaStoreHelper.saveToFolder(this@CameraActivity, bytes, entry)
                runOnUiThread {
                    Toast.makeText(
                        this@CameraActivity,
                        if (uri != null) "已保存到「${entry.name}」" else "保存失败",
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
            }

            override fun onError(exc: ImageCaptureException) {
                runOnUiThread {
                    Toast.makeText(this@CameraActivity, "拍摄失败", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
