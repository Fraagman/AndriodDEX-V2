package com.example.androidhost.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executors

enum class VmState {
    OFF,
    STARTING,
    RUNNING,
    STOPPED,
    UNSUPPORTED
}

class VmService : Service() {

    private val binder = LocalBinder()
    
    inner class LocalBinder : android.os.Binder() {
        fun getService(): VmService = this@VmService
    }

    private var virtualMachineInstance: Any? = null
    private var virtualMachineClass: Class<*>? = null

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        checkSupport()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "START_VM") {
            startVm("/data/local/tmp/vm.img")
        } else if (action == "STOP_VM") {
            stopVm()
        }
        return START_NOT_STICKY
    }

    private fun checkSupport() {
        if (Build.VERSION.SDK_INT >= 33 && packageManager.hasSystemFeature("android.software.virtualization")) {
            try {
                Class.forName("android.system.virtualmachine.VirtualMachineManager")
                _vmState.value = VmState.OFF
            } catch (e: ClassNotFoundException) {
                _vmState.value = VmState.UNSUPPORTED
                Log.e(TAG, "VirtualMachineManager class not found, AVF is not supported")
            }
        } else {
            _vmState.value = VmState.UNSUPPORTED
            Log.e(TAG, "AVF is not supported on this device")
        }
    }

    fun startVm(payloadPath: String) {
        if (_vmState.value == VmState.UNSUPPORTED) {
            Log.e(TAG, "Cannot start VM: Unsupported")
            return
        }

        if (_vmState.value == VmState.RUNNING || _vmState.value == VmState.STARTING) {
            Log.w(TAG, "VM is already running or starting")
            return
        }

        val payloadFile = File(payloadPath)
        // Disable file existence check for MVP testing since file might be pushed via adb later
        // if (!payloadFile.exists()) {
        //     Log.e(TAG, "Payload file not found: $payloadPath")
        //     return
        // }

        _vmState.value = VmState.STARTING
        Log.i(TAG, "VM state: STARTING")

        try {
            // Use reflection to access AVF APIs to avoid compilation errors
            val vmmClass = Class.forName("android.system.virtualmachine.VirtualMachineManager")
            val getInstanceMethod = vmmClass.getMethod("getInstance", Context::class.java)
            val vmmInstance = getInstanceMethod.invoke(null, this)

            val configBuilderClass = Class.forName("android.system.virtualmachine.VirtualMachineConfig\$Builder")
            val configBuilderInstance = configBuilderClass.getConstructor(Context::class.java).newInstance(this)

            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    // Try to configure with payload. Methods might differ by exact Android version.
                    val setPayloadMethod = configBuilderClass.getMethod("setPayloadBinaryName", String::class.java)
                    setPayloadMethod.invoke(configBuilderInstance, payloadFile.name)
                    
                    val buildMethod = configBuilderClass.getMethod("build")
                    val configInstance = buildMethod.invoke(configBuilderInstance)

                    val createMethod = vmmClass.getMethod("create", String::class.java, Class.forName("android.system.virtualmachine.VirtualMachineConfig"))
                    virtualMachineInstance = createMethod.invoke(vmmInstance, "AndroidDex_VM", configInstance)
                    virtualMachineClass = Class.forName("android.system.virtualmachine.VirtualMachine")

                    Log.i(TAG, "VM created")
                    
                    val callbackClass = Class.forName("android.system.virtualmachine.VirtualMachineCallback")
                    val callbackProxy = Proxy.newProxyInstance(
                        classLoader,
                        arrayOf(callbackClass),
                        object : InvocationHandler {
                            override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
                                when (method.name) {
                                    "onPayloadStarted" -> {
                                        _vmState.value = VmState.RUNNING
                                        Log.i(TAG, "VM state: RUNNING")
                                    }
                                    "onPayloadReady" -> {
                                        Log.i(TAG, "VM payload ready")
                                    }
                                    "onPayloadFinished" -> {
                                        _vmState.value = VmState.STOPPED
                                        val exitCode = args?.getOrNull(1) as? Int ?: -1
                                        Log.i(TAG, "VM state: STOPPED with exit code $exitCode")
                                    }
                                    "onError" -> {
                                        _vmState.value = VmState.STOPPED
                                        val errorCode = args?.getOrNull(1) as? Int ?: -1
                                        val message = args?.getOrNull(2) as? String ?: ""
                                        Log.e(TAG, "VM error: $errorCode - $message")
                                    }
                                    "onStopped" -> {
                                        _vmState.value = VmState.STOPPED
                                        val reason = args?.getOrNull(1) as? Int ?: -1
                                        Log.i(TAG, "VM state: STOPPED reason $reason")
                                    }
                                }
                                return null
                            }
                        }
                    )
                    
                    val setCallbackMethod = virtualMachineClass?.getMethod("setCallback", java.util.concurrent.Executor::class.java, callbackClass)
                    setCallbackMethod?.invoke(virtualMachineInstance, Executors.newSingleThreadExecutor(), callbackProxy)
                    
                    val runMethod = virtualMachineClass?.getMethod("run")
                    runMethod?.invoke(virtualMachineInstance)

                } catch (e: Exception) {
                    Log.e(TAG, "Error configuring/starting VM: ${e.message}", e)
                    _vmState.value = VmState.STOPPED
                }
            } else {
                Log.e(TAG, "AVF custom VMs require API 34+")
                _vmState.value = VmState.UNSUPPORTED
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VM using reflection", e)
            _vmState.value = VmState.STOPPED
        }
    }

    fun stopVm() {
        if (_vmState.value == VmState.RUNNING || _vmState.value == VmState.STARTING) {
            try {
                val stopMethod = virtualMachineClass?.getMethod("stop")
                stopMethod?.invoke(virtualMachineInstance)
                _vmState.value = VmState.STOPPED
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping VM", e)
            }
        }
    }

    companion object {
        private const val TAG = "VmService"
        private val _vmState = MutableStateFlow(VmState.OFF)
        val vmState: StateFlow<VmState> = _vmState.asStateFlow()
    }
}
