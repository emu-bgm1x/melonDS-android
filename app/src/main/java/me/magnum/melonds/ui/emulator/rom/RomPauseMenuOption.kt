package me.magnum.melonds.ui.emulator.rom

import me.magnum.melonds.R
import me.magnum.melonds.ui.emulator.PauseMenuOption

enum class RomPauseMenuOption(override val textResource: Int) : PauseMenuOption {
    SETTINGS(R.string.settings),
    LAYOUT(R.string.ff_layout_edit_game),  // ff-bgm1x: 게임 중에 버튼 배치 편집 (기본 / 이 게임)
    SAVE_STATE(R.string.save_state),
    LOAD_STATE(R.string.load_state),
    REWIND(R.string.rewind),
    CHEATS(R.string.cheats),
    VIEW_ACHIEVEMENTS(R.string.achievements),
    RESET(R.string.reset),
    EXIT(R.string.exit)
}