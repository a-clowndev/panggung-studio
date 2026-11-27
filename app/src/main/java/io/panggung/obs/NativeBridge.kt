package io.panggung.obs

import android.view.Surface
import java.nio.ByteBuffer

object NativeBridge {
    init {
        System.loadLibrary("avutil")
        System.loadLibrary("swresample")
        System.loadLibrary("swscale")
        System.loadLibrary("avcodec")
        System.loadLibrary("avformat")
        System.loadLibrary("avfilter")
        System.loadLibrary("obs")
        System.loadLibrary("obs-android-plugins")
        System.loadLibrary("panggung")
    }

    external fun nativeInit(
        locale: String, dataDir: String, moduleDir: String, configDir: String,
        width: Int, height: Int, fps: Int,
    ): Boolean

    external fun nativeAttachSurface(surface: Surface, width: Int, height: Int): Boolean
    external fun nativeResizeSurface(width: Int, height: Int)
    external fun nativeDetachSurface()

    external fun nativePushCameraI420(
        yBuf: ByteBuffer, uBuf: ByteBuffer, vBuf: ByteBuffer,
        yStride: Int, uStride: Int, vStride: Int, width: Int, height: Int, timestampNs: Long,
    )
    external fun nativePushScreenRgba(rgbaBuf: ByteBuffer, stride: Int, width: Int, height: Int, timestampNs: Long)
    external fun nativePushWebViewRgba(rgbaBuf: ByteBuffer, stride: Int, width: Int, height: Int)
    external fun nativePushSourceRgba(sourceName: String, rgbaBuf: ByteBuffer, stride: Int, width: Int, height: Int): Boolean
    external fun nativePushAudioInputS16(pcmBuf: ByteBuffer, frames: Int, sampleRate: Int, channels: Int, timestampNs: Long)
    external fun nativePushAudioOutputS16(pcmBuf: ByteBuffer, frames: Int, sampleRate: Int, channels: Int, timestampNs: Long)

    external fun nativeCreateScene(name: String): Boolean
    external fun nativeSwitchScene(idx: Int): Boolean
    external fun nativeSetStudioMode(enabled: Boolean)
    external fun nativeTransitionToProgram(): Boolean
    external fun nativeSetTransition(transitionId: String, durationMs: Int): Boolean

    external fun nativeAddSource(sourceId: String, name: String, settingsJson: String): Boolean
    external fun nativeSetSourceVisible(name: String, visible: Boolean)
    external fun nativeSetSourceTransform(name: String, x: Float, y: Float, scaleX: Float, scaleY: Float, rotDeg: Float)
    external fun nativeRemoveSource(name: String)
    external fun nativeSetSourceVolume(name: String, volume: Float)
    external fun nativeSetSourceMuted(name: String, muted: Boolean)

    external fun nativeGetSourcePropertiesJson(name: String): String
    external fun nativeUpdateSourceSettings(name: String, settingsJson: String): Boolean
    external fun nativeListFiltersJson(sourceName: String): String
    external fun nativeAddFilter(sourceName: String, filterId: String, filterName: String, settingsJson: String): Boolean
    external fun nativeRemoveFilter(sourceName: String, filterName: String): Boolean
    external fun nativeSetFilterEnabled(sourceName: String, filterName: String, enabled: Boolean)

    external fun nativeSetVideoEncoder(encoderId: String)
    external fun nativeResetVideo(width: Int, height: Int, fps: Int): Boolean
    external fun nativeResetVideoEx(width: Int, height: Int, fps: Int, fmt: Int, space: Int, range: Int, scale: Int): Boolean
    external fun nativeSaveSceneCollection(path: String): Boolean
    external fun nativeLoadSceneCollection(path: String): Boolean
    external fun nativeGetSnapshotJson(): String

    external fun nativeStartRecording(path: String, bitrateKbps: Int): Boolean
    external fun nativePauseRecording(paused: Boolean): Boolean
    external fun nativeStopRecording()
    external fun nativeStartReplayBuffer(dir: String, maxSec: Int, bitrateKbps: Int): Boolean
    external fun nativeSaveReplayBuffer(): Boolean
    external fun nativeStopReplayBuffer()
    external fun nativeStartStreaming(rtmpUrl: String, streamKey: String, bitrateKbps: Int): Boolean
    external fun nativeStopStreaming()

    external fun nativeSetAudioAdvanced(name: String, syncMs: Int, balance: Float, mixers: Int, monitorType: Int): Boolean
    external fun nativeGetAudioAdvancedJson(name: String): String

    external fun nativeAttachProjectorSurface(surface: Surface, width: Int, height: Int): Boolean
    external fun nativeDetachProjectorSurface()

    external fun nativeRemuxToMp4(src: String, dst: String): Boolean
    external fun nativeStartMultistream(url: String, key: String, bitrateKbps: Int): Boolean
    external fun nativeStopMultistreams()
    external fun nativeStartCustomOutput(outputId: String, url: String, bitrateKbps: Int): Boolean
    external fun nativeStopCustomOutput()

    external fun nativeLoadPluginSo(soPath: String, dataPath: String): Boolean
    external fun nativeRunLuaScript(label: String, code: String): String
    external fun nativeListScriptsJson(): String
    external fun nativeToggleDualCanvas(enabled: Boolean): Boolean
    external fun nativeGetLastCrashLog(): String

    // Fase A: Complete OBS Scene, Source, Media & Studio Mode T-Bar APIs
    external fun nativeMoveSourceOrder(name: String, direction: Int): Boolean
    external fun nativeRenameSource(oldName: String, newName: String): Boolean
    external fun nativeSetSourceLocked(name: String, locked: Boolean): Boolean
    external fun nativeFitSourceToScreen(name: String): Boolean
    external fun nativeGetSourceTransformJson(name: String): String
    external fun nativeRenameScene(idx: Int, newName: String): Boolean
    external fun nativeDeleteScene(idx: Int): Boolean
    external fun nativeMediaPlayPause(name: String, pause: Boolean): Boolean
    external fun nativeMediaRestart(name: String): Boolean
    external fun nativeSetStudioTBar(t: Float): Boolean
    external fun nativeFadeToBlack(): Boolean
    external fun nativeDuplicateScene(idx: Int, dupName: String): Boolean
    external fun nativeCenterSource(name: String): Boolean
    external fun nativeFlipSource(name: String, horizontal: Boolean): Boolean
    external fun nativeResetSourceTransform(name: String): Boolean
    external fun nativeUpdateLiveBitrate(bitrateKbps: Int): Boolean
    external fun nativeUpdateStreamBitrate(bitrateKbps: Int): Boolean

    // Hardened Remote Control (disabled by default; opt-in with mandatory SHA-256 auth)
    external fun nativeSetRemoteControl(enabled: Boolean, lanAccess: Boolean, authToken: String): Boolean
    external fun nativeGetRemoteStatusJson(): String

    external fun nativeGetVersion(): String
    external fun nativeGetLoadedInputsJson(): String
    external fun nativeShutdown()
}
