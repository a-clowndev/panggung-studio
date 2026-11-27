package io.panggung.obs

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.SurfaceTexture
import android.media.projection.MediaProjectionManager
import android.app.PictureInPictureParams
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.util.Rational
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.panggung.obs.androidplus.ThermalGovernor
import io.panggung.obs.androidplus.ThermalState
import io.panggung.obs.capture.AudioInputCapture
import io.panggung.obs.capture.Camera2Capture
import io.panggung.obs.capture.LiveOverlaysEngine
import io.panggung.obs.capture.WebViewCapture
import io.panggung.obs.persistence.PersistedSettings
import io.panggung.obs.persistence.SettingsPersistence
import io.panggung.obs.privilege.ShizukuHelper
import io.panggung.obs.security.StreamKeyStore
import io.panggung.obs.service.FloatingBubbleService
import io.panggung.obs.service.PanggungService
import io.panggung.obs.ui.AboutDialog
import io.panggung.obs.ui.AdvancedAudioDialog
import io.panggung.obs.ui.AutoConfigWizardDialog
import io.panggung.obs.ui.BrowserDockDialog
import io.panggung.obs.ui.FullObsSettingsDialog
import io.panggung.obs.ui.LogViewerDialog
import io.panggung.obs.ui.MultiviewDialog
import io.panggung.obs.ui.ProjectorPresentation
import io.panggung.obs.ui.PropertiesDialog
import io.panggung.obs.ui.StorageAndExportHelper
import io.panggung.obs.ui.TransformDialog
import io.panggung.obs.ui.components.*
import io.panggung.obs.ui.layouts.ResponsiveStudioScaffold
import io.panggung.obs.ui.layouts.StudioCallbacks
import io.panggung.obs.ui.modals.ConfirmStopBroadcastDialog
import io.panggung.obs.ui.modals.CrashRecoveryBanner
import io.panggung.obs.ui.modals.RemoteQrDialog
import io.panggung.obs.ui.modals.SourceContextMenuDialog
import io.panggung.obs.ui.modals.ThermalGovernorBanner
import io.panggung.obs.ui.theme.PanggungColors
import io.panggung.obs.ui.theme.PanggungRadius
import io.panggung.obs.ui.theme.PanggungTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SourceUi(
    val name: String,
    val id: String,
    val visible: Boolean,
    val volume: Float,
    val muted: Boolean,
    val locked: Boolean = false,
    val magnitudeDb: Float = -60f,
    val peakDb: Float = -60f,
)
data class FilterUi(val name: String, val id: String, val enabled: Boolean)

data class SnapshotUi(
    val scenes: List<String> = emptyList(),
    val activeSceneIdx: Int = 0,
    val previewSceneIdx: Int = 0,
    val studioMode: Boolean = false,
    val transitionId: String = "fade_transition",
    val transitionMs: Int = 300,
    val videoEncoderId: String = "android_mediacodec_h264",
    val baseWidth: Int = 1280,
    val baseHeight: Int = 720,
    val sources: List<SourceUi> = emptyList(),
    val fps: Double = 0.0,
    val frameTimeMs: Double = 0.0,
    val totalFrames: Int = 0,
    val laggedFrames: Int = 0,
    val droppedFrames: Int = 0,
    val outputBytes: Long = 0L,
    val congestion: Float = 0f,
    val recording: Boolean = false,
    val streaming: Boolean = false,
    val replayActive: Boolean = false,
)

class MainActivity : ComponentActivity() {
    private var obsReady by mutableStateOf(false)
    private var obsVersion by mutableStateOf("...")
    private var snapshot by mutableStateOf(SnapshotUi())
    private var lastRecordPath by mutableStateOf("")
    private var statusBanner by mutableStateOf("Ready (Remote OFF)")
    private var floatingHubEnabled by mutableStateOf(true)
    private var wantStreaming by mutableStateOf(false)
    private var lastNetGen by mutableStateOf(0L)

    private lateinit var cameraCapture: Camera2Capture
    private lateinit var webViewCapture: WebViewCapture
    private val micCapture = AudioInputCapture()
    private var projector: ProjectorPresentation? = null
    private var pendingMediaKind: String = "image_source"

    private val mediaPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val localPath = copyUriToLocalCache(uri)
            if (localPath != null) {
                val isImg = pendingMediaKind == "image_source"
                val name = (if (isImg) "Image " else "Media ") + (snapshot.sources.size + 1)
                val settings = if (isImg) {
                    JSONObject().put("file", localPath).toString()
                } else {
                    JSONObject().put("local_file", localPath).put("looping", true).toString()
                }
                NativeBridge.nativeAddSource(pendingMediaKind, name, settings)
                statusBanner = "Added $name"
                refreshSnapshot()
            }
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) micCapture.start()
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK && res.data != null) {
            val svcIntent = Intent(this, PanggungService::class.java).apply {
                action = PanggungService.ACTION_START_SCREEN
                putExtra(PanggungService.EXTRA_RESULT_CODE, res.resultCode)
                putExtra(PanggungService.EXTRA_RESULT_DATA, res.data)
            }
            startForegroundService(svcIntent)
            statusBanner = "Screen + Internal Audio active"
        }
    }

    companion object {
        @Volatile var activeCameraToggle: ((Boolean) -> String?)? = null
        @Volatile var currentCameraFront: Boolean = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraCapture = Camera2Capture(this)
        webViewCapture = WebViewCapture(this)
        activeCameraToggle = { front ->
            runOnUiThread { addCamera(front, pipIfScreenActive = true) }
            if (front) "Front Camera" else "Back Camera"
        }

        val dataDir = File(filesDir, "obs-data")
        val configDir = File(filesDir, "obs-config").apply { mkdirs() }
        getExternalFilesDir(Environment.DIRECTORY_MOVIES)?.mkdirs()
        extractAssetsIfNeeded(this, "obs", dataDir)

        val saved = SettingsPersistence.load(this)
        applyOrientationLock(saved.orientationLock)
        val ok = NativeBridge.nativeInit(
            locale = saved.locale,
            dataDir = dataDir.absolutePath,
            moduleDir = applicationInfo.nativeLibraryDir,
            configDir = configDir.absolutePath,
            width = saved.baseWidth,
            height = saved.baseHeight,
            fps = saved.fps,
        )
        obsVersion = NativeBridge.nativeGetVersion()
        obsReady = ok
        if (ok) {
            ThermalGovernor.setKeepScreenOn(this, true)
            NativeBridge.nativeSetVideoEncoder(saved.videoEncoderId)
            NativeBridge.nativeSetTransition(saved.transitionId, saved.transitionMs)
            // Use clean darkroom #0D1117 background instead of bright blue letterbox bars
            NativeBridge.nativeUpdateSourceSettings("Background", """{"color":4279701773}""")
            val savedCol = File(filesDir, "scene_collection.json")
            if (savedCol.exists()) NativeBridge.nativeLoadSceneCollection(savedCol.absolutePath)
            refreshSnapshot()
            requestPermissionsIfNeeded()
        }

        setContent {
            StudioScreen()
        }
    }

    @Composable
    private fun StudioScreen() {
        var selectedTab by remember { mutableIntStateOf(0) }
        var rtmpUrl by remember { mutableStateOf("rtmp://a.rtmp.youtube.com/live2") }
        var streamKey by remember { mutableStateOf(StreamKeyStore.loadStreamKey(this)) }
        var bitrateKbps by remember { mutableFloatStateOf(4500f) }
        var filterTargetSource by remember { mutableStateOf<String?>(null) }
        var propsTargetSource by remember { mutableStateOf<String?>(null) }
        var transformTargetSource by remember { mutableStateOf<String?>(null) }
        var audioAdvTargetSource by remember { mutableStateOf<String?>(null) }
        var contextMenuSource by remember { mutableStateOf<SourceUi?>(null) }
        var selectedSourceName by remember { mutableStateOf<String?>(null) }
        var recPaused by remember { mutableStateOf(false) }
        var showMultiview by remember { mutableStateOf(false) }
        var showWizard by remember { mutableStateOf(false) }
        var showAbout by remember { mutableStateOf(false) }
        var showFullSettings by remember { mutableStateOf(false) }
        var showBrowserDock by remember { mutableStateOf(false) }
        var showLogs by remember { mutableStateOf(false) }
        var showRemoteQr by remember { mutableStateOf(false) }
        var showAddSourceDialog by remember { mutableStateOf(false) }
        var landscapeTabModal by remember { mutableStateOf<Int?>(null) }
        var showConfirmStop by remember { mutableStateOf(false) }
        var dualCanvasOn by remember { mutableStateOf(false) }
        var keepScreenOn by remember { mutableStateOf(true) }
        var orientationLock by remember { mutableStateOf(SettingsPersistence.load(this).orientationLock) }
        var transformVersion by remember { mutableIntStateOf(0) }
        var thermalState by remember { mutableStateOf(ThermalState()) }
        var crashBannerVisible by remember { mutableStateOf(NativeBridge.nativeGetLastCrashLog().isNotBlank()) }
        val coroutineScope = rememberCoroutineScope()

        // High-rate VU polling (80ms) on Mixer tab; 400ms otherwise
        LaunchedEffect(obsReady, selectedTab) {
            while (obsReady) {
                refreshSnapshot()
                delay(if (selectedTab == 1) 80L else 400L)
            }
        }

        // Dual-Loop Thermal + Network Congestion AIMD ABR polling every 2s (v0.2.0)
        LaunchedEffect(obsReady, bitrateKbps, snapshot.recording, snapshot.streaming) {
            while (obsReady) {
                thermalState = ThermalGovernor.sampleAndApply(
                    context = this@MainActivity,
                    baseBitrateKbps = bitrateKbps.toInt(),
                    outputActive = snapshot.recording || snapshot.streaming,
                    congestion = snapshot.congestion,
                    droppedFrames = snapshot.droppedFrames,
                )
                if (thermalState.throttled && (snapshot.streaming || snapshot.recording)) {
                    statusBanner = "ABR (${thermalState.abrReason}): ${thermalState.effectiveBitrateKbps} kbps"
                }
                delay(2000L)
            }
        }

        // IRL Auto-Reconnect & Wi-Fi <-> 5G Handoff Recovery Loop (v0.2.0)
        LaunchedEffect(obsReady, wantStreaming, rtmpUrl, streamKey, bitrateKbps) {
            var retryAttempt = 0
            // Grace period for initial RTMP/RTMPS/SRT handshake and FGS network callback registration
            if (wantStreaming) {
                delay(2500L)
                lastNetGen = PanggungService.networkGeneration.get()
            }
            while (obsReady && wantStreaming) {
                val curNetGen = PanggungService.networkGeneration.get()
                val netChanged = curNetGen != lastNetGen
                if (netChanged) lastNetGen = curNetGen

                if (!snapshot.streaming || netChanged) {
                    if (netChanged && snapshot.streaming) {
                        statusBanner = "Network switched — reconnecting stream..."
                        NativeBridge.nativeStopStreaming()
                        delay(800L)
                    }
                    retryAttempt++
                    if (retryAttempt <= 20) {
                        val backoffSec = (1 shl (retryAttempt - 1).coerceAtMost(4)).coerceAtMost(16)
                        statusBanner = "Reconnecting ($retryAttempt/20) in ${backoffSec}s..."
                        delay(backoffSec * 1000L)
                        if (!wantStreaming) break
                        val ok = NativeBridge.nativeStartStreaming(rtmpUrl, streamKey, bitrateKbps.toInt())
                        delay(2000L)
                        refreshSnapshot()
                        if (ok && snapshot.streaming) {
                            statusBanner = "Stream reconnected LIVE!"
                            retryAttempt = 0
                        }
                    } else {
                        statusBanner = "Auto-reconnect paused after 20 retries"
                        wantStreaming = false
                    }
                } else {
                    retryAttempt = 0
                    delay(2000L)
                }
            }
        }

        val startLiveWithGuard: () -> Unit = {
            startFgs(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            val ok = NativeBridge.nativeStartStreaming(rtmpUrl, streamKey, bitrateKbps.toInt())
            wantStreaming = ok
            lastNetGen = PanggungService.networkGeneration.get()
            statusBanner = if (ok) "Streaming LIVE" else "Stream start failed"
            refreshSnapshot()
        }

        val toggleLiveAction = {
            if (snapshot.streaming || wantStreaming) {
                showConfirmStop = true
            } else {
                startLiveWithGuard()
            }
        }
        val toggleRecAction = {
            if (snapshot.recording) {
                showConfirmStop = true
            } else {
                toggleRecording(bitrateKbps.toInt())
                recPaused = false
            }
        }
        val toggleReplayAction = {
            if (snapshot.replayActive) {
                val ok = NativeBridge.nativeSaveReplayBuffer()
                statusBanner = if (ok) "Replay clip saved!" else "Replay save failed"
            } else {
                val dir = (getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir).absolutePath
                val ok = NativeBridge.nativeStartReplayBuffer(dir, 15, bitrateKbps.toInt())
                statusBanner = if (ok) "Replay Buffer active (15s)" else "Replay Buffer failed"
                refreshSnapshot()
            }
        }

        val setOrientationMode: (String) -> Unit = { mode ->
            orientationLock = mode
            applyOrientationLock(mode)
            val cur = SettingsPersistence.load(this@MainActivity)
            SettingsPersistence.save(this@MainActivity, cur.copy(orientationLock = mode))
            statusBanner = when (mode) {
                "PORTRAIT" -> "Orientation locked: Portrait"
                "LANDSCAPE" -> "Orientation locked: Landscape"
                else -> "Orientation: Auto-Rotate"
            }
        }
        val cycleOrientationLock: () -> Unit = {
            val next = when (orientationLock) {
                "AUTO" -> "PORTRAIT"
                "PORTRAIT" -> "LANDSCAPE"
                else -> "AUTO"
            }
            setOrientationMode(next)
        }

        val safeDeleteSource: (String) -> Unit = { name ->
            val target = snapshot.sources.firstOrNull { it.name == name }
            NativeBridge.nativeRemoveSource(name)
            if (selectedSourceName == name) selectedSourceName = null
            if (target?.id == "android_camera") {
                cameraCapture.stop()
            }
            transformVersion++
            statusBanner = "Removed $name"
            refreshSnapshot()
        }

        val rotateSource90: (String) -> Unit = { name ->
            try {
                val tj = org.json.JSONObject(NativeBridge.nativeGetSourceTransformJson(name))
                val bw = tj.optInt("base_w", snapshot.baseWidth.coerceAtLeast(1)).toFloat()
                val bh = tj.optInt("base_h", snapshot.baseHeight.coerceAtLeast(1)).toFloat()
                val cx = tj.optDouble("x", bw / 2.0).toFloat()
                val cy = tj.optDouble("y", bh / 2.0).toFloat()
                val sx = tj.optDouble("scale_x", 1.0).toFloat()
                val sy = tj.optDouble("scale_y", 1.0).toFloat()
                val curRot = tj.optDouble("rot", 0.0).toFloat()
                val nextRot = (((curRot.toInt() + 90) % 360) + 360) % 360
                NativeBridge.nativeSetSourceTransform(name, cx, cy, sx, sy, nextRot.toFloat())
                transformVersion++
                statusBanner = "Rotated $name -> ${nextRot}°"
                refreshSnapshot()
            } catch (_: Exception) {}
        }

        val callbacks = StudioCallbacks(
            onSwitchScene = { idx ->
                selectedSourceName = null
                transformVersion++
                NativeBridge.nativeSwitchScene(idx)
                refreshSnapshot()
            },
            onCreateScene = {
                var n = snapshot.scenes.size + 1
                var name = "Scene $n"
                while (snapshot.scenes.contains(name)) {
                    n++
                    name = "Scene $n"
                }
                val ok = NativeBridge.nativeCreateScene(name)
                if (ok) {
                    statusBanner = "Created $name"
                    refreshSnapshot()
                }
            },
            onSelectSource = { selectedSourceName = it },
            onLongPressSource = { contextMenuSource = it },
            onToggleSourceVisible = { src -> NativeBridge.nativeSetSourceVisible(src.name, !src.visible); refreshSnapshot() },
            onToggleSourceLock = { src -> NativeBridge.nativeSetSourceLocked(src.name, !src.locked); refreshSnapshot() },
            onMoveSourceOrder = { name, dir -> NativeBridge.nativeMoveSourceOrder(name, dir); refreshSnapshot() },
            onDeleteSource = safeDeleteSource,
            onOpenProperties = { propsTargetSource = it },
            onOpenFilters = { filterTargetSource = it },
            onOpenTransform = { transformTargetSource = it },
            onFitSource = { name -> NativeBridge.nativeFitSourceToScreen(name); transformVersion++; statusBanner = "Fit $name to screen"; refreshSnapshot() },
            onToggleMute = { src -> NativeBridge.nativeSetSourceMuted(src.name, !src.muted); refreshSnapshot() },
            onVolumeChange = { name, v -> NativeBridge.nativeSetSourceVolume(name, v); refreshSnapshot() },
            onOpenAudioAdv = { audioAdvTargetSource = it },
            onToggleRec = toggleRecAction,
            onToggleLive = toggleLiveAction,
            onToggleReplay = toggleReplayAction,
            onToggleStudioMode = { NativeBridge.nativeSetStudioMode(!snapshot.studioMode); refreshSnapshot() },
            onTransitionToProgram = { NativeBridge.nativeTransitionToProgram(); refreshSnapshot() },
            onSetTBar = { t -> NativeBridge.nativeSetStudioTBar(t) },
            onFadeToBlack = { NativeBridge.nativeFadeToBlack(); statusBanner = "Fade to Black triggered" },
            onOpenRemoteQr = { showRemoteQr = true },
            onOpenFullSettings = { showFullSettings = true },
            onCycleOrientationLock = cycleOrientationLock,
            onRotateSource = rotateSource90,
            onFlipSource = { name ->
                NativeBridge.nativeFlipSource(name, true)
                transformVersion++
                statusBanner = "Flipped $name"
                refreshSnapshot()
            },
            onOpenAddSource = { showAddSourceDialog = true },
            onOpenTabModal = { landscapeTabModal = it },
        )

        val validSelected = selectedSourceName?.takeIf { sel -> snapshot.sources.any { it.name == sel } }
        val activeSelectedSource = validSelected ?: snapshot.sources.lastOrNull { !it.id.contains("audio") }?.name

        PanggungTheme {
            ResponsiveStudioScaffold(
                obsReady = obsReady,
                obsVersion = obsVersion,
                snapshot = snapshot,
                selectedSource = activeSelectedSource,
                selectedTab = selectedTab,
                onSelectTab = { selectedTab = it },
                statusBanner = statusBanner,
                orientationLock = orientationLock,
                transformVersion = transformVersion,
                topBanners = {
                    if (crashBannerVisible) {
                        CrashRecoveryBanner(
                            onViewLog = { showLogs = true },
                            onDismiss = {
                                crashBannerVisible = false
                                try {
                                    File(filesDir, "obs-config/last_crash.log").delete()
                                    File(filesDir, "obs-config/crash.log").delete()
                                } catch (_: Exception) {}
                            },
                        )
                    }
                    if (thermalState.throttled) {
                        ThermalGovernorBanner(
                            tempC = thermalState.tempC,
                            headroom = thermalState.headroom,
                            activeBitrateKbps = thermalState.effectiveBitrateKbps,
                            onDropTo30Fps = {
                                if (!snapshot.recording && !snapshot.streaming) {
                                    NativeBridge.nativeResetVideo(snapshot.baseWidth, snapshot.baseHeight, 30)
                                    statusBanner = "Thermal drop: 30 FPS applied"
                                } else {
                                    NativeBridge.nativeUpdateLiveBitrate(2000)
                                    statusBanner = "Thermal throttle: 2000 kbps live"
                                }
                            },
                        )
                    }
                },
                callbacks = callbacks,
                tabContent = { tab ->
                    when (tab) {
                        0 -> SourcesPanel(
                            selectedSource = selectedSourceName,
                            onSelectSource = { selectedSourceName = it },
                            onLongPressSource = { contextMenuSource = it },
                            onOpenFilters = { filterTargetSource = it },
                            onOpenProperties = { propsTargetSource = it },
                            onOpenTransform = { transformTargetSource = it },
                        )
                        1 -> MixerPanel(onOpenAdvanced = { audioAdvTargetSource = it })
                        2 -> StudioTransitionsPanel()
                        3 -> OutputPanel(
                            rtmpUrl, { rtmpUrl = it },
                            streamKey, { streamKey = it },
                            bitrateKbps, { bitrateKbps = it },
                            recPaused, { recPaused = it },
                        )
                        4 -> SettingsPanel(
                            dualCanvasOn = dualCanvasOn,
                            keepScreenOn = keepScreenOn,
                            orientationLock = orientationLock,
                            bitrateKbps = bitrateKbps.toInt(),
                            onSetOrientationLock = setOrientationMode,
                            onToggleKeepScreenOn = {
                                keepScreenOn = it
                                ThermalGovernor.setKeepScreenOn(this@MainActivity, it)
                            },
                            onCheckUpdates = {
                                coroutineScope.launch {
                                    val curVer = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "0.2.0-alpha.1" } catch (_: Exception) { "0.2.0-alpha.1" }
                                    statusBanner = ThermalGovernor.checkLatestRelease(curVer)
                                }
                            },
                            onToggleDualCanvas = {
                                dualCanvasOn = !dualCanvasOn
                                NativeBridge.nativeToggleDualCanvas(dualCanvasOn)
                                statusBanner = if (dualCanvasOn) "Dual Canvas (720x1280 Vertical) ON" else "Dual Canvas OFF"
                            },
                            onOpenFullSettings = { showFullSettings = true },
                            onOpenBrowserDock = { showBrowserDock = true },
                            onOpenLogs = { showLogs = true },
                            onOpenMultiview = { showMultiview = true },
                            onOpenWizard = { showWizard = true },
                            onOpenRemoteQr = { showRemoteQr = true },
                            onOpenAbout = { showAbout = true },
                        )
                    }
                },
            )

            if (contextMenuSource != null) {
                val cs = contextMenuSource!!
                SourceContextMenuDialog(
                    sourceName = cs.name,
                    locked = cs.locked,
                    visible = cs.visible,
                    onMoveUp = { NativeBridge.nativeMoveSourceOrder(cs.name, 0); refreshSnapshot() },
                    onMoveDown = { NativeBridge.nativeMoveSourceOrder(cs.name, 1); refreshSnapshot() },
                    onToggleLock = { NativeBridge.nativeSetSourceLocked(cs.name, !cs.locked); refreshSnapshot() },
                    onToggleVisible = { NativeBridge.nativeSetSourceVisible(cs.name, !cs.visible); refreshSnapshot() },
                    onFitToScreen = { NativeBridge.nativeFitSourceToScreen(cs.name); transformVersion++; refreshSnapshot() },
                    onCenter = { NativeBridge.nativeCenterSource(cs.name); transformVersion++; refreshSnapshot() },
                    onFlipH = { NativeBridge.nativeFlipSource(cs.name, true); transformVersion++; refreshSnapshot() },
                    onFlipV = { NativeBridge.nativeFlipSource(cs.name, false); transformVersion++; refreshSnapshot() },
                    onResetTransform = { NativeBridge.nativeResetSourceTransform(cs.name); transformVersion++; refreshSnapshot() },
                    onOpenProperties = { propsTargetSource = cs.name },
                    onOpenFilters = { filterTargetSource = cs.name },
                    onOpenTransform = { transformTargetSource = cs.name },
                    onDelete = { safeDeleteSource(cs.name) },
                    onDismiss = { contextMenuSource = null },
                )
            }
            if (showAddSourceDialog) {
                AlertDialog(
                    onDismissRequest = { showAddSourceDialog = false },
                    containerColor = PanggungColors.DockSurface,
                    title = { Text("Add Source (10 Types)", color = PanggungColors.TextPrimary, fontWeight = FontWeight.Bold) },
                    text = {
                        Column(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                BroadcastButton("+ Cam Front", { showAddSourceDialog = false; addCamera(front = true) }, tone = BroadcastTone.Primary, modifier = Modifier.weight(1f))
                                BroadcastButton("+ Cam Back", { showAddSourceDialog = false; addCamera(front = false) }, tone = BroadcastTone.Primary, modifier = Modifier.weight(1f))
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                BroadcastButton("+ Screen", { showAddSourceDialog = false; requestScreenCapture() }, tone = BroadcastTone.Primary, modifier = Modifier.weight(1f))
                                BroadcastButton("+ Browser", { showAddSourceDialog = false; addBrowserOverlay() }, modifier = Modifier.weight(1f))
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                BroadcastButton("+ Image", { showAddSourceDialog = false; pendingMediaKind = "image_source"; mediaPickerLauncher.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f))
                                BroadcastButton("+ Media", { showAddSourceDialog = false; pendingMediaKind = "ffmpeg_source"; mediaPickerLauncher.launch(arrayOf("video/*", "audio/*")) }, modifier = Modifier.weight(1f))
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                BroadcastButton(
                                    "+ Text",
                                    {
                                        showAddSourceDialog = false
                                        val n = "Text ${snapshot.sources.size + 1}"
                                        NativeBridge.nativeAddSource("text_ft2_source_v2", n, """{"text":"Panggung Studio Live"}""")
                                        selectedSourceName = n
                                        refreshSnapshot()
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                BroadcastButton(
                                    "+ Rich Text",
                                    {
                                        showAddSourceDialog = false
                                        val n = "RichText ${snapshot.sources.size + 1}"
                                        NativeBridge.nativeAddSource("text_ft2_source_v2", n, """{"text":"PANGGUNG LIVE","color1":4294967295,"color2":4289132564,"outline":true}""")
                                        selectedSourceName = n
                                        refreshSnapshot()
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                BroadcastButton(
                                    "+ Stats HUD",
                                    {
                                        showAddSourceDialog = false
                                        val n = "Stats HUD"
                                        NativeBridge.nativeAddSource("text_ft2_source_v2", n, """{"text":"${String.format(Locale.US, "%.0f FPS", snapshot.fps)} | LIVE"}""")
                                        selectedSourceName = n
                                        refreshSnapshot()
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                BroadcastButton(
                                    "+ Color",
                                    {
                                        showAddSourceDialog = false
                                        val n = "Color ${snapshot.sources.size + 1}"
                                        NativeBridge.nativeAddSource("color_source", n, """{"color":4279701773,"width":1280,"height":720}""")
                                        selectedSourceName = n
                                        refreshSnapshot()
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showAddSourceDialog = false }) {
                            Text("Close", color = PanggungColors.TealActive)
                        }
                    },
                )
            }
            landscapeTabModal?.let { tabIdx ->
                val modalTitle = when (tabIdx) {
                    1 -> "Audio Mixer"
                    2 -> "Studio & Transitions"
                    3 -> "Output, RTMP & Replay"
                    else -> "Studio Tools & Canvas"
                }
                AlertDialog(
                    onDismissRequest = { landscapeTabModal = null },
                    containerColor = PanggungColors.DockSurface,
                    title = { Text(modalTitle, color = PanggungColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
                    text = {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp)
                                .then(if (tabIdx == 4) Modifier else Modifier.verticalScroll(rememberScrollState())),
                        ) {
                            when (tabIdx) {
                                1 -> MixerPanel(onOpenAdvanced = { landscapeTabModal = null; audioAdvTargetSource = it })
                                2 -> StudioTransitionsPanel()
                                3 -> OutputPanel(
                                    rtmpUrl = rtmpUrl,
                                    onRtmpUrlChange = { rtmpUrl = it },
                                    streamKey = streamKey,
                                    onStreamKeyChange = { streamKey = it },
                                    bitrateKbps = bitrateKbps,
                                    onBitrateChange = { bitrateKbps = it },
                                    recPaused = recPaused,
                                    onRecPausedChange = { recPaused = it },
                                )
                                else -> SettingsPanel(
                                    dualCanvasOn = dualCanvasOn,
                                    keepScreenOn = keepScreenOn,
                                    orientationLock = orientationLock,
                                    bitrateKbps = bitrateKbps.toInt(),
                                    onSetOrientationLock = setOrientationMode,
                                    onToggleKeepScreenOn = { enabled ->
                                        keepScreenOn = enabled
                                        ThermalGovernor.setKeepScreenOn(this@MainActivity, enabled)
                                    },
                                    onCheckUpdates = {
                                        val curVer = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "0.2.0-alpha.1" } catch (_: Exception) { "0.2.0-alpha.1" }
                                        coroutineScope.launch { statusBanner = ThermalGovernor.checkLatestRelease(curVer) }
                                    },
                                    onToggleDualCanvas = {
                                        dualCanvasOn = !dualCanvasOn
                                        NativeBridge.nativeToggleDualCanvas(dualCanvasOn)
                                        statusBanner = if (dualCanvasOn) "Dual Canvas (16:9 + 9:16) ON" else "Dual Canvas OFF"
                                    },
                                    onOpenFullSettings = { landscapeTabModal = null; showFullSettings = true },
                                    onOpenBrowserDock = { landscapeTabModal = null; showBrowserDock = true },
                                    onOpenLogs = { landscapeTabModal = null; showLogs = true },
                                    onOpenMultiview = { landscapeTabModal = null; showMultiview = true },
                                    onOpenWizard = { landscapeTabModal = null; showWizard = true },
                                    onOpenRemoteQr = { landscapeTabModal = null; showRemoteQr = true },
                                    onOpenAbout = { landscapeTabModal = null; showAbout = true },
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { landscapeTabModal = null }) {
                            Text("Close", color = PanggungColors.TealActive)
                        }
                    },
                )
            }
            if (showConfirmStop) {
                ConfirmStopBroadcastDialog(
                    isStreaming = snapshot.streaming || wantStreaming,
                    isRecording = snapshot.recording,
                    onConfirm = {
                        wantStreaming = false
                        if (snapshot.streaming) NativeBridge.nativeStopStreaming()
                        if (snapshot.recording) NativeBridge.nativeStopRecording()
                        recPaused = false
                        statusBanner = "Broadcast stopped cleanly"
                        showConfirmStop = false
                        refreshSnapshot()
                    },
                    onDismiss = { showConfirmStop = false },
                )
            }
            if (showRemoteQr) {
                RemoteQrDialog(
                    onDismiss = { showRemoteQr = false },
                    onStatusChanged = { statusBanner = it },
                )
            }

            if (filterTargetSource != null) {
                FiltersDialog(sourceName = filterTargetSource!!, onDismiss = { filterTargetSource = null })
            }
            if (propsTargetSource != null) {
                PropertiesDialog(sourceName = propsTargetSource!!, onDismiss = { propsTargetSource = null })
            }
            if (transformTargetSource != null) {
                TransformDialog(sourceName = transformTargetSource!!, onDismiss = { transformTargetSource = null; transformVersion++ })
            }
            if (audioAdvTargetSource != null) {
                AdvancedAudioDialog(sourceName = audioAdvTargetSource!!, onDismiss = { audioAdvTargetSource = null })
            }
            if (showMultiview) {
                MultiviewDialog(
                    scenes = snapshot.scenes,
                    activeIdx = snapshot.activeSceneIdx,
                    previewIdx = snapshot.previewSceneIdx,
                    onSelectScene = { NativeBridge.nativeSwitchScene(it); refreshSnapshot() },
                    onDismiss = { showMultiview = false },
                )
            }
            if (showWizard) {
                AutoConfigWizardDialog(
                    onApplyPreset = { w, h, fps, enc, br ->
                        NativeBridge.nativeResetVideo(w, h, fps)
                        NativeBridge.nativeSetVideoEncoder(enc)
                        bitrateKbps = br
                        statusBanner = "Wizard applied: ${w}x${h}@$fps ($enc)"
                        refreshSnapshot()
                    },
                    onDismiss = { showWizard = false },
                )
            }
            if (showAbout) {
                AboutDialog(obsVersion = obsVersion, onDismiss = { showAbout = false })
            }
            if (showFullSettings) {
                FullObsSettingsDialog(
                    currentWidth = snapshot.baseWidth,
                    currentHeight = snapshot.baseHeight,
                    onApplyVideoEx = { w, h, fps, fmt, sp, rng, sc ->
                        val ok = NativeBridge.nativeResetVideoEx(w, h, fps, fmt, sp, rng, sc)
                        statusBanner = if (ok) "Video pipeline updated (${w}x${h}@$fps)" else "Stop outputs first"
                        refreshSnapshot()
                    },
                    onStatus = { statusBanner = it },
                    onDismiss = { showFullSettings = false },
                )
            }
            if (showBrowserDock) {
                BrowserDockDialog(initialUrl = "https://studio.youtube.com", onDismiss = { showBrowserDock = false })
            }
            if (showLogs) {
                LogViewerDialog(
                    crashLog = NativeBridge.nativeGetLastCrashLog(),
                    statusLog = "Panggung Engine v0.2.0-alpha.1 | ${snapshot.baseWidth}x${snapshot.baseHeight} | FPS=${snapshot.fps}\n$statusBanner",
                    onDismiss = { showLogs = false },
                )
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun SourcesPanel(
        selectedSource: String?,
        onSelectSource: (String) -> Unit,
        onLongPressSource: (SourceUi) -> Unit,
        onOpenFilters: (String) -> Unit,
        onOpenProperties: (String) -> Unit,
        onOpenTransform: (String) -> Unit,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                BroadcastButton("+ Cam Back", { addCamera(front = false) }, tone = BroadcastTone.Primary, compact = true)
                BroadcastButton("+ Cam Front", { addCamera(front = true) }, tone = BroadcastTone.Primary, compact = true)
                BroadcastButton("+ Screen", { requestScreenCapture() }, tone = BroadcastTone.ActiveOutline, compact = true)
                BroadcastButton("+ Browser", { addBrowserOverlay() }, compact = true)
                BroadcastButton("+ Image", { pendingMediaKind = "image_source"; mediaPickerLauncher.launch(arrayOf("image/*")) }, compact = true)
                BroadcastButton("+ Media", { pendingMediaKind = "ffmpeg_source"; mediaPickerLauncher.launch(arrayOf("video/*", "audio/*")) }, compact = true)
                BroadcastButton("+ Text", {
                    val name = "Text ${snapshot.sources.size + 1}"
                    NativeBridge.nativeAddSource(
                        "text_ft2_source_v2", name,
                        """{"text":"PANGGUNG LIVE","font":{"face":"Roboto","size":72,"flags":1},"color1":4294967295,"color2":4294967295,"outline":true,"outline_size":3}""",
                    )
                    refreshSnapshot()
                }, compact = true)
                BroadcastButton("+ Rich Text", {
                    val name = "Rich Text ${snapshot.sources.size + 1}"
                    NativeBridge.nativeAddSource("panggung_text_android", name, "")
                    LiveOverlaysEngine.renderRichGradientText(name, "PANGGUNG LIVE")
                    refreshSnapshot()
                }, compact = true)
                BroadcastButton("+ Stats HUD", {
                    val name = "Device Stats"
                    if (snapshot.sources.none { it.name == name }) {
                        NativeBridge.nativeAddSource("panggung_device_stats", name, "")
                    }
                    val batt = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val lvl = batt?.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) ?: 100
                    val tc = (batt?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 350) ?: 350) / 10.0
                    LiveOverlaysEngine.renderDeviceStats(name, snapshot.fps, tc, lvl, 0.2f)
                    refreshSnapshot()
                }, compact = true)
                BroadcastButton("+ Color", {
                    val name = "Color ${snapshot.sources.size + 1}"
                    NativeBridge.nativeAddSource("color_source", name, """{"color":4294923520,"width":400,"height":240}""")
                    refreshSnapshot()
                }, compact = true)
            }

            if (snapshot.sources.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(PanggungRadius.Card)
                        .background(PanggungColors.DockSurface)
                        .border(1.dp, PanggungColors.Border, PanggungRadius.Card)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("No sources in this scene. Tap + Cam Back or + Screen above.", color = PanggungColors.TextMuted, fontSize = 12.sp)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    items(snapshot.sources) { src ->
                        val isSelected = selectedSource == src.name
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(PanggungRadius.Button)
                                .background(if (isSelected) PanggungColors.CardSelected else PanggungColors.CardElevated)
                                .border(1.dp, if (isSelected) PanggungColors.TealActive else PanggungColors.Border, PanggungRadius.Button)
                                .combinedClickable(
                                    onClick = { onSelectSource(src.name) },
                                    onLongClick = { onLongPressSource(src) },
                                )
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(src.name, color = PanggungColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(src.id, color = PanggungColors.TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (src.locked) "LOCK" else "OPEN",
                                    color = if (src.locked) PanggungColors.WarnBright else PanggungColors.TextDim,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.clickable {
                                        NativeBridge.nativeSetSourceLocked(src.name, !src.locked)
                                        refreshSnapshot()
                                    }.padding(3.dp),
                                )
                                Text(
                                    text = if (src.visible) "SHOW" else "HIDE",
                                    color = if (src.visible) PanggungColors.LiveBright else PanggungColors.TextDim,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.clickable {
                                        NativeBridge.nativeSetSourceVisible(src.name, !src.visible)
                                        refreshSnapshot()
                                    }.padding(3.dp),
                                )
                                Text(
                                    text = "MENU",
                                    color = PanggungColors.TealActive,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable { onLongPressSource(src) }.padding(3.dp),
                                )
                                Text(
                                    text = "DEL",
                                    color = PanggungColors.RecBright,
                                    fontSize = 10.sp,
                                    modifier = Modifier.clickable {
                                        NativeBridge.nativeRemoveSource(src.name)
                                        if (src.id == "android_camera") cameraCapture.stop()
                                        refreshSnapshot()
                                    }.padding(3.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun FiltersDialog(sourceName: String, onDismiss: () -> Unit) {
        var filters by remember { mutableStateOf(loadFilters(sourceName)) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Filters: $sourceName", fontSize = 15.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "chroma_key_filter_v2" to "Chroma Key",
                            "color_filter_v2" to "Color",
                            "sharpness_filter_v2" to "Sharpen",
                            "panggung_composite_blur" to "Blur",
                            "panggung_retro_effects" to "Retro CRT",
                            "panggung_stroke_glow_shadow" to "Glow",
                            "panggung_3d_effect" to "3D Tilt",
                            "panggung_ml_background" to "Luma Key",
                            "noise_suppress_filter_v2" to "RNNoise",
                            "panggung_dsp_pack" to "Reverb/DeEss",
                            "compressor_filter" to "Compressor",
                            "gain_filter" to "Gain",
                        ).forEach { (fid, label) ->
                            OutlinedButton(
                                onClick = {
                                    NativeBridge.nativeAddFilter(sourceName, fid, label, "")
                                    filters = loadFilters(sourceName)
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            ) { Text("+ $label", fontSize = 11.sp) }
                        }
                    }
                    filters.forEach { f ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(Color(0xFF1F2433)).padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(f.name, color = Color.White, fontSize = 12.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    if (f.enabled) "ON" else "OFF",
                                    color = if (f.enabled) Color(0xFF4ADE80) else Color.Gray,
                                    fontSize = 11.sp,
                                    modifier = Modifier.clickable {
                                        NativeBridge.nativeSetFilterEnabled(sourceName, f.name, !f.enabled)
                                        filters = loadFilters(sourceName)
                                    },
                                )
                                Text(
                                    "DEL",
                                    color = Color(0xFFF87171),
                                    fontSize = 11.sp,
                                    modifier = Modifier.clickable {
                                        NativeBridge.nativeRemoveFilter(sourceName, f.name)
                                        filters = loadFilters(sourceName)
                                    },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        )
    }

    @Composable
    private fun MixerPanel(onOpenAdvanced: (String) -> Unit) {
        val audioSources = snapshot.sources.filter { it.id.contains("audio") || it.id == "ffmpeg_source" || it.id == "android_webview" }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(audioSources) { src ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(PanggungRadius.Card)
                        .background(PanggungColors.DockSurface)
                        .border(1.dp, PanggungColors.Border, PanggungRadius.Card)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(src.name, color = PanggungColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                String.format(Locale.US, "Mag: %.1f dB | Peak: %.1f dB", src.magnitudeDb, src.peakDb),
                                color = PanggungColors.TextDim,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            BroadcastButton("ADV", { onOpenAdvanced(src.name) }, compact = true)
                            BroadcastButton(
                                text = if (src.muted) "MUTED" else "${(src.volume * 100).toInt()}%",
                                onClick = { NativeBridge.nativeSetSourceMuted(src.name, !src.muted); refreshSnapshot() },
                                tone = if (src.muted) BroadcastTone.Danger else BroadcastTone.ActiveOutline,
                                compact = true,
                            )
                        }
                    }
                    StereoVuMeterBar(
                        magnitudeDb = src.magnitudeDb,
                        peakDb = src.peakDb,
                        muted = src.muted,
                        barThickness = 6.dp,
                    )
                    Slider(
                        value = src.volume.coerceIn(0f, 1.5f),
                        valueRange = 0f..1.5f,
                        onValueChange = { v -> NativeBridge.nativeSetSourceVolume(src.name, v); refreshSnapshot() },
                        colors = SliderDefaults.colors(
                            thumbColor = PanggungColors.TealActive,
                            activeTrackColor = PanggungColors.TealPrimary,
                            inactiveTrackColor = PanggungColors.Border,
                        ),
                    )
                }
            }
        }
    }

    @Composable
    private fun StudioTransitionsPanel() {
        var durMs by remember { mutableFloatStateOf(snapshot.transitionMs.toFloat()) }
        var tbar by remember { mutableFloatStateOf(0f) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BroadcastToggleRow(
                label = "Studio Mode (Preview → Program)",
                subtitle = "Edit scenes privately before transitioning live",
                checked = snapshot.studioMode,
                onCheckedChange = { NativeBridge.nativeSetStudioMode(it); refreshSnapshot() },
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BroadcastButton(
                    text = "TRANSITION TO PROGRAM",
                    onClick = { NativeBridge.nativeTransitionToProgram(); tbar = 0f; refreshSnapshot() },
                    tone = BroadcastTone.Primary,
                    modifier = Modifier.weight(1.3f),
                )
                BroadcastButton(
                    text = "FADE TO BLACK",
                    onClick = { NativeBridge.nativeFadeToBlack(); statusBanner = "Fade to Black triggered" },
                    tone = BroadcastTone.Warning,
                    modifier = Modifier.weight(0.9f),
                )
            }
            if (snapshot.studioMode) {
                LabeledSlider(
                    label = "Manual T-Bar",
                    valueText = "${(tbar * 100).toInt()}%",
                    value = tbar,
                    valueRange = 0f..1f,
                    onValueChange = {
                        tbar = it
                        NativeBridge.nativeSetStudioTBar(it)
                    },
                )
            }
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(
                    "cut_transition" to "Cut",
                    "fade_transition" to "Fade",
                    "swipe_transition" to "Swipe",
                    "slide_transition" to "Slide",
                    "fade_to_color_transition" to "Fade Color",
                    "wipe_transition" to "Luma Wipe",
                ).forEach { (tid, label) ->
                    BroadcastButton(
                        text = label,
                        onClick = { NativeBridge.nativeSetTransition(tid, durMs.toInt()); refreshSnapshot() },
                        tone = if (snapshot.transitionId == tid) BroadcastTone.Primary else BroadcastTone.Neutral,
                        compact = true,
                    )
                }
            }
            LabeledSlider(
                label = "Transition Duration",
                valueText = "${durMs.toInt()} ms",
                value = durMs,
                valueRange = 50f..2000f,
                onValueChange = {
                    durMs = it
                    NativeBridge.nativeSetTransition(snapshot.transitionId, it.toInt())
                },
            )
            BroadcastButton(
                text = if (projector != null) "Close External HDMI/USB-C Projector" else "Open External Display Projector",
                onClick = { toggleExternalProjector() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    @Composable
    private fun OutputPanel(
        rtmpUrl: String, onRtmpUrlChange: (String) -> Unit,
        streamKey: String, onStreamKeyChange: (String) -> Unit,
        bitrateKbps: Float, onBitrateChange: (Float) -> Unit,
        recPaused: Boolean, onRecPausedChange: (Boolean) -> Unit,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BroadcastButton(
                    text = if (snapshot.recording) "STOP REC" else "START REC",
                    onClick = { toggleRecording(bitrateKbps.toInt()); onRecPausedChange(false) },
                    tone = if (snapshot.recording) BroadcastTone.Danger else BroadcastTone.Primary,
                    modifier = Modifier.weight(1f),
                )
                if (snapshot.recording) {
                    BroadcastButton(
                        text = if (recPaused) "RESUME" else "PAUSE",
                        onClick = {
                            val next = !recPaused
                            NativeBridge.nativePauseRecording(next)
                            onRecPausedChange(next)
                            statusBanner = if (next) "Recording paused" else "Recording resumed"
                        },
                        tone = BroadcastTone.Warning,
                    )
                }
                BroadcastButton(
                    text = if (snapshot.streaming || wantStreaming) "STOP LIVE" else "GO LIVE",
                    onClick = {
                        if (snapshot.streaming || wantStreaming) {
                            wantStreaming = false
                            NativeBridge.nativeStopStreaming()
                            statusBanner = "Stream stopped"
                            refreshSnapshot()
                        } else {
                            startFgs(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                            val ok = NativeBridge.nativeStartStreaming(rtmpUrl, streamKey, bitrateKbps.toInt())
                            wantStreaming = ok
                            lastNetGen = PanggungService.networkGeneration.get()
                            statusBanner = if (ok) "Streaming LIVE" else "Stream start failed"
                            refreshSnapshot()
                        }
                    },
                    tone = if (snapshot.streaming || wantStreaming) BroadcastTone.Danger else BroadcastTone.Live,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BroadcastButton(
                    text = if (snapshot.replayActive) "Stop Replay" else "Start Replay (15s)",
                    onClick = {
                        if (snapshot.replayActive) {
                            NativeBridge.nativeStopReplayBuffer()
                            statusBanner = "Replay Buffer stopped"
                        } else {
                            val dir = (getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir).absolutePath
                            val ok = NativeBridge.nativeStartReplayBuffer(dir, 15, bitrateKbps.toInt())
                            statusBanner = if (ok) "Replay Buffer active (15s)" else "Replay Buffer failed"
                        }
                        refreshSnapshot()
                    },
                    modifier = Modifier.weight(1f),
                    compact = true,
                )
                if (snapshot.replayActive) {
                    BroadcastButton(
                        text = "Save Replay",
                        onClick = {
                            val ok = NativeBridge.nativeSaveReplayBuffer()
                            statusBanner = if (ok) "Replay clip saved!" else "Replay save failed"
                        },
                        tone = BroadcastTone.Primary,
                        modifier = Modifier.weight(1f),
                        compact = true,
                    )
                }
            }

            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(
                    "android_mediacodec_h264" to "HW H.264",
                    "android_mediacodec_hevc" to "HW HEVC",
                    "android_mediacodec_av1" to "HW AV1",
                    "obs_x264" to "SW x264",
                ).forEach { (eid, label) ->
                    BroadcastButton(
                        text = label,
                        onClick = { NativeBridge.nativeSetVideoEncoder(eid); refreshSnapshot() },
                        tone = if (snapshot.videoEncoderId == eid) BroadcastTone.Primary else BroadcastTone.Neutral,
                        compact = true,
                    )
                }
            }

            LabeledSlider(
                label = "Video Bitrate",
                valueText = "${bitrateKbps.toInt()} kbps",
                value = bitrateKbps,
                valueRange = 1000f..15000f,
                onValueChange = {
                    onBitrateChange(it)
                    if (snapshot.recording || snapshot.streaming) {
                        NativeBridge.nativeUpdateLiveBitrate(it.toInt())
                    }
                },
            )
            OutlinedTextField(value = rtmpUrl, onValueChange = onRtmpUrlChange, label = { Text("RTMP / SRT / WHIP URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = streamKey,
                onValueChange = { onStreamKeyChange(it); StreamKeyStore.saveStreamKey(this@MainActivity, it) },
                label = { Text("Stream Key (Android Keystore AES-256)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (lastRecordPath.isNotEmpty()) Text("Saved: $lastRecordPath", color = PanggungColors.TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }

    @Composable
    private fun SettingsPanel(
        dualCanvasOn: Boolean,
        keepScreenOn: Boolean,
        orientationLock: String,
        bitrateKbps: Int,
        onSetOrientationLock: (String) -> Unit,
        onToggleKeepScreenOn: (Boolean) -> Unit,
        onCheckUpdates: () -> Unit,
        onToggleDualCanvas: () -> Unit,
        onOpenFullSettings: () -> Unit,
        onOpenBrowserDock: () -> Unit,
        onOpenLogs: () -> Unit,
        onOpenMultiview: () -> Unit,
        onOpenWizard: () -> Unit,
        onOpenRemoteQr: () -> Unit,
        onOpenAbout: () -> Unit,
    ) {
        val deviceStats = remember(snapshot.totalFrames) { readDeviceStats() }
        val storageEst = remember(bitrateKbps) { StorageAndExportHelper.estimateRemainingRecording(this@MainActivity, bitrateKbps) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("Canvas: ${snapshot.baseWidth}x${snapshot.baseHeight}", color = PanggungColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf(
                        Triple(1280, 720, "720p30" to 30),
                        Triple(1920, 1080, "1080p30" to 30),
                        Triple(1920, 1080, "1080p60" to 60),
                        Triple(720, 1280, "Vertical 720p" to 30),
                    ).forEach { (w, h, lblFps) ->
                        val (label, targetFps) = lblFps
                        BroadcastButton(label, {
                            val ok = NativeBridge.nativeResetVideo(w, h, targetFps)
                            if (ok) {
                                val cur = SettingsPersistence.load(this@MainActivity)
                                SettingsPersistence.save(this@MainActivity, cur.copy(baseWidth = w, baseHeight = h, fps = targetFps))
                            }
                            statusBanner = if (ok) "Video reset to ${w}x${h}@$targetFps (saved)" else "Stop output before changing resolution"
                            refreshSnapshot()
                        }, compact = true)
                    }
                }
            }
            item {
                Text("Screen Orientation Lock", color = PanggungColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("AUTO" to "Auto", "PORTRAIT" to "Lock Portrait", "LANDSCAPE" to "Lock Landscape").forEach { (mode, lbl) ->
                        BroadcastButton(
                            text = lbl,
                            onClick = { onSetOrientationLock(mode) },
                            tone = if (orientationLock == mode) BroadcastTone.Primary else BroadcastTone.Neutral,
                            modifier = Modifier.weight(1f),
                            compact = true,
                        )
                    }
                }
            }
            item {
                BroadcastToggleRow(
                    label = "Floating Studio Hub Popup",
                    subtitle = "Show draggable REC/MIC/SCENE control bubble over games",
                    checked = floatingHubEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled && !Settings.canDrawOverlays(this@MainActivity)) {
                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                            statusBanner = "Grant 'Appear on top' permission for Floating Hub"
                        }
                        floatingHubEnabled = enabled
                    },
                )
            }
            item {
                BroadcastToggleRow(
                    label = "Keep Screen On",
                    subtitle = "Prevent display sleep during live broadcast",
                    checked = keepScreenOn,
                    onCheckedChange = onToggleKeepScreenOn,
                )
            }
            item {
                DockPanel(title = "Live Broadcast Telemetry") {
                    Text(
                        "FPS: ${"%.1f".format(snapshot.fps)} | Render: ${"%.2f".format(snapshot.frameTimeMs)} ms | Skipped: ${snapshot.laggedFrames}/${snapshot.totalFrames}",
                        color = PanggungColors.TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    )
                    Text(deviceStats, color = PanggungColors.TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text(storageEst, color = PanggungColors.LiveBright, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text(ShizukuHelper.getStatusSummary(this@MainActivity), color = PanggungColors.TextSecondary, fontSize = 11.sp)
                }
            }
            item {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BroadcastButton("All Settings (13 Pages)", onOpenFullSettings, tone = BroadcastTone.Primary, compact = true)
                    BroadcastButton("Remote & QR", onOpenRemoteQr, tone = BroadcastTone.ActiveOutline, compact = true)
                    BroadcastButton("Connect Shizuku", {
                        ShizukuHelper.bindUserService(this@MainActivity) { msg ->
                            runOnUiThread { statusBanner = msg }
                        }
                    }, compact = true)
                    BroadcastButton(if (ShizukuHelper.isCapturing()) "Stop Shizuku" else "Shizuku Cap", {
                        if (ShizukuHelper.isCapturing()) {
                            ShizukuHelper.stopDialoglessCapture()
                            statusBanner = "Shizuku dialog-less capture stopped"
                        } else {
                            if (snapshot.sources.none { it.id == "android_screen" }) {
                                NativeBridge.nativeAddSource("android_screen", "Shizuku Screen", "")
                            }
                            val ok = ShizukuHelper.startDialoglessCapture()
                            statusBanner = if (ok) "Shizuku Dialog-less Screen Capture ACTIVE!" else "Connect Shizuku first"
                            refreshSnapshot()
                        }
                    }, compact = true)
                }
            }
            item {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BroadcastButton("Save Col", {
                        val f = File(filesDir, "scene_collection.json")
                        val ok = NativeBridge.nativeSaveSceneCollection(f.absolutePath)
                        statusBanner = if (ok) "Saved ${f.name}" else "Save failed"
                    }, compact = true)
                    BroadcastButton("Load Col", {
                        val f = File(filesDir, "scene_collection.json")
                        val ok = NativeBridge.nativeLoadSceneCollection(f.absolutePath)
                        statusBanner = if (ok) "Loaded ${f.name}" else "No saved collection"
                        refreshSnapshot()
                    }, compact = true)
                    BroadcastButton("Multiview", onOpenMultiview, compact = true)
                    BroadcastButton("Browser Dock", onOpenBrowserDock, compact = true)
                    BroadcastButton("Logs", onOpenLogs, compact = true)
                    BroadcastButton("Auto-Config", onOpenWizard, compact = true)
                    BroadcastButton(if (dualCanvasOn) "Dual: ON" else "Dual Canvas", onToggleDualCanvas, compact = true)
                    BroadcastButton("Check Update", onCheckUpdates, compact = true)
                    BroadcastButton("Export .zip", {
                        val zip = File(filesDir, "panggung_portable.zip")
                        val ok = StorageAndExportHelper.exportPortableZip(filesDir, zip)
                        statusBanner = if (ok) "Exported ${zip.name}" else "Export failed"
                    }, compact = true)
                    BroadcastButton("PiP", {
                        if (Build.VERSION.SDK_INT >= 26) {
                            enterPictureInPictureMode(
                                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
                            )
                        }
                    }, compact = true)
                    BroadcastButton("About", onOpenAbout, compact = true)
                }
            }
        }
    }

    private fun loadFilters(sourceName: String): List<FilterUi> {
        return try {
            val arr = JSONArray(NativeBridge.nativeListFiltersJson(sourceName))
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(FilterUi(o.optString("name"), o.optString("id"), o.optBoolean("enabled", true)))
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun addBrowserOverlay() {
        if (snapshot.sources.none { it.id == "android_webview" }) {
            NativeBridge.nativeAddSource("android_webview", "Browser Overlay", "")
        }
        val demoHtml = """
            <html><body style="margin:0;background:transparent;font-family:sans-serif;display:flex;align-items:flex-end;padding:24px;">
            <div style="background:rgba(15,23,42,0.85);border-left:6px solid #38bdf8;color:#fff;padding:14px 22px;border-radius:8px;font-size:28px;font-weight:700;">
                Panggung Browser Overlay • Live Ready
            </div></body></html>
        """.trimIndent()
        webViewCapture.start(demoHtml, 1280, 720, isHtml = true)
        statusBanner = "Browser Source active (WebView + window.obsstudio)"
        refreshSnapshot()
    }

    private fun addCamera(front: Boolean, pipIfScreenActive: Boolean = false) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionsIfNeeded(); return
        }
        startFgs(ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        currentCameraFront = front
        val name = if (front) "Front Camera" else "Back Camera"
        // Always read live native snapshot so backgrounded MainActivity never sees stale sources
        var existing: String? = null
        var hasScreen = false
        var baseW = snapshot.baseWidth.coerceAtLeast(1)
        var baseH = snapshot.baseHeight.coerceAtLeast(1)
        try {
            val liveSnap = org.json.JSONObject(NativeBridge.nativeGetSnapshotJson())
            baseW = liveSnap.optInt("base_width", baseW).coerceAtLeast(1)
            baseH = liveSnap.optInt("base_height", baseH).coerceAtLeast(1)
            val srcs = liveSnap.optJSONArray("sources")
            if (srcs != null) {
                for (i in 0 until srcs.length()) {
                    val s = srcs.getJSONObject(i)
                    val sid = s.optString("id")
                    if (sid == "android_camera" && existing == null) existing = s.optString("name")
                    if (sid == "android_screen") hasScreen = true
                }
            }
        } catch (_: Exception) {}
        val sourceName = if (existing != null) {
            if (existing != name && NativeBridge.nativeRenameSource(existing, name)) name else existing
        } else {
            NativeBridge.nativeAddSource("android_camera", name, "")
            name
        }
        val sensorDeg = cameraCapture.start(front = front, width = 1280, height = 720)
        val rot = if (baseH > baseW) sensorDeg.toFloat() else 0f
        if (hasScreen && pipIfScreenActive) {
            // Preserve existing PiP position/size on Front <-> Back flip; only place default PiP if newly added
            if (existing == null) {
                NativeBridge.nativeSetSourceTransform(sourceName, baseW * 0.80f, baseH * 0.24f, 0.28f, 0.28f, rot)
            }
        } else {
            // Apply clean center-aligned fit + orientation transform
            NativeBridge.nativeFitSourceToScreen(sourceName)
            if (rot != 0f) {
                NativeBridge.nativeSetSourceTransform(sourceName, baseW / 2f, baseH / 2f, 1f, 1f, rot)
            }
        }
        statusBanner = "$name active (sensor ${sensorDeg}°)"
        refreshSnapshot()
    }

    private fun toggleExternalProjector() {
        if (projector != null) {
            projector?.dismiss()
            projector = null
            statusBanner = "External Projector closed"
            return
        }
        val dm = getSystemService(DisplayManager::class.java)
        val ext = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()
        if (ext == null) {
            statusBanner = "No external HDMI/USB-C/Wireless display detected"
            return
        }
        projector = ProjectorPresentation(this, ext).also { it.show() }
        statusBanner = "Projector active on ${ext.name}"
    }

    private fun copyUriToLocalCache(uri: Uri): String? {
        return try {
            val dir = File(filesDir, "media").apply { mkdirs() }
            val ext = contentResolver.getType(uri)?.substringAfter('/') ?: "bin"
            val dst = File(dir, "media_${System.currentTimeMillis()}.$ext")
            contentResolver.openInputStream(uri)?.use { inp -> dst.outputStream().use { out -> inp.copyTo(out) } }
            dst.absolutePath
        } catch (_: Exception) { null }
    }

    private fun readDeviceStats(): String {
        val batt = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batt?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val tempC = (batt?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val pm = getSystemService(PowerManager::class.java)
        val headroom = if (Build.VERSION.SDK_INT >= 30) pm.getThermalHeadroom(10) else 0f
        val memMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)
        return String.format(Locale.US, "Batt: %d%% (%.1f°C) | Thermal: %.2f | Heap: %d MB", level, tempC, headroom, memMb)
    }

    private fun requestScreenCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun toggleRecording(bitrateKbps: Int) {
        if (snapshot.recording) {
            NativeBridge.nativeStopRecording()
            statusBanner = "Recording saved!"
        } else {
            val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
            dir.mkdirs()
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outFile = File(dir, "panggung_$ts.mp4").absolutePath
            lastRecordPath = outFile
            val ok = NativeBridge.nativeStartRecording(outFile, bitrateKbps)
            statusBanner = if (ok) "Recording MP4 (${snapshot.videoEncoderId})" else "Failed to start recording"
        }
        refreshSnapshot()
    }

    private fun applyOrientationLock(mode: String) {
        requestedOrientation = when (mode) {
            "PORTRAIT" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "LANDSCAPE" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun startFgs(type: Int) {
        startForegroundService(Intent(this, PanggungService::class.java).putExtra(PanggungService.EXTRA_FGS_TYPE, type))
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.POST_NOTIFICATIONS
        if (needed.isNotEmpty()) permLauncher.launch(needed.toTypedArray()) else micCapture.start()
    }

    private fun refreshSnapshot() {
        try {
            val o = JSONObject(NativeBridge.nativeGetSnapshotJson())
            val scArr = o.optJSONArray("scenes")
            val scenes = buildList { if (scArr != null) for (i in 0 until scArr.length()) add(scArr.getString(i)) }
            val srcArr = o.optJSONArray("sources")
            val sources = buildList {
                if (srcArr != null) {
                    for (i in 0 until srcArr.length()) {
                        val s = srcArr.getJSONObject(i)
                        add(
                            SourceUi(
                                name = s.optString("name"),
                                id = s.optString("id"),
                                visible = s.optBoolean("visible", true),
                                volume = s.optDouble("volume", 1.0).toFloat(),
                                muted = s.optBoolean("muted", false),
                                locked = s.optBoolean("locked", false),
                                magnitudeDb = s.optDouble("magnitude_db", -60.0).toFloat(),
                                peakDb = s.optDouble("peak_db", -60.0).toFloat(),
                            ),
                        )
                    }
                }
            }
            snapshot = SnapshotUi(
                scenes = scenes,
                activeSceneIdx = o.optInt("active_scene_idx", 0),
                previewSceneIdx = o.optInt("preview_scene_idx", 0),
                studioMode = o.optBoolean("studio_mode", false),
                transitionId = o.optString("transition_id", "fade_transition"),
                transitionMs = o.optInt("transition_ms", 300),
                videoEncoderId = o.optString("video_encoder_id", "android_mediacodec_h264"),
                baseWidth = o.optInt("base_width", 1280),
                baseHeight = o.optInt("base_height", 720),
                sources = sources,
                fps = o.optDouble("fps", 0.0),
                frameTimeMs = o.optDouble("frame_time_ms", 0.0),
                totalFrames = o.optInt("total_frames", 0),
                laggedFrames = o.optInt("lagged_frames", 0),
                droppedFrames = o.optInt("dropped_frames", 0),
                outputBytes = o.optLong("output_bytes", 0L),
                congestion = o.optDouble("congestion", 0.0).toFloat(),
                recording = o.optBoolean("recording", false),
                streaming = o.optBoolean("streaming", false),
                replayActive = o.optBoolean("replay_active", false),
            )
        } catch (_: Exception) {}
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!obsReady) return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_F1 -> { NativeBridge.nativeSwitchScene(0); refreshSnapshot(); true }
            KeyEvent.KEYCODE_F2 -> { if (snapshot.scenes.size > 1) NativeBridge.nativeSwitchScene(1); refreshSnapshot(); true }
            KeyEvent.KEYCODE_F9 -> { NativeBridge.nativeTransitionToProgram(); refreshSnapshot(); true }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (snapshot.replayActive) {
                    NativeBridge.nativeSaveReplayBuffer()
                    statusBanner = "Hotkey (Vol+): Replay saved!"
                    true
                } else super.onKeyDown(keyCode, event)
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onStart() {
        super.onStart()
        // Hide floating bubble while user is inside Panggung Studio
        stopService(Intent(this, FloatingBubbleService::class.java))
    }

    override fun onStop() {
        super.onStop()
        if (obsReady) {
            NativeBridge.nativeSaveSceneCollection(File(filesDir, "scene_collection.json").absolutePath)
        }
        // Auto-show Floating Studio Hub popup when leaving into a game or another app
        if (obsReady && floatingHubEnabled && !isFinishing && Settings.canDrawOverlays(this)) {
            startService(Intent(this, FloatingBubbleService::class.java))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            stopService(Intent(this, FloatingBubbleService::class.java))
            cameraCapture.stop()
            webViewCapture.stop()
            micCapture.stop()
            NativeBridge.nativeShutdown()
        }
    }

    private fun extractAssetsIfNeeded(ctx: Context, assetRoot: String, destDir: File) {
        val marker = File(destDir, ".extracted_v6")
        if (marker.exists()) return
        copyAssetFolder(ctx, assetRoot, destDir)
        marker.writeText("ok")
    }

    private fun copyAssetFolder(ctx: Context, assetPath: String, dest: File) {
        val list = ctx.assets.list(assetPath) ?: return
        if (list.isEmpty()) {
            dest.parentFile?.mkdirs()
            ctx.assets.open(assetPath).use { inp -> dest.outputStream().use { out -> inp.copyTo(out) } }
        } else {
            dest.mkdirs()
            for (child in list) copyAssetFolder(ctx, "$assetPath/$child", File(dest, child))
        }
    }
}
