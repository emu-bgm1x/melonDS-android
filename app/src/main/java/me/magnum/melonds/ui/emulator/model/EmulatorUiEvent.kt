package me.magnum.melonds.ui.emulator.model

import me.magnum.melonds.domain.model.rewind.RewindWindowPosition
import me.magnum.melonds.domain.model.RomInfo
import me.magnum.melonds.domain.model.SaveStateSlot
import me.magnum.melonds.ui.emulator.rewind.model.RewindWindow

sealed class EmulatorUiEvent {
    sealed class OpenScreen : EmulatorUiEvent() {
        data object SettingsScreen : OpenScreen()
        data class CheatsScreen(val romInfo: RomInfo) : OpenScreen()
        // ff-bgm1x: 게임 중 버튼 배치 편집. layoutId = 지금 쓰는 배치, initialForGame = 이 게임 배치를 쓰는 중
        data class LayoutEditor(
            val layoutId: java.util.UUID,
            val initialForGame: Boolean,
            val gameLayoutId: java.util.UUID?,
            val globalLayoutId: java.util.UUID,
            val gameName: String,
        ) : OpenScreen()
    }
    data class ShowPauseMenu(val pauseMenu: PauseMenu) : EmulatorUiEvent()
    data class ShowRewindWindow(val rewindWindow: RewindWindow, val windowPosition: RewindWindowPosition) : EmulatorUiEvent()
    data class ShowRomSaveStates(val saveStates: List<SaveStateSlot>, val reason: Reason) : EmulatorUiEvent() {
        enum class Reason {
            SAVING,
            LOADING,
        }
    }
    data object ShowAchievementList : EmulatorUiEvent()
    data object ShowPendingSubmissionsDialog : EmulatorUiEvent()
    data object CloseEmulator : EmulatorUiEvent()
}