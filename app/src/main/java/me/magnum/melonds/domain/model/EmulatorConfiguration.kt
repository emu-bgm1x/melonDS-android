package me.magnum.melonds.domain.model

import android.net.Uri

data class EmulatorConfiguration(
        val useCustomBios: Boolean,
        val showBootScreen: Boolean,
        val dsBios7Uri: Uri?,
        val dsBios9Uri: Uri?,
        val dsFirmwareUri: Uri?,
        val dsiBios7Uri: Uri?,
        val dsiBios9Uri: Uri?,
        val dsiFirmwareUri: Uri?,
        val dsiNandUri: Uri?,
        val internalDirectory: String,
        val fastForwardSpeedMultiplier: Float,
        // ff-bgm1x: 배속 중 BGM·효과음 1배속 (네이티브가 이 이름으로 읽는다)
        val bgm1x: Boolean,
        val bgm1xFastForwardCutscenes: Boolean,
        val bgm1xSfx: Boolean,
        val bgm1xSfxVolume: Int,
        val rewindEnabled: Boolean,
        val rewindPeriodSeconds: Int,
        val rewindWindowSeconds: Int,
        val useJit: Boolean,
        val consoleType: ConsoleType,
        val soundEnabled: Boolean,
        val audioInterpolation: AudioInterpolation,
        val audioBitrate: AudioBitrate,
        val volume: Int,
        val audioLatency: AudioLatency,
        val micSource: MicSource,
        val firmwareConfiguration: FirmwareConfiguration,
        val rendererConfiguration: RendererConfiguration
)