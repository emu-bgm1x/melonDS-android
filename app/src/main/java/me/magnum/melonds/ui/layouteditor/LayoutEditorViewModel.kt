package me.magnum.melonds.ui.layouteditor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.magnum.melonds.domain.model.RuntimeBackground
import me.magnum.melonds.domain.model.layout.BackgroundMode
import me.magnum.melonds.domain.model.layout.Insets
import me.magnum.melonds.domain.model.layout.LayoutConfiguration
import me.magnum.melonds.domain.model.layout.LayoutConfiguration.LayoutOrientation
import me.magnum.melonds.domain.model.layout.LayoutDisplayPair
import me.magnum.melonds.domain.model.layout.PositionedLayoutComponent
import me.magnum.melonds.domain.model.layout.ScreenFold
import me.magnum.melonds.domain.model.layout.ScreenLayout
import me.magnum.melonds.domain.model.layout.UILayout
import me.magnum.melonds.domain.model.layout.UILayoutVariant
import me.magnum.melonds.domain.model.ui.Orientation
import me.magnum.melonds.domain.repositories.BackgroundRepository
import me.magnum.melonds.domain.repositories.LayoutsRepository
import me.magnum.melonds.impl.layout.UILayoutProvider
import me.magnum.melonds.ui.layouteditor.model.CurrentLayoutState
import me.magnum.melonds.ui.layouteditor.model.LayoutBackgroundProperties
import me.magnum.melonds.ui.layouteditor.model.LayoutTarget
import java.util.UUID
import javax.inject.Inject
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.magnum.melonds.domain.model.Background
import me.magnum.melonds.domain.model.Rect
import me.magnum.melonds.domain.model.layout.LayoutComponent
import me.magnum.melonds.ui.layouteditor.skin.FfSkinPack

@HiltViewModel
class LayoutEditorViewModel @Inject constructor(
    private val layoutsRepository: LayoutsRepository,
    private val backgroundsRepository: BackgroundRepository,
    private val uiLayoutProvider: UILayoutProvider,
    private val settingsRepository: me.magnum.melonds.domain.repositories.SettingsRepository,  // ff-bgm1x: 기본 배치 고르기
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private var initialLayoutConfiguration: LayoutConfiguration? = null
    private var currentLayoutVariant: UILayoutVariant? = null

    private val _currentLayoutConfiguration = MutableStateFlow<LayoutConfiguration?>(null)
    val currentLayoutConfiguration = _currentLayoutConfiguration.asStateFlow()

    private val _mainScreenBackground = MutableStateFlow<RuntimeBackground?>(null)
    val mainScreenBackground = _mainScreenBackground.asStateFlow()

    private val _secondaryScreenBackground = MutableStateFlow<RuntimeBackground?>(null)
    val secondaryScreenBackground = _secondaryScreenBackground.asStateFlow()

    // ff-bgm1x: 스킨 적용 결과 알림 (화면에 짧게 띄운다)
    private val _skinMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val skinMessages = _skinMessages.asSharedFlow()

    private val _currentLayout = MutableStateFlow<CurrentLayoutState?>(null)
    val currentLayout = _currentLayout.asStateFlow()

    private val _mainScreenBackgroundProperties = MutableStateFlow<LayoutBackgroundProperties?>(null)
    val mainScreenBackgroundProperties by lazy {
        viewModelScope.launch {
            currentLayout.filterNotNull().map {
                LayoutBackgroundProperties(it.layout.mainScreenLayout.backgroundId, it.layout.mainScreenLayout.backgroundMode)
            }.collect(_mainScreenBackgroundProperties)
        }
        _mainScreenBackgroundProperties.asStateFlow()
    }

    private val _secondaryScreenBackgroundProperties = MutableStateFlow<LayoutBackgroundProperties?>(null)
    val secondaryScreenBackgroundProperties by lazy {
        viewModelScope.launch {
            currentLayout.filterNotNull().map {
                LayoutBackgroundProperties(it.layout.secondaryScreenLayout.backgroundId, it.layout.secondaryScreenLayout.backgroundMode)
            }.collect(_secondaryScreenBackgroundProperties)
        }
        _secondaryScreenBackgroundProperties.asStateFlow()
    }

    init {
        val layoutId = savedStateHandle.get<String?>(LayoutEditorActivity.KEY_LAYOUT_ID)?.let { UUID.fromString(it) }
        if (layoutId != null) {
            viewModelScope.launch {
                val initialLayout = layoutsRepository.getLayout(layoutId)
                initialLayoutConfiguration = initialLayout
                _currentLayoutConfiguration.value = initialLayout
            }
        } else {
            val newLayout = LayoutConfiguration.newCustom(
            )
            initialLayoutConfiguration = newLayout
            _currentLayoutConfiguration.value = newLayout
        }

        viewModelScope.launch {
            _currentLayoutConfiguration.filterNotNull().collect {
                uiLayoutProvider.setCurrentLayoutConfiguration(it)
            }
        }

        viewModelScope.launch {
            uiLayoutProvider.currentLayout.collect {
                if (it == null) {
                    _currentLayout.value = null
                } else {
                    val (variant, layout) = it
                    val currentLayoutConfig = _currentLayoutConfiguration.value ?: return@collect
                    currentLayoutVariant = variant
                    _currentLayout.value = CurrentLayoutState(layout, currentLayoutConfig.orientation)
                }
            }
        }
        viewModelScope.launch {
            _currentLayout.distinctUntilChangedBy { it?.layout?.mainScreenLayout?.backgroundId to it?.layout?.mainScreenLayout?.backgroundMode }.collect {
                if (it != null) {
                    loadMainScreenBackgroundBackground(it.layout.mainScreenLayout.backgroundId, it.layout.mainScreenLayout.backgroundMode)
                } else {
                    // Unload the background. The background mode doesn't matter
                    loadMainScreenBackgroundBackground(null, BackgroundMode.FIT_CENTER)
                }
            }
        }
        viewModelScope.launch {
            _currentLayout.distinctUntilChangedBy { it?.layout?.secondaryScreenLayout?.backgroundId to it?.layout?.secondaryScreenLayout?.backgroundMode }.collect {
                if (it != null) {
                    loadSecondaryScreenBackgroundBackground(it.layout.secondaryScreenLayout.backgroundId, it.layout.secondaryScreenLayout.backgroundMode)
                } else {
                    // Unload the background. The background mode doesn't matter
                    loadSecondaryScreenBackgroundBackground(null, BackgroundMode.FIT_CENTER)
                }
            }
        }
    }

    fun setCurrentSystemOrientation(orientation: Orientation) {
        uiLayoutProvider.updateCurrentOrientation(orientation)
    }

    fun setCurrentUiSize(width: Int, height: Int) {
        uiLayoutProvider.updateUiSize(width, height)
    }

    fun setUiInsets(insets: Insets) {
        uiLayoutProvider.updateUiInsets(insets)
    }

    fun setScreenFolds(folds: List<ScreenFold>) {
        uiLayoutProvider.updateFolds(folds)
    }

    fun setConnectedDisplays(displays: LayoutDisplayPair) {
        uiLayoutProvider.updateDisplays(displays)
    }

    suspend fun getBackgroundName(backgroundId: UUID): String? {
        return backgroundsRepository.getBackground(backgroundId)?.name
    }

    private fun loadMainScreenBackgroundBackground(backgroundId: UUID?, mode: BackgroundMode) {
        if (backgroundId == null) {
            _mainScreenBackground.value = RuntimeBackground(null, mode)
        } else {
            viewModelScope.launch {
                val background = backgroundsRepository.getBackground(backgroundId)
                _mainScreenBackground.value = RuntimeBackground(background, mode)
            }
        }
    }

    private fun loadSecondaryScreenBackgroundBackground(backgroundId: UUID?, mode: BackgroundMode) {
        if (backgroundId == null) {
            _secondaryScreenBackground.value = RuntimeBackground(null, mode)
        } else {
            viewModelScope.launch {
                val background = backgroundsRepository.getBackground(backgroundId)
                _secondaryScreenBackground.value = RuntimeBackground(background, mode)
            }
        }
    }

    fun setCurrentLayoutName(name: String) {
        _currentLayoutConfiguration.update {
            it?.copy(
                name = name
            )
        }
    }

    fun saveCurrentLayout() {
        _currentLayoutConfiguration.value?.let {
            viewModelScope.launch {
                layoutsRepository.saveLayout(it)
            }
        }
    }

    /**
     * ff-bgm1x: 게임 중 편집에서 고른 쪽에 저장한다 (레무로이드와 같이).
     * - 이 게임: 게임 전용 배치가 있으면 그것을, 없으면 새로 만들어 저장 (배치 목록에는 보이지 않음). 롬 지정은 게임 화면이 한다.
     * - 기본: 지금 고른 기본 배치에 저장. 기본 배치가 원래 배치(바꿀 수 없음)면 새 배치로 만들어 기본으로 고른다.
     * 끝나면 [done] 에 저장한 배치 번호를 넘긴다.
     */
    fun ffSave(
        forGame: Boolean,
        gameLayoutId: UUID?,
        globalLayoutId: UUID?,
        gameName: String,
        defaultName: String,
        done: (UUID?) -> Unit,
    ) {
        val config = _currentLayoutConfiguration.value
        if (config == null) {
            done(null)
            return
        }
        viewModelScope.launch {
            val savedId = if (forGame) {
                val id = gameLayoutId ?: UUID.randomUUID()
                layoutsRepository.saveLayout(
                    config.copy(id = id, name = me.magnum.melonds.domain.model.layout.FfGameLayouts.nameFor(gameName), type = LayoutConfiguration.LayoutType.CUSTOM)
                )
                id
            } else {
                val global = globalLayoutId?.let { layoutsRepository.getLayout(it) }
                if (global == null || global.type == LayoutConfiguration.LayoutType.DEFAULT) {
                    val id = UUID.randomUUID()
                    layoutsRepository.saveLayout(config.copy(id = id, name = defaultName, type = LayoutConfiguration.LayoutType.CUSTOM))
                    settingsRepository.setSelectedLayoutId(id)
                    id
                } else {
                    layoutsRepository.saveLayout(config.copy(id = global.id, name = global.name, type = LayoutConfiguration.LayoutType.CUSTOM))
                    global.id
                }
            }
            done(savedId)
        }
    }

    fun revertLayoutChanges() {
        _currentLayoutConfiguration.value = initialLayoutConfiguration
    }

    fun resetLayout() {
        _currentLayoutConfiguration.update {
            it?.copy(
                orientation = LayoutOrientation.FOLLOW_SYSTEM,
                useCustomOpacity = false,
                opacity = 50,
                layoutVariants = emptyMap(),
            )
        }
    }

    fun saveLayoutToCurrentConfiguration(
        layoutComponents: List<PositionedLayoutComponent>?,
        secondaryDisplayLayoutComponents: List<PositionedLayoutComponent>?,
    ) {
        if (layoutComponents == null && secondaryDisplayLayoutComponents == null) return
        val currentVariant = currentLayoutVariant ?: return

        _currentLayoutConfiguration.update {
            it?.copy(
                layoutVariants = it.layoutVariants.toMutableMap().apply {
                    val currentLayout = this[currentVariant]
                    val updatedLayout = if (currentLayout != null) {
                        val mainScreenLayout = layoutComponents?.let {
                            currentLayout.mainScreenLayout.copy(components = it)
                        } ?: currentLayout.mainScreenLayout
                        val secondaryScreenLayout = secondaryDisplayLayoutComponents?.let {
                            currentLayout.secondaryScreenLayout.copy(components = it)
                        } ?: currentLayout.secondaryScreenLayout

                        currentLayout.copy(
                            mainScreenLayout = mainScreenLayout,
                            secondaryScreenLayout = secondaryScreenLayout,
                        )
                    } else {
                        UILayout(ScreenLayout(layoutComponents), ScreenLayout(secondaryDisplayLayoutComponents))
                    }

                    this[currentVariant] = updatedLayout
                }
            )
        }
    }

    fun savePropertiesToCurrentConfiguration(name: String?, orientation: LayoutOrientation, useCustomOpacity: Boolean, layoutOpacity: Int) {
        _currentLayoutConfiguration.value?.let {
            _currentLayoutConfiguration.value = it.copy(
                name = name,
                orientation = orientation,
                useCustomOpacity = useCustomOpacity,
                opacity = layoutOpacity
            )
        }
    }

    fun setBackgroundPropertiesBackgroundId(target: LayoutTarget, backgroundId: UUID?) {
        when (target) {
            LayoutTarget.MAIN_SCREEN -> _mainScreenBackgroundProperties.update {
                it?.copy(backgroundId = backgroundId)
            }
            LayoutTarget.SECONDARY_SCREEN -> _secondaryScreenBackgroundProperties.update {
                it?.copy(backgroundId = backgroundId)
            }
        }
    }

    fun setBackgroundPropertiesBackgroundMode(target: LayoutTarget, backgroundMode: BackgroundMode) {
        when (target) {
            LayoutTarget.MAIN_SCREEN -> _mainScreenBackgroundProperties.update {
                it?.copy(backgroundMode = backgroundMode)
            }
            LayoutTarget.SECONDARY_SCREEN -> _secondaryScreenBackgroundProperties.update {
                it?.copy(backgroundMode = backgroundMode)
            }
        }
    }

    fun saveBackgroundToCurrentConfiguration(target: LayoutTarget) {
        val currentVariant = currentLayoutVariant ?: return
        val currentBackgroundProperties = when (target) {
            LayoutTarget.MAIN_SCREEN -> _mainScreenBackgroundProperties.value
            LayoutTarget.SECONDARY_SCREEN -> _secondaryScreenBackgroundProperties.value
        } ?: return

        _currentLayoutConfiguration.update { layoutConfiguration ->
            layoutConfiguration?.copy(
                layoutVariants = layoutConfiguration.layoutVariants.toMutableMap().apply {
                    val updatedLayout = when (target) {
                        LayoutTarget.MAIN_SCREEN -> {
                            if (containsKey(currentVariant)) {
                                this[currentVariant]?.mainScreenLayout?.copy(
                                    backgroundId = currentBackgroundProperties.backgroundId,
                                    backgroundMode = currentBackgroundProperties.backgroundMode,
                                )?.let { updatedScreenLayout ->
                                    this[currentVariant]?.copy(mainScreenLayout = updatedScreenLayout)
                                }
                            } else {
                                UILayout(
                                    mainScreenLayout = ScreenLayout(
                                        backgroundId = currentBackgroundProperties.backgroundId,
                                        backgroundMode = currentBackgroundProperties.backgroundMode,
                                        components = null,
                                    ),
                                    secondaryScreenLayout = ScreenLayout(),
                                )
                            }
                        }
                        LayoutTarget.SECONDARY_SCREEN -> {
                            if (containsKey(currentVariant)) {
                                this[currentVariant]?.secondaryScreenLayout?.copy(
                                    backgroundId = currentBackgroundProperties.backgroundId,
                                    backgroundMode = currentBackgroundProperties.backgroundMode,
                                )?.let { updatedScreenLayout ->
                                    this[currentVariant]?.copy(secondaryScreenLayout = updatedScreenLayout)
                                }
                            } else {
                                UILayout(
                                    mainScreenLayout = ScreenLayout(),
                                    secondaryScreenLayout = ScreenLayout(
                                        backgroundId = currentBackgroundProperties.backgroundId,
                                        backgroundMode = currentBackgroundProperties.backgroundMode,
                                        components = null,
                                    ),
                                )
                            }
                        }
                    }
                    updatedLayout?.let {
                        this[currentVariant] = it
                    }
                }
            )
        }
    }

    fun resetBackgroundProperties(target: LayoutTarget) {
        val currentLayout = currentLayout.value?.layout
        when (target) {
            LayoutTarget.MAIN_SCREEN -> {
                _mainScreenBackgroundProperties.value = currentLayout?.let {
                    LayoutBackgroundProperties(currentLayout.mainScreenLayout.backgroundId, currentLayout.mainScreenLayout.backgroundMode)
                }
            }
            LayoutTarget.SECONDARY_SCREEN -> {
                _secondaryScreenBackgroundProperties.value = currentLayout?.let {
                    LayoutBackgroundProperties(currentLayout.secondaryScreenLayout.backgroundId, currentLayout.secondaryScreenLayout.backgroundMode)
                }
            }
        }
    }

    /**
     * ff-bgm1x: 스킨(zip)을 지금 화면(방향·크기)에 적용한다. 화면비가 가장 가까운 배경을 배경으로 넣고(늘려 채움),
     * 배경에 그려진 화면 자리로 위·아래 화면을 옮긴다. 다른 버튼은 그대로 둔다.
     */
    // ff-bgm1x: 스킨 고르기를 시작한 때의 레이아웃 (파일 고르기 화면이 세로로 열려 돌아오는 사이 방향이 바뀔 수 있다)
    private var skinTargetVariant: UILayoutVariant? = null

    fun rememberSkinTarget() {
        skinTargetVariant = currentLayoutVariant
    }

    fun applySkin(context: Context, uri: Uri) {
        val variant = skinTargetVariant ?: currentLayoutVariant ?: return
        skinTargetVariant = null
        val layout = _currentLayoutConfiguration.value?.layoutVariants?.get(variant) ?: _currentLayout.value?.layout ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: "skin.zip"
                    val pack = FfSkinPack.import(context.applicationContext, uri, name)
                    val portrait = variant.orientation == Orientation.PORTRAIT
                    val bg = pack.pickBackground(portrait, variant.uiSize.x, variant.uiSize.y)
                    val detected = bg?.let { FfSkinPack.detectScreens(it.file, 2, listOf(4f / 3f)) }
                    Triple(pack, bg, detected)
                }
            }
            val (pack, bg, detected) = result.getOrElse {
                _skinMessages.tryEmit("Skin: " + (it.message ?: it.javaClass.simpleName))
                return@launch
            }
            // 배경 그림이 없는 스킨은 투명한 배경에 버튼 그림만 잇는다
            val bgFile = bg?.file ?: FfSkinPack.blankBackground(pack.dir)
            val backgroundId = UUID.randomUUID()
            backgroundsRepository.addBackground(Background(backgroundId, pack.name + " " + bgFile.nameWithoutExtension, Uri.fromFile(bgFile)))
            me.magnum.melonds.ui.layouteditor.skin.FfSkinButtons.register(context, backgroundId, pack.dir, bgFile)

            // 화면 자리를 찾았으면 화면을 옮기고, 못 찾았으면(또는 배경이 없으면) 지금 자리에 둔다
            val components = if (detected == null) {
                layout.mainScreenLayout.components ?: emptyList()
            } else {
                val (imageSize, screens) = detected
                val sx = variant.uiSize.x.toFloat() / imageSize.first
                val sy = variant.uiSize.y.toFloat() / imageSize.second
                fun map(r: android.graphics.Rect) = Rect(
                    (r.left * sx).toInt(), (r.top * sy).toInt(), (r.width() * sx).toInt(), (r.height() * sy).toInt(),
                )
                val top = map(screens[0])
                val bottom = map(screens[1])
                (layout.mainScreenLayout.components ?: emptyList()).map {
                    when (it.component) {
                        LayoutComponent.TOP_SCREEN -> it.copy(rect = top)
                        LayoutComponent.BOTTOM_SCREEN -> it.copy(rect = bottom)
                        else -> it
                    }
                }
            }
            _currentLayoutConfiguration.update { config ->
                config?.copy(
                    layoutVariants = config.layoutVariants.toMutableMap().apply {
                        val current = this[variant] ?: layout
                        this[variant] = current.copy(
                            mainScreenLayout = current.mainScreenLayout.copy(
                                backgroundId = backgroundId,
                                backgroundMode = BackgroundMode.STRETCH,
                                components = components,
                            ),
                        )
                    },
                )
            }
            _skinMessages.tryEmit(
                when {
                    bg == null -> "Skin: no background in this skin, only the buttons were changed"
                    detected == null -> "Skin: screen areas not found. Drag each screen onto the background's screen area and tap it once to snap"
                    else -> "Skin applied: " + bg.file.name
                },
            )
        }
    }

    fun currentLayoutHasName(): Boolean {
        return !_currentLayoutConfiguration.value?.name.isNullOrEmpty()
    }
}