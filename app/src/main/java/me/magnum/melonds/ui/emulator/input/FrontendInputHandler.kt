package me.magnum.melonds.ui.emulator.input

import me.magnum.melonds.domain.model.Input
import me.magnum.melonds.domain.model.Point

abstract class FrontendInputHandler : IInputListener {
    override fun onKeyPress(key: Input) {
        when (key) {
            Input.PAUSE -> onPausePressed()
            Input.FAST_FORWARD -> onFastForwardPressed()
            Input.MICROPHONE -> onMicrophonePressed()
            Input.TOGGLE_SOFT_INPUT -> onSoftInputTogglePressed()
            Input.RESET -> onResetPressed()
            Input.SWAP_SCREENS -> onSwapScreens()
            Input.QUICK_SAVE -> onQuickSave()
            Input.QUICK_LOAD -> onQuickLoad()
            Input.REWIND -> onRewind()
            Input.SOUND_CATALOG -> onSoundCatalog()
            else -> {}
        }
    }

    override fun onKeyReleased(key: Input) {
    }

    override fun onTouch(point: Point) {
    }

    abstract fun onPausePressed()
    abstract fun onFastForwardPressed()
    abstract fun onMicrophonePressed()
    abstract fun onSoftInputTogglePressed()
    abstract fun onResetPressed()
    abstract fun onSwapScreens()
    abstract fun onQuickSave()
    abstract fun onQuickLoad()
    abstract fun onRewind()
    // ff-bgm1x: 소리 분류 화면 (1x Lab 앱)
    open fun onSoundCatalog() {}
}