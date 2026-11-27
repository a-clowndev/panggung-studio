package io.panggung.obs.ui.layouts

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.panggung.obs.SnapshotUi
import io.panggung.obs.SourceUi
import io.panggung.obs.ui.components.*
import io.panggung.obs.ui.theme.PanggungColors
import io.panggung.obs.ui.theme.PanggungRadius
import java.util.Locale

enum class StudioLayoutMode { PhonePortrait, PhoneLandscape3Col, TabletFullSidebar }

data class StudioCallbacks(
    val onSwitchScene: (Int) -> Unit,
    val onCreateScene: () -> Unit,
    val onSelectSource: (String) -> Unit,
    val onLongPressSource: (SourceUi) -> Unit,
    val onToggleSourceVisible: (SourceUi) -> Unit,
    val onToggleSourceLock: (SourceUi) -> Unit,
    val onMoveSourceOrder: (String, Int) -> Unit,
    val onDeleteSource: (String) -> Unit,
    val onOpenProperties: (String) -> Unit,
    val onOpenFilters: (String) -> Unit,
    val onOpenTransform: (String) -> Unit,
    val onFitSource: (String) -> Unit,
    val onToggleMute: (SourceUi) -> Unit,
    val onVolumeChange: (String, Float) -> Unit,
    val onOpenAudioAdv: (String) -> Unit,
    val onToggleRec: () -> Unit,
    val onToggleLive: () -> Unit,
    val onToggleReplay: () -> Unit,
    val onToggleStudioMode: () -> Unit,
    val onTransitionToProgram: () -> Unit,
    val onSetTBar: (Float) -> Unit,
    val onFadeToBlack: () -> Unit,
    val onOpenRemoteQr: () -> Unit,
    val onOpenFullSettings: () -> Unit,
    val onCycleOrientationLock: () -> Unit = {},
    val onRotateSource: (String) -> Unit = {},
    val onFlipSource: (String) -> Unit = {},
    val onOpenAddSource: () -> Unit = {},
    val onOpenTabModal: (Int) -> Unit = {}, // 1=Mixer, 2=Studio/Transitions, 3=Output/RTMP, 4=Tools/QuickSettings
)

/**
 * Responsive Hysteresis Layout Router (T-B02):
 * - Tablet / DeX Full-Sidebar: >= 952dp x 552dp (with 48dp hysteresis band)
 * - Phone 3-Column Compact Landscape: width > height (inspired by Keee7/OBS-for-Android)
 * - Phone Portrait: height >= width
 */
@Composable
fun ResponsiveStudioScaffold(
    obsReady: Boolean,
    obsVersion: String,
    snapshot: SnapshotUi,
    selectedSource: String?,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    statusBanner: String,
    orientationLock: String = "AUTO",
    transformVersion: Int = 0,
    topBanners: @Composable ColumnScope.() -> Unit,
    callbacks: StudioCallbacks,
    tabContent: @Composable (Int) -> Unit,
) {
    var layoutMode by remember { mutableStateOf(StudioLayoutMode.PhonePortrait) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(PanggungColors.BgCanvas)
            .systemBarsPadding(),
    ) {
        val w = maxWidth.value
        val h = maxHeight.value
        // 48dp hysteresis band around 952x552 tablet threshold
        val targetMode = when {
            layoutMode == StudioLayoutMode.TabletFullSidebar && w >= 904f && h >= 504f -> StudioLayoutMode.TabletFullSidebar
            w >= 952f && h >= 552f -> StudioLayoutMode.TabletFullSidebar
            w > h + 24f -> StudioLayoutMode.PhoneLandscape3Col
            else -> StudioLayoutMode.PhonePortrait
        }
        SideEffect { layoutMode = targetMode }

        val selectedObj = snapshot.sources.firstOrNull { it.name == selectedSource }

        when (layoutMode) {
            StudioLayoutMode.PhonePortrait -> PhonePortraitLayout(
                obsReady = obsReady,
                obsVersion = obsVersion,
                snapshot = snapshot,
                selectedSource = selectedSource,
                selectedLocked = selectedObj?.locked == true,
                selectedTab = selectedTab,
                onSelectTab = onSelectTab,
                statusBanner = statusBanner,
                orientationLock = orientationLock,
                transformVersion = transformVersion,
                topBanners = topBanners,
                callbacks = callbacks,
                tabContent = tabContent,
            )
            StudioLayoutMode.PhoneLandscape3Col -> PhoneLandscape3ColLayout(
                obsReady = obsReady,
                obsVersion = obsVersion,
                snapshot = snapshot,
                selectedSource = selectedSource,
                selectedObj = selectedObj,
                statusBanner = statusBanner,
                orientationLock = orientationLock,
                transformVersion = transformVersion,
                callbacks = callbacks,
            )
            StudioLayoutMode.TabletFullSidebar -> TabletFullSidebarLayout(
                obsReady = obsReady,
                obsVersion = obsVersion,
                snapshot = snapshot,
                selectedSource = selectedSource,
                selectedObj = selectedObj,
                statusBanner = statusBanner,
                orientationLock = orientationLock,
                transformVersion = transformVersion,
                callbacks = callbacks,
            )
        }
    }
}

@Composable
private fun PhonePortraitLayout(
    obsReady: Boolean,
    obsVersion: String,
    snapshot: SnapshotUi,
    selectedSource: String?,
    selectedLocked: Boolean,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    statusBanner: String,
    orientationLock: String,
    transformVersion: Int,
    topBanners: @Composable ColumnScope.() -> Unit,
    callbacks: StudioCallbacks,
    tabContent: @Composable (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 1. Broadcast Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("PANGGUNG", color = PanggungColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 0.6.sp)
                Box(
                    modifier = Modifier
                        .clip(PanggungRadius.Badge)
                        .background(PanggungColors.TealMuted)
                        .border(1.dp, PanggungColors.TealPrimary, PanggungRadius.Badge)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text("v0.2.0-a1", color = PanggungColors.TealActive, fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = String.format(Locale.US, "%.1f fps • %.1fms", snapshot.fps, snapshot.frameTimeMs),
                    color = PanggungColors.TextMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
                val rotLbl = when (orientationLock) {
                    "PORTRAIT" -> "PORT"
                    "LANDSCAPE" -> "LAND"
                    else -> "AUTO"
                }
                BroadcastButton(
                    text = rotLbl,
                    onClick = callbacks.onCycleOrientationLock,
                    tone = if (orientationLock == "AUTO") BroadcastTone.Neutral else BroadcastTone.ActiveOutline,
                    compact = true,
                )
                BroadcastButton("QR", onClick = callbacks.onOpenRemoteQr, compact = true)
            }
        }

        // Dynamic Edge-Case Banners (Thermal / Crash Recovery)
        topBanners()

        // 2. Interactive Live Preview Canvas (bounded aspect ratio so bottom tabs & controls are ALWAYS visible in Portrait)
        val portraitPreviewRatio = (snapshot.baseWidth.coerceAtLeast(1).toFloat() / snapshot.baseHeight.coerceAtLeast(1).toFloat()).coerceIn(1.25f, 2.0f)
        InteractivePreviewCanvas(
            obsReady = obsReady,
            baseWidth = snapshot.baseWidth,
            baseHeight = snapshot.baseHeight,
            selectedSource = selectedSource,
            selectedLocked = selectedLocked,
            recording = snapshot.recording,
            streaming = snapshot.streaming,
            studioMode = snapshot.studioMode,
            transformVersion = transformVersion,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(portraitPreviewRatio),
        )

        // 3. Contextual Source Toolbar (fits cleanly in 1 row)
        if (!selectedSource.isNullOrEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PanggungRadius.Button)
                    .background(PanggungColors.DockSurface)
                    .border(1.dp, PanggungColors.Border, PanggungRadius.Button)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    selectedSource,
                    color = PanggungColors.TealActive,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BroadcastButton("Fit", { callbacks.onFitSource(selectedSource) }, compact = true)
                    BroadcastButton("↻90", { callbacks.onRotateSource(selectedSource) }, compact = true)
                    BroadcastButton("⇄", { callbacks.onFlipSource(selectedSource) }, compact = true)
                    BroadcastButton("Prop", { callbacks.onOpenProperties(selectedSource) }, compact = true)
                    BroadcastButton("Flt", { callbacks.onOpenFilters(selectedSource) }, compact = true)
                    BroadcastButton("Xfrm", { callbacks.onOpenTransform(selectedSource) }, compact = true)
                    BroadcastButton("^", { callbacks.onMoveSourceOrder(selectedSource, 0) }, compact = true)
                    BroadcastButton("v", { callbacks.onMoveSourceOrder(selectedSource, 1) }, compact = true)
                }
            }
        }

        // 4. Scenes Strip (crisp teal-bordered tabs, zero Material3 lavender pills)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            snapshot.scenes.forEachIndexed { idx, sName ->
                val selected = if (snapshot.studioMode) idx == snapshot.previewSceneIdx else idx == snapshot.activeSceneIdx
                BroadcastButton(
                    text = sName,
                    onClick = { callbacks.onSwitchScene(idx) },
                    tone = if (selected) BroadcastTone.Primary else BroadcastTone.Neutral,
                    compact = true,
                )
            }
            BroadcastButton(
                text = "+ Scene",
                onClick = callbacks.onCreateScene,
                tone = BroadcastTone.ActiveOutline,
                compact = true,
            )
        }

        // 5. Fixed 5-Tab Bar (TabRow — NEVER clips "Sources"!)
        val tabs = listOf("Sources", "Mixer", "Studio", "Output", "Settings")
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = PanggungColors.DockSurface,
            contentColor = PanggungColors.TealActive,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    height = 2.dp,
                    color = PanggungColors.TealActive,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .clip(PanggungRadius.Button)
                .border(1.dp, PanggungColors.Border, PanggungRadius.Button),
        ) {
            tabs.forEachIndexed { i, title ->
                Tab(
                    selected = selectedTab == i,
                    onClick = { onSelectTab(i) },
                    text = {
                        Text(
                            text = title,
                            fontSize = 11.sp,
                            fontWeight = if (selectedTab == i) FontWeight.Bold else FontWeight.Medium,
                            color = if (selectedTab == i) PanggungColors.TextPrimary else PanggungColors.TextMuted,
                            maxLines = 1,
                        )
                    },
                )
            }
        }

        // 6. Active Dock Body
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            tabContent(selectedTab)
        }

        // 7. Persistent Bottom Broadcast Bar (REC / LIVE / REPLAY / STUDIO)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(PanggungRadius.Card)
                .background(PanggungColors.DockSurface)
                .border(1.dp, PanggungColors.Border, PanggungRadius.Card)
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (snapshot.studioMode) {
                BroadcastButton(
                    text = "CUT → PROGRAM",
                    onClick = callbacks.onTransitionToProgram,
                    tone = BroadcastTone.Warning,
                    modifier = Modifier.fillMaxWidth(),
                    compact = true,
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BroadcastButton(
                    text = if (snapshot.recording) "STOP REC" else "REC",
                    onClick = callbacks.onToggleRec,
                    tone = if (snapshot.recording) BroadcastTone.Danger else BroadcastTone.Neutral,
                    modifier = Modifier.weight(1f),
                    compact = true,
                )
                BroadcastButton(
                    text = if (snapshot.streaming) "STOP LIVE" else "GO LIVE",
                    onClick = callbacks.onToggleLive,
                    tone = if (snapshot.streaming) BroadcastTone.Danger else BroadcastTone.Live,
                    modifier = Modifier.weight(1f),
                    compact = true,
                )
                BroadcastButton(
                    text = if (snapshot.replayActive) "SAVE RPLY" else "REPLAY",
                    onClick = callbacks.onToggleReplay,
                    tone = if (snapshot.replayActive) BroadcastTone.Primary else BroadcastTone.Neutral,
                    modifier = Modifier.weight(1f),
                    compact = true,
                )
                BroadcastButton(
                    text = if (snapshot.studioMode) "STD ON" else "STUDIO",
                    onClick = callbacks.onToggleStudioMode,
                    tone = if (snapshot.studioMode) BroadcastTone.Primary else BroadcastTone.Neutral,
                    modifier = Modifier.weight(1f),
                    compact = true,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = statusBanner.ifEmpty { "Ready • ${snapshot.baseWidth}x${snapshot.baseHeight}" },
                    color = PanggungColors.TealActive,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "Drop:${snapshot.droppedFrames} | ${(snapshot.outputBytes / 1024)}KB",
                    color = PanggungColors.TextDim,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/**
 * Artboard 25 — Phone 3-Column Compact Landscape (Keee7/OBS-for-Android Parity):
 * Col 1 (24%): Sources + Scenes
 * Col 2 (48%): Live Preview + Zoom Bar + Source Quick Toolbar
 * Col 3 (28%): Stereo VU Mixer + 2x2 Controls Dock
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhoneLandscape3ColLayout(
    obsReady: Boolean,
    obsVersion: String,
    snapshot: SnapshotUi,
    selectedSource: String?,
    selectedObj: SourceUi?,
    statusBanner: String,
    orientationLock: String,
    transformVersion: Int,
    callbacks: StudioCallbacks,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // COL 1: Sources & Scenes
            Column(
                modifier = Modifier.weight(0.25f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DockPanel(
                    title = "Sources",
                    modifier = Modifier.weight(0.58f).fillMaxWidth(),
                    headerAction = {
                        Text("+Add", color = PanggungColors.TealActive, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { callbacks.onOpenAddSource() })
                        if (selectedObj != null) {
                            Text("^", color = PanggungColors.TextMuted, fontSize = 11.sp, modifier = Modifier.clickable { callbacks.onMoveSourceOrder(selectedObj.name, 0) })
                            Text("v", color = PanggungColors.TextMuted, fontSize = 11.sp, modifier = Modifier.clickable { callbacks.onMoveSourceOrder(selectedObj.name, 1) })
                        }
                    },
                ) {
                    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        items(snapshot.sources) { src ->
                            val isSel = src.name == selectedSource
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(PanggungRadius.Badge)
                                    .background(if (isSel) PanggungColors.CardSelected else PanggungColors.CardElevated)
                                    .border(1.dp, if (isSel) PanggungColors.TealActive else Color.Transparent, PanggungRadius.Badge)
                                    .combinedClickable(
                                        onClick = { callbacks.onSelectSource(src.name) },
                                        onLongClick = { callbacks.onLongPressSource(src) },
                                    )
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(src.name, color = PanggungColors.TextPrimary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(if (src.visible) "V" else "-", color = if (src.visible) PanggungColors.LiveBright else PanggungColors.TextDim, fontSize = 9.sp,
                                        modifier = Modifier.clickable { callbacks.onToggleSourceVisible(src) })
                                    Text(if (src.locked) "L" else "U", color = if (src.locked) PanggungColors.WarnBright else PanggungColors.TextDim, fontSize = 9.sp,
                                        modifier = Modifier.clickable { callbacks.onToggleSourceLock(src) })
                                    Text("×", color = PanggungColors.RecBright, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clickable { callbacks.onDeleteSource(src.name) })
                                }
                            }
                        }
                    }
                }
                DockPanel(
                    title = "Scenes",
                    modifier = Modifier.weight(0.42f).fillMaxWidth(),
                    headerAction = {
                        Text("+New", color = PanggungColors.TealActive, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { callbacks.onCreateScene() })
                    },
                ) {
                    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        items(snapshot.scenes.indices.toList()) { idx ->
                            val active = if (snapshot.studioMode) idx == snapshot.previewSceneIdx else idx == snapshot.activeSceneIdx
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(PanggungRadius.Badge)
                                    .background(if (active) PanggungColors.TealPrimary else PanggungColors.CardElevated)
                                    .clickable { callbacks.onSwitchScene(idx) }
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                            ) {
                                Text(snapshot.scenes[idx], color = PanggungColors.TextPrimary, fontSize = 10.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }

            // COL 2: Live Preview + Zoom Bar + Contextual Toolbar
            Column(
                modifier = Modifier.weight(0.47f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                InteractivePreviewCanvas(
                    obsReady = obsReady,
                    baseWidth = snapshot.baseWidth,
                    baseHeight = snapshot.baseHeight,
                    selectedSource = selectedSource,
                    selectedLocked = selectedObj?.locked == true,
                    recording = snapshot.recording,
                    streaming = snapshot.streaming,
                    studioMode = snapshot.studioMode,
                    transformVersion = transformVersion,
                    showZoomToolbar = true,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                if (selectedObj != null) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        BroadcastButton("Fit", { callbacks.onFitSource(selectedObj.name) }, compact = true, modifier = Modifier.weight(1f))
                        BroadcastButton("↻90", { callbacks.onRotateSource(selectedObj.name) }, compact = true, modifier = Modifier.weight(1f))
                        BroadcastButton("⇄", { callbacks.onFlipSource(selectedObj.name) }, compact = true, modifier = Modifier.weight(0.8f))
                        BroadcastButton("Prop", { callbacks.onOpenProperties(selectedObj.name) }, compact = true, modifier = Modifier.weight(1f))
                        BroadcastButton("Flt", { callbacks.onOpenFilters(selectedObj.name) }, compact = true, modifier = Modifier.weight(0.9f))
                        BroadcastButton("Xfrm", { callbacks.onOpenTransform(selectedObj.name) }, compact = true, modifier = Modifier.weight(1f))
                    }
                }
            }

            // COL 3: Stereo VU Mixer + 2x2 Controls
            Column(
                modifier = Modifier.weight(0.28f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DockPanel(
                    title = "Audio Mixer",
                    modifier = Modifier.weight(0.45f).fillMaxWidth(),
                    headerAction = {
                        Text("Full", color = PanggungColors.TealActive, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { callbacks.onOpenTabModal(1) })
                    },
                ) {
                    val audioSources = snapshot.sources.filter { it.id.contains("audio") || it.id == "ffmpeg_source" }
                    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        items(audioSources) { src ->
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(src.name, color = PanggungColors.TextPrimary, fontSize = 10.sp, maxLines = 1, modifier = Modifier.weight(1f))
                                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text(
                                            "ADV",
                                            color = PanggungColors.TextMuted,
                                            fontSize = 8.5f.sp,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.clickable { callbacks.onOpenAudioAdv(src.name) },
                                        )
                                        Text(
                                            if (src.muted) "MUTE" else "${src.magnitudeDb.toInt()}dB",
                                            color = if (src.muted) PanggungColors.RecBright else PanggungColors.TealActive,
                                            fontSize = 9.sp,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.clickable { callbacks.onToggleMute(src) },
                                        )
                                    }
                                }
                                StereoVuMeterBar(src.magnitudeDb, src.peakDb, src.muted, barThickness = 3.5.dp)
                            }
                        }
                    }
                }
                DockPanel(title = "Controls", modifier = Modifier.weight(0.55f).fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        BroadcastButton(
                            if (snapshot.streaming) "STOP" else "LIVE",
                            callbacks.onToggleLive,
                            tone = if (snapshot.streaming) BroadcastTone.Danger else BroadcastTone.Live,
                            compact = true,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        BroadcastButton(
                            if (snapshot.recording) "STOP" else "REC",
                            callbacks.onToggleRec,
                            tone = if (snapshot.recording) BroadcastTone.Danger else BroadcastTone.Neutral,
                            compact = true,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        BroadcastButton(
                            if (snapshot.studioMode) "Studio ON" else "Studio",
                            callbacks.onToggleStudioMode,
                            tone = if (snapshot.studioMode) BroadcastTone.Primary else BroadcastTone.Neutral,
                            compact = true,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        if (snapshot.studioMode) {
                            BroadcastButton(
                                "CUT",
                                callbacks.onTransitionToProgram,
                                tone = BroadcastTone.Warning,
                                compact = true,
                                modifier = Modifier.weight(0.75f).fillMaxHeight(),
                            )
                        }
                        BroadcastButton("Output", { callbacks.onOpenTabModal(3) }, compact = true, modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                    Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        BroadcastButton("Trans", { callbacks.onOpenTabModal(2) }, compact = true, modifier = Modifier.weight(1f).fillMaxHeight())
                        BroadcastButton("Tools", { callbacks.onOpenTabModal(4) }, compact = true, modifier = Modifier.weight(1f).fillMaxHeight())
                        BroadcastButton("Set", callbacks.onOpenFullSettings, compact = true, modifier = Modifier.weight(0.85f).fillMaxHeight())
                    }
                }
            }
        }

        // Full-width Status Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(PanggungRadius.Badge)
                .background(PanggungColors.DockSurface)
                .border(1.dp, PanggungColors.Border, PanggungRadius.Badge)
                .padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("PANGGUNG STUDIO v0.2.0-alpha.1 • $statusBanner", color = PanggungColors.TextSecondary, fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 1, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "[QR]",
                    color = PanggungColors.TealActive,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clickable { callbacks.onOpenRemoteQr() },
                )
                Text(
                    text = "[${if (orientationLock == "LANDSCAPE") "LAND" else if (orientationLock == "PORTRAIT") "PORT" else "AUTO"}]",
                    color = if (orientationLock == "AUTO") PanggungColors.TextMuted else PanggungColors.TealActive,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clickable { callbacks.onCycleOrientationLock() },
                )
                Text(
                    String.format(Locale.US, "%.1f fps | %.1fms | Drop:%d", snapshot.fps, snapshot.frameTimeMs, snapshot.droppedFrames),
                    color = PanggungColors.TealActive,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/**
 * Artboard 23 — Tablet / Samsung DeX Full-Sidebar Layout:
 * Left Full-Height Sidebar (Scenes + Sources) | Right Top Giant Preview + Right Bottom 3 Docks (Vertical VU Mixer | Transitions | Controls)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabletFullSidebarLayout(
    obsReady: Boolean,
    obsVersion: String,
    snapshot: SnapshotUi,
    selectedSource: String?,
    selectedObj: SourceUi?,
    statusBanner: String,
    orientationLock: String,
    transformVersion: Int,
    callbacks: StudioCallbacks,
) {
    var tbar by remember { mutableFloatStateOf(0f) }
    Column(modifier = Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Left Full-Height Sidebar
            Column(modifier = Modifier.width(220.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DockPanel(
                    title = "Scenes",
                    modifier = Modifier.weight(0.4f).fillMaxWidth(),
                    headerAction = {
                        Text("+ Add", color = PanggungColors.TealActive, fontSize = 10.sp, modifier = Modifier.clickable { callbacks.onCreateScene() })
                    },
                ) {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(snapshot.scenes.indices.toList()) { idx ->
                            val active = if (snapshot.studioMode) idx == snapshot.previewSceneIdx else idx == snapshot.activeSceneIdx
                            BroadcastButton(
                                text = snapshot.scenes[idx],
                                onClick = { callbacks.onSwitchScene(idx) },
                                tone = if (active) BroadcastTone.Primary else BroadcastTone.Neutral,
                                compact = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                DockPanel(
                    title = "Sources",
                    modifier = Modifier.weight(0.6f).fillMaxWidth(),
                    headerAction = {
                        Text("+ Add", color = PanggungColors.TealActive, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { callbacks.onOpenAddSource() })
                        if (selectedObj != null) {
                            Text("^", color = PanggungColors.TextMuted, fontSize = 11.sp, modifier = Modifier.clickable { callbacks.onMoveSourceOrder(selectedObj.name, 0) })
                            Text("v", color = PanggungColors.TextMuted, fontSize = 11.sp, modifier = Modifier.clickable { callbacks.onMoveSourceOrder(selectedObj.name, 1) })
                        }
                    },
                ) {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(snapshot.sources) { src ->
                            val isSel = src.name == selectedSource
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(PanggungRadius.Button)
                                    .background(if (isSel) PanggungColors.CardSelected else PanggungColors.CardElevated)
                                    .border(1.dp, if (isSel) PanggungColors.TealActive else PanggungColors.Border, PanggungRadius.Button)
                                    .combinedClickable(
                                        onClick = { callbacks.onSelectSource(src.name) },
                                        onLongClick = { callbacks.onLongPressSource(src) },
                                    )
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(src.name, color = PanggungColors.TextPrimary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(if (src.visible) "V" else "-", color = if (src.visible) PanggungColors.LiveBright else PanggungColors.TextDim, fontSize = 10.sp,
                                        modifier = Modifier.clickable { callbacks.onToggleSourceVisible(src) })
                                    Text(if (src.locked) "L" else "U", color = if (src.locked) PanggungColors.WarnBright else PanggungColors.TextDim, fontSize = 10.sp,
                                        modifier = Modifier.clickable { callbacks.onToggleSourceLock(src) })
                                    Text("×", color = PanggungColors.RecBright, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clickable { callbacks.onDeleteSource(src.name) })
                                }
                            }
                        }
                    }
                }
            }

            // Right Area: Giant OBS-Style Preview + Sleek Source Bar + Compact Bottom 3 Docks
            Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InteractivePreviewCanvas(
                    obsReady = obsReady,
                    baseWidth = snapshot.baseWidth,
                    baseHeight = snapshot.baseHeight,
                    selectedSource = selectedSource,
                    selectedLocked = selectedObj?.locked == true,
                    recording = snapshot.recording,
                    streaming = snapshot.streaming,
                    studioMode = snapshot.studioMode,
                    transformVersion = transformVersion,
                    showZoomToolbar = true,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                if (selectedObj != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(32.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BroadcastButton("Fit", { callbacks.onFitSource(selectedObj.name) }, compact = true, modifier = Modifier.weight(1f).fillMaxHeight())
                        BroadcastButton("↻ 90°", { callbacks.onRotateSource(selectedObj.name) }, compact = true, modifier = Modifier.weight(1f).fillMaxHeight())
                        BroadcastButton("⇄ Flip", { callbacks.onFlipSource(selectedObj.name) }, compact = true, modifier = Modifier.weight(1f).fillMaxHeight())
                        BroadcastButton("Prop", { callbacks.onOpenProperties(selectedObj.name) }, compact = true, modifier = Modifier.weight(1f).fillMaxHeight())
                        BroadcastButton("Flt", { callbacks.onOpenFilters(selectedObj.name) }, compact = true, modifier = Modifier.weight(1f).fillMaxHeight())
                        BroadcastButton("Xfrm", { callbacks.onOpenTransform(selectedObj.name) }, compact = true, modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                }
                Row(modifier = Modifier.height(178.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Dock 1: Vertical Stereo VU Mixer
                    DockPanel(
                        title = "Audio Mixer",
                        modifier = Modifier.weight(0.42f).fillMaxHeight(),
                        headerAction = {
                            Text("Full", color = PanggungColors.TealActive, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { callbacks.onOpenTabModal(1) })
                        },
                    ) {
                        val audioSources = snapshot.sources.filter { it.id.contains("audio") || it.id == "ffmpeg_source" }
                        if (audioSources.isEmpty()) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text("No audio source in this scene", color = PanggungColors.TextMuted, fontSize = 10.sp)
                                Spacer(Modifier.height(6.dp))
                                BroadcastButton("+ Add Audio / Source", callbacks.onOpenAddSource, compact = true)
                            }
                        } else {
                            Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                audioSources.forEach { src ->
                                    Column(
                                        modifier = Modifier.fillMaxHeight().width(58.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        Text(src.name, color = PanggungColors.TextSecondary, fontSize = 9.sp, maxLines = 1)
                                        StereoVuMeterBar(src.magnitudeDb, src.peakDb, src.muted, vertical = true, modifier = Modifier.weight(1f))
                                        Text("ADV", color = PanggungColors.TealActive, fontSize = 8.5f.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.clickable { callbacks.onOpenAudioAdv(src.name) })
                                        BroadcastButton(
                                            if (src.muted) "MUTE" else "${(src.volume * 100).toInt()}%",
                                            { callbacks.onToggleMute(src) },
                                            tone = if (src.muted) BroadcastTone.Danger else BroadcastTone.Neutral,
                                            compact = true,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // Dock 2: Transitions + Manual T-Bar
                    DockPanel(title = "Scene Transitions", modifier = Modifier.weight(0.29f).fillMaxHeight()) {
                        LabeledSlider(
                            label = "Manual T-Bar",
                            valueText = "${(tbar * 100).toInt()}%",
                            value = tbar,
                            valueRange = 0f..1f,
                            onValueChange = { tbar = it; callbacks.onSetTBar(it) },
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            BroadcastButton("Cut/Trans", callbacks.onTransitionToProgram, tone = BroadcastTone.Primary, compact = true, modifier = Modifier.weight(1f))
                            BroadcastButton("FTB", callbacks.onFadeToBlack, tone = BroadcastTone.Warning, compact = true, modifier = Modifier.weight(1f))
                        }
                    }
                    // Dock 3: Controls (100% Portrait Parity)
                    DockPanel(title = "Controls", modifier = Modifier.weight(0.29f).fillMaxHeight()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            BroadcastButton(
                                if (snapshot.streaming) "STOP LIVE" else "GO LIVE",
                                callbacks.onToggleLive,
                                tone = if (snapshot.streaming) BroadcastTone.Danger else BroadcastTone.Live,
                                compact = true,
                                modifier = Modifier.weight(1f),
                            )
                            BroadcastButton(
                                if (snapshot.recording) "STOP REC" else "REC",
                                callbacks.onToggleRec,
                                tone = if (snapshot.recording) BroadcastTone.Danger else BroadcastTone.Neutral,
                                compact = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            BroadcastButton("Output", { callbacks.onOpenTabModal(3) }, compact = true, modifier = Modifier.weight(1f))
                            BroadcastButton("Trans", { callbacks.onOpenTabModal(2) }, compact = true, modifier = Modifier.weight(1f))
                            BroadcastButton("Tools", { callbacks.onOpenTabModal(4) }, compact = true, modifier = Modifier.weight(1f))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            BroadcastButton(if (snapshot.studioMode) "Studio ON" else "Studio", callbacks.onToggleStudioMode, tone = if (snapshot.studioMode) BroadcastTone.Primary else BroadcastTone.Neutral, compact = true, modifier = Modifier.weight(1f))
                            BroadcastButton("QR", callbacks.onOpenRemoteQr, compact = true, modifier = Modifier.weight(0.6f))
                            BroadcastButton("Settings", callbacks.onOpenFullSettings, compact = true, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        // Bottom Status Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(PanggungRadius.Badge)
                .background(PanggungColors.DockSurface)
                .border(1.dp, PanggungColors.Border, PanggungRadius.Badge)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("PANGGUNG STUDIO v0.2.0-alpha.1 • $statusBanner", color = PanggungColors.TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "[${if (orientationLock == "LANDSCAPE") "LAND" else if (orientationLock == "PORTRAIT") "PORT" else "AUTO"}]",
                    color = if (orientationLock == "AUTO") PanggungColors.TextMuted else PanggungColors.TealActive,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clickable { callbacks.onCycleOrientationLock() },
                )
                Text(
                    String.format(Locale.US, "FPS: %.1f | Render: %.2fms | Drop: %d | Out: %d KB", snapshot.fps, snapshot.frameTimeMs, snapshot.droppedFrames, snapshot.outputBytes / 1024),
                    color = PanggungColors.TealActive,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
