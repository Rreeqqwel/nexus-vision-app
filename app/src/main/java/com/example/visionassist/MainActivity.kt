package com.example.visionassist

import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.visionassist.ai.SceneAnalyzer
import com.example.visionassist.ai.AnalysisResult
import com.example.visionassist.camera.CameraManager
import com.example.visionassist.tts.TextToSpeechManager
import com.example.visionassist.ui.screens.CameraScreen
import com.example.visionassist.ui.screens.PermissionScreen
import com.example.visionassist.ui.theme.VisionAssistTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var ttsManager: TextToSpeechManager
    private lateinit var sceneAnalyzer: SceneAnalyzer
    private lateinit var cameraManager: CameraManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Enable edge-to-edge display
        enableEdgeToEdge()
        hideSystemUI()
        
        // Initialize managers
        ttsManager = TextToSpeechManager(this)
        sceneAnalyzer = SceneAnalyzer()
        cameraManager = CameraManager(this)

        setContent {
            VisionAssistTheme {
                VisionAssistApp(
                    ttsManager = ttsManager,
                    sceneAnalyzer = sceneAnalyzer,
                    cameraManager = cameraManager,
                    onVibrate = { vibrateDevice() }
                )
            }
        }
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = 
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun vibrateDevice() {
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        vibrator.vibrate(
            VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsManager.shutdown()
        cameraManager.shutdown()
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun VisionAssistApp(
    ttsManager: TextToSpeechManager,
    sceneAnalyzer: SceneAnalyzer,
    cameraManager: CameraManager,
    onVibrate: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Camera permission state
    val cameraPermissionState = rememberPermissionState(
        android.Manifest.permission.CAMERA
    )

    // App state
    var isAnalyzing by remember { mutableStateOf(false) }
    var isTtsReady by remember { mutableStateOf(false) }

    // Observe lifecycle for TTS
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    ttsManager.resume()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    ttsManager.pause()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Initialize TTS
    LaunchedEffect(Unit) {
        ttsManager.initialize { success ->
            isTtsReady = success
            if (success) {
                scope.launch {
                    delay(500)
                    ttsManager.speak(context.getString(R.string.ready_to_assist))
                }
            }
        }
    }

    // Function to handle scene description
    fun describeScene() {
        if (isAnalyzing) return
        
        scope.launch {
            isAnalyzing = true
            onVibrate()
            
            ttsManager.speak(context.getString(R.string.analyzing_scene))
            
            // Get current camera frame (in production, you'd capture from CameraX)
            val bitmap = cameraManager.captureFrame()
            
            // Analyze the scene (using mock for now)
            val result = sceneAnalyzer.analyzeScene(bitmap)
            
            when (result) {
                is AnalysisResult.Success -> {
                    ttsManager.speak(result.description)
                }
                is AnalysisResult.Error -> {
                    ttsManager.speak(context.getString(R.string.error_analyzing))
                }
            }
            
            isAnalyzing = false
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.Black)
        ) {
            if (cameraPermissionState.status.isGranted) {
                // Camera preview with full-screen tap
                CameraScreen(
                    cameraManager = cameraManager,
                    isAnalyzing = isAnalyzing,
                    onTap = { describeScene() },
                    onDoubleTap = { 
                        // Future: OCR functionality
                        scope.launch {
                            ttsManager.speak("Reading text mode - coming soon")
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Permission request screen
                PermissionScreen(
                    onRequestPermission = {
                        cameraPermissionState.launchPermissionRequest()
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
