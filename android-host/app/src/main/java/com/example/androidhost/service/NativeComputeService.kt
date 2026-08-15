package com.example.androidhost.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class NativeComputeService : Service() {
    private val binder = LocalBinder()
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    
    private var codeServerProcess: Process? = null

    inner class LocalBinder : android.os.Binder() {
        fun getService(): NativeComputeService = this@NativeComputeService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "START_NCL") {
            startNcl()
        } else if (action == "STOP_NCL") {
            stopNcl()
        }
        return START_NOT_STICKY
    }

    fun startNcl() {
        if (_nclState.value == ComputeState.RUNNING || _nclState.value == ComputeState.STARTING) {
            return
        }
        _nclState.value = ComputeState.STARTING
        
        serviceScope.launch {
            try {
                setupTermuxEnvironment(applicationContext)
                spawnCodeServer(applicationContext)
                _nclState.value = ComputeState.RUNNING
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start NCL", e)
                _nclState.value = ComputeState.STOPPED
            }
        }
    }
    
    fun stopNcl() {
        codeServerProcess?.destroy()
        codeServerProcess = null
        _nclState.value = ComputeState.STOPPED
    }

    override fun onDestroy() {
        super.onDestroy()
        stopNcl()
        serviceJob.cancel()
    }

    private fun setupTermuxEnvironment(context: Context) {
        val termuxDir = File(context.filesDir, "termux")
        if (termuxDir.exists() && termuxDir.list()?.isNotEmpty() == true) {
            Log.i(TAG, "Termux environment already exists")
            return
        }
        
        Log.i(TAG, "Extracting Termux environment...")
        termuxDir.mkdirs()
        
        try {
            val assetManager = context.assets
            val inputStream = assetManager.open("termux.zip")
            val zipInputStream = ZipInputStream(inputStream)
            
            var entry = zipInputStream.nextEntry
            while (entry != null) {
                val newFile = File(termuxDir, entry.name)
                if (entry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()
                    val fos = FileOutputStream(newFile)
                    val buffer = ByteArray(1024)
                    var len: Int
                    while (zipInputStream.read(buffer).also { len = it } > 0) {
                        fos.write(buffer, 0, len)
                    }
                    fos.close()
                    // Android FileOutputStream strips POSIX permissions, restore execute bits
                    if (newFile.parentFile?.name == "bin" || newFile.parentFile?.name == "libexec" || newFile.name.endsWith(".sh")) {
                        newFile.setExecutable(true)
                    }
                }
                zipInputStream.closeEntry()
                entry = zipInputStream.nextEntry
            }
            zipInputStream.close()
            Log.i(TAG, "Termux environment extracted successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract termux.zip from assets, assuming testing mock environment: ${e.message}")
            // Fallback for missing asset in dev
            val mockBin = File(termuxDir, "usr/bin")
            mockBin.mkdirs()
            File(mockBin, "code-server").writeText("#!/system/bin/sh\necho 'Mock code-server started'\nsleep 3600")
            File(mockBin, "bash").writeText("#!/system/bin/sh\nsh")
            File(mockBin, "code-server").setExecutable(true)
            File(mockBin, "bash").setExecutable(true)
        }
    }

    private fun spawnCodeServer(context: Context) {
        val termuxDir = File(context.filesDir, "termux")
        val codeServerBin = File(termuxDir, "usr/bin/code-server")
        
        val processBuilder = ProcessBuilder()
        processBuilder.command(
            codeServerBin.absolutePath,
            "--bind-addr", "127.0.0.1:18080",
            "--auth", "none"
        )
        
        val env = processBuilder.environment()
        env["PREFIX"] = File(termuxDir, "usr").absolutePath
        env["HOME"] = File(termuxDir, "home").absolutePath
        env["PATH"] = "${File(termuxDir, "usr/bin").absolutePath}:${System.getenv("PATH")}"
        
        processBuilder.directory(File(termuxDir, "home").apply { mkdirs() })
        
        try {
            codeServerProcess = processBuilder.start()
            Log.i(TAG, "code-server started on 127.0.0.1:18080")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute code-server", e)
            throw e
        }
    }
    
    companion object {
        private const val TAG = "NativeComputeService"
        private val _nclState = MutableStateFlow(ComputeState.OFF)
        val nclState: StateFlow<ComputeState> = _nclState.asStateFlow()
    }
}
