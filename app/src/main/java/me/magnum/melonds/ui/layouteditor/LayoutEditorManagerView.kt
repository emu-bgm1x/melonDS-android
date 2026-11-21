package me.magnum.melonds.ui.layouteditor

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.get
import androidx.preference.PreferenceManager
import com.squareup.picasso.Callback
import com.squareup.picasso.Picasso
import me.magnum.melonds.R
import me.magnum.melonds.databinding.ViewLayoutEditorManagerBinding
import me.magnum.melonds.domain.model.RuntimeBackground
import me.magnum.melonds.domain.model.consoleAspectRatio
import me.magnum.melonds.domain.model.layout.LayoutComponent
import me.magnum.melonds.extensions.setBackgroundMode
import me.magnum.melonds.ui.common.component.dialog.TextInputDialog
import me.magnum.melonds.ui.common.component.dialog.TextInputDialogState
import me.magnum.melonds.ui.layouteditor.LayoutEditorActivity.MenuOption
import me.magnum.melonds.ui.layouteditor.LayoutEditorManagerView.AspectRatioEnforcementPriority.HEIGHT
import me.magnum.melonds.ui.layouteditor.LayoutEditorManagerView.AspectRatioEnforcementPriority.WIDTH
import me.magnum.melonds.ui.layouteditor.model.LayoutComponentEditableProperty
import me.magnum.melonds.ui.layouteditor.model.LayoutTarget
import me.magnum.melonds.ui.layouteditor.model.ScreenEditorState
import me.magnum.melonds.ui.layouteditor.ui.LayoutBackgroundDialog
import me.magnum.melonds.ui.layouteditor.ui.FfLayoutPanel
import me.magnum.melonds.ui.layouteditor.ui.LayoutComponentPropertyValueDialog
import me.magnum.melonds.ui.layouteditor.ui.LayoutPropertiesDialog
import me.magnum.melonds.ui.theme.MelonTheme
import me.magnum.melonds.utils.getLayoutComponentName
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToInt

private const val CONTROLS_SLIDE_ANIMATION_DURATION_MS = 100L
private const val PREF_MARGIN = "ff_layout_editor_margin"
private const val PREF_GRID = "ff_layout_editor_grid"
private const val PREF_NEW_PANEL = "ff_layout_editor_new_panel"

class LayoutEditorManagerView(
    private val layoutTarget: LayoutTarget,
    private val picasso: Picasso,
    initialEditorState: ScreenEditorState? = null,
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    interface LayoutEditorManagerListener {
        fun openBackgroundPicker(layoutTarget: LayoutTarget, selectedBackgroundId: UUID?)
        fun openSkinPicker() {}  // ff-bgm1x
        fun onStoreLayoutChanges()
        fun onSaveLayoutAndExit()
        fun onExit()
        fun onFfSave(forGame: Boolean) {}  // ff-bgm1x: 게임 중 편집에서 고른 쪽에 저장
        fun onFfResetAll(forGame: Boolean) {}  // ff-bgm1x: 게임 중 편집에서 고른 쪽만 초기화
    }

    // ff-bgm1x: 게임 중에 열었을 때의 저장 대상 (null = 설정 화면에서 연 편집기라 고르기 없음, true = 이 게임)
    val ffTarget = mutableStateOf<Boolean?>(null)

    private enum class ScreenAspectRatio(val ratio: Float?) {
        RATIO_4_3(consoleAspectRatio),
        RATIO_16_9(16 / 9f),
        UNRESTRICTED(null)
    }

    private enum class AspectRatioEnforcementPriority {
        WIDTH, HEIGHT,
    }

    private val binding: ViewLayoutEditorManagerBinding
    private val viewModel: LayoutEditorViewModel by lazy {
        val owner = findViewTreeViewModelStoreOwner() ?: error("No view-model store owner found")
        ViewModelProvider(owner).get<LayoutEditorViewModel>()
    }

    var listener: LayoutEditorManagerListener? = null
    private var areBottomControlsShown = true
    private var areScalingControlsShown = true
    private var selectedViewMinSize = 0
    private var currentWidthScale = 0f
    private var currentHeightScale = 0f
    private var selectedViewIsScreen = false
    private var selectedScreenComponent: LayoutComponent? = null
    private var selectedAspectRatio = ScreenAspectRatio.RATIO_4_3
    private var topAspectRatio = ScreenAspectRatio.RATIO_4_3
    private var bottomAspectRatio = ScreenAspectRatio.RATIO_4_3
    private var updatingAspectSpinner = false

    // ff-bgm1x: 꽉 채우기·가장자리 붙이기 여백과 격자 간격 (px). 편집기 설정이라 레이아웃이 아닌 앱 설정에 둔다
    private val toolPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private var layoutMargin = toolPreferences.getInt(PREF_MARGIN, 0)

    // ff-bgm1x: 새 편집 패널(떠 있는 패널)을 쓰는지. 원래 아래 패널과 골라 쓸 수 있다
    private var useNewPanel by mutableStateOf(toolPreferences.getBoolean(PREF_NEW_PANEL, true))
    private var panelTick by mutableStateOf(0)
    private var panelMargin by mutableStateOf(layoutMargin)
    private var panelGrid by mutableStateOf(toolPreferences.getInt(PREF_GRID, 0))

    private var showLayoutPropertiesDialog by mutableStateOf(initialEditorState?.isPropertiesDialogShown ?: false)
    private var showBackgroundPropertiesDialog by mutableStateOf(initialEditorState?.isBackgroundPropertiesDialogShown ?: false)
    private var shownEditablePropertyDialog by mutableStateOf<LayoutComponentEditableProperty?>(null)
    private val nameInputDialogState = TextInputDialogState()

    val layoutEditorView get() = binding.viewLayoutEditor
    val imageBackground get() = binding.imageBackground

    init {
        val layoutInflater = LayoutInflater.from(context)
        isFocusable = true
        isFocusableInTouchMode = true
        binding = ViewLayoutEditorManagerBinding.inflate(layoutInflater)
        val composeView = ComposeView(context).apply {
            setContent {
                MelonTheme {
                    if (showLayoutPropertiesDialog) {
                        val layoutConfiguration by viewModel.currentLayoutConfiguration.collectAsStateWithLifecycle()
                        val currentConfiguration = layoutConfiguration

                        if (currentConfiguration != null) {
                            LayoutPropertiesDialog(
                                layoutConfiguration = currentConfiguration,
                                onDismiss = { showLayoutPropertiesDialog = false },
                                onSave = { name, orientation, useCustomOpacity, opacity ->
                                    viewModel.savePropertiesToCurrentConfiguration(name, orientation, useCustomOpacity, opacity)
                                    showLayoutPropertiesDialog = false
                                }
                            )
                        }
                    }

                    if (showBackgroundPropertiesDialog) {
                        val backgroundProperties by when (layoutTarget) {
                            LayoutTarget.MAIN_SCREEN -> viewModel.mainScreenBackgroundProperties.collectAsStateWithLifecycle()
                            LayoutTarget.SECONDARY_SCREEN -> viewModel.secondaryScreenBackgroundProperties.collectAsStateWithLifecycle()
                        }
                        val currentBackgroundProperties = backgroundProperties

                        if (currentBackgroundProperties != null) {
                            LayoutBackgroundDialog(
                                backgroundId = currentBackgroundProperties.backgroundId,
                                backgroundMode = currentBackgroundProperties.backgroundMode,
                                loadBackgroundName = { backgroundId -> viewModel.getBackgroundName(backgroundId) },
                                onOpenBackgroundPicker = { listener?.openBackgroundPicker(layoutTarget, currentBackgroundProperties.backgroundId) },
                                onBackgroundModeUpdate = { viewModel.setBackgroundPropertiesBackgroundMode(layoutTarget, it) },
                                onDismiss = {
                                    viewModel.resetBackgroundProperties(layoutTarget)
                                    showBackgroundPropertiesDialog = false
                                },
                                onSave = {
                                    viewModel.saveBackgroundToCurrentConfiguration(layoutTarget)
                                    showBackgroundPropertiesDialog = false
                                }
                            )
                        }
                    }

                    val currentlyShownEditablePropertyDialog = shownEditablePropertyDialog
                    LayoutComponentPropertyValueDialog(
                        editableProperty = currentlyShownEditablePropertyDialog,
                        initialValue = when (currentlyShownEditablePropertyDialog) {
                            LayoutComponentEditableProperty.SIZE -> binding.seekBarSize.progress + selectedViewMinSize
                            LayoutComponentEditableProperty.WIDTH -> binding.seekBarWidth.progress + selectedViewMinSize
                            LayoutComponentEditableProperty.HEIGHT -> binding.seekBarHeight.progress + selectedViewMinSize
                            LayoutComponentEditableProperty.X -> binding.viewLayoutEditor.getSelectedView()?.getPosition()?.x ?: 0
                            LayoutComponentEditableProperty.Y -> binding.viewLayoutEditor.getSelectedView()?.getPosition()?.y ?: 0
                            LayoutComponentEditableProperty.MARGIN -> layoutMargin
                            LayoutComponentEditableProperty.GRID -> binding.viewLayoutEditor.gridSize
                            null -> 0
                        },
                        onValueChanged = {
                            when (currentlyShownEditablePropertyDialog) {
                                LayoutComponentEditableProperty.SIZE -> binding.seekBarSize.progress = it - selectedViewMinSize
                                LayoutComponentEditableProperty.WIDTH -> {
                                    binding.seekBarWidth.progress = it - selectedViewMinSize
                                    enforceAspectRatio(selectedAspectRatio, WIDTH)
                                }
                                LayoutComponentEditableProperty.HEIGHT -> {
                                    binding.seekBarHeight.progress = it - selectedViewMinSize
                                    enforceAspectRatio(selectedAspectRatio, HEIGHT)
                                }
                                LayoutComponentEditableProperty.X -> binding.viewLayoutEditor.moveSelectedViewTo(it, null)
                                LayoutComponentEditableProperty.Y -> binding.viewLayoutEditor.moveSelectedViewTo(null, it)
                                LayoutComponentEditableProperty.MARGIN -> {
                                    layoutMargin = it.coerceAtLeast(0)
                                    toolPreferences.edit().putInt(PREF_MARGIN, layoutMargin).apply()
                                    updateToolLabels()
                                }
                                LayoutComponentEditableProperty.GRID -> {
                                    binding.viewLayoutEditor.gridSize = it.coerceAtLeast(0)
                                    toolPreferences.edit().putInt(PREF_GRID, binding.viewLayoutEditor.gridSize).apply()
                                    updateToolLabels()
                                }
                                null -> { /* no-op */ }
                            }
                            shownEditablePropertyDialog = null
                        },
                        onCancel = { shownEditablePropertyDialog = null },
                    )

                    TextInputDialog(
                        title = stringResource(R.string.layout_name),
                        dialogState = nameInputDialogState,
                    )

                    // ff-bgm1x: 새 편집 패널
                    if (useNewPanel) {
                        FfLayoutPanel(
                            editor = binding.viewLayoutEditor,
                            tick = panelTick,
                            margin = panelMargin,
                            grid = panelGrid,
                            onMarginChanged = {
                                layoutMargin = it
                                panelMargin = it
                                toolPreferences.edit().putInt(PREF_MARGIN, it).apply()
                                updateToolLabels()
                            },
                            onGridChanged = {
                                binding.viewLayoutEditor.gridSize = it
                                panelGrid = it
                                toolPreferences.edit().putInt(PREF_GRID, it).apply()
                                updateToolLabels()
                            },
                            onFillScreen = {
                                val view = binding.viewLayoutEditor.selectedViewOrNull()
                                val ratio = when (view?.component) {
                                    LayoutComponent.TOP_SCREEN -> topAspectRatio.ratio
                                    LayoutComponent.BOTTOM_SCREEN -> bottomAspectRatio.ratio
                                    else -> null
                                }
                                binding.viewLayoutEditor.fillSelectedScreen(layoutMargin, ratio)
                            },
                            onOpenMenu = { openMenu() },
                            onUseOriginalEditor = { setNewPanel(false) },
                            target = ffTarget.value,
                            onTargetChanged = { ffTarget.value = it },
                            onResetAll = { listener?.onFfResetAll(it) },
                            onSave = { listener?.onFfSave(it) },
                            onCancel = { listener?.onExit() },
                        )
                    }
                }
            }
        }
        addView(binding.root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(composeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            binding.layoutScalingContainer.setPadding(
                insets.left,
                0,
                insets.right,
                insets.bottom,
            )
            binding.layoutControls.setPadding(
                insets.left,
                0,
                insets.right,
                insets.bottom,
            )

            WindowInsetsCompat.CONSUMED
        }

        binding.buttonAddButton.setOnClickListener {
            openButtonsMenu()
        }
        binding.buttonMenu.setOnClickListener {
            openMenu()
        }
        binding.buttonDeleteButton.setOnClickListener {
            binding.viewLayoutEditor.deleteSelectedView()
        }
        binding.buttonCenterHorizontal.setOnClickListener {
            binding.viewLayoutEditor.centerSelectedViewHorizontally()
        }
        binding.buttonCenterVertical.setOnClickListener {
            binding.viewLayoutEditor.centerSelectedViewVertically()
        }
        // ff-bgm1x: 배치 편의 기능
        binding.buttonAlignLeft.setOnClickListener {
            binding.viewLayoutEditor.alignSelectedView(LayoutEditorView.Edge.LEFT, layoutMargin)
        }
        binding.buttonAlignRight.setOnClickListener {
            binding.viewLayoutEditor.alignSelectedView(LayoutEditorView.Edge.RIGHT, layoutMargin)
        }
        binding.buttonAlignTop.setOnClickListener {
            binding.viewLayoutEditor.alignSelectedView(LayoutEditorView.Edge.TOP, layoutMargin)
        }
        binding.buttonAlignBottom.setOnClickListener {
            binding.viewLayoutEditor.alignSelectedView(LayoutEditorView.Edge.BOTTOM, layoutMargin)
        }
        binding.buttonFillScreen.setOnClickListener {
            binding.viewLayoutEditor.fillSelectedScreen(layoutMargin, selectedAspectRatio.ratio)
        }
        binding.buttonSameSize.setOnClickListener {
            binding.viewLayoutEditor.sameSizeForSelection()
        }
        binding.buttonMirror.setOnClickListener {
            binding.viewLayoutEditor.mirrorLeftToRight()
        }
        binding.buttonMargin.setOnClickListener {
            shownEditablePropertyDialog = LayoutComponentEditableProperty.MARGIN
        }
        binding.buttonGrid.setOnClickListener {
            shownEditablePropertyDialog = LayoutComponentEditableProperty.GRID
        }
        binding.layoutXLabels.setOnClickListener {
            shownEditablePropertyDialog = LayoutComponentEditableProperty.X
        }
        binding.layoutYLabels.setOnClickListener {
            shownEditablePropertyDialog = LayoutComponentEditableProperty.Y
        }
        binding.viewLayoutEditor.gridSize = toolPreferences.getInt(PREF_GRID, 0)
        binding.viewLayoutEditor.onEditorChanged = { panelTick++ }
        binding.viewLayoutEditor.onSelectedViewChanged = { view ->
            panelTick++
            binding.textX.text = view.getPosition().x.toString()
            binding.textY.text = view.getPosition().y.toString()
            // 길게 눌러 함께 고르면 "고른 버튼 같은 크기로" 를 보인다
            binding.buttonSameSize.isVisible =
                !view.component.isScreen() && binding.viewLayoutEditor.selectionCount() > 1
        }
        updateToolLabels()

        binding.viewLayoutEditor.setLayoutComponentViewBuilderFactory(EditorLayoutComponentViewBuilderFactory())
        binding.viewLayoutEditor.setOnClickListener {
            // 새 편집 패널에서는 빈 곳을 누르면 고른 것만 해제한다
            if (useNewPanel) {
                binding.viewLayoutEditor.clearSelection()
                return@setOnClickListener
            }
            if (areBottomControlsShown)
                hideBottomControls()
            else
                showBottomControls()
        }
        binding.viewLayoutEditor.setOnViewSelectedListener { view, widthScale, heightScale, maxWidth, maxHeight, minSize ->
            if (useNewPanel) {
                panelTick++
                return@setOnViewSelectedListener
            }
            hideBottomControls()
            // Force the scaling controls to restart so the new view always
            // receives fresh listeners even if they were already visible.
            hideScalingControls(false)
            selectedViewIsScreen = view.component.isScreen()
            selectedScreenComponent = view.component
            selectedAspectRatio = when (view.component) {
                LayoutComponent.TOP_SCREEN -> topAspectRatio
                LayoutComponent.BOTTOM_SCREEN -> bottomAspectRatio
                else -> ScreenAspectRatio.UNRESTRICTED
            }
            showScalingControls(
                widthScale,
                heightScale,
                maxWidth,
                maxHeight,
                minSize,
                selectedViewIsScreen,
                view.baseAlpha,
                view.onTop,
            )
        }
        binding.viewLayoutEditor.setOnViewDeselectedListener {
            hideScalingControls()
        }
        binding.layoutSizeLabels.setOnClickListener {
            shownEditablePropertyDialog = LayoutComponentEditableProperty.SIZE
        }
        binding.seekBarSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val scale = progress / seekBar.max.toFloat()
                val value = (binding.seekBarSize.max * scale + selectedViewMinSize).toInt()
                binding.textSize.text = value.toString()
                binding.viewLayoutEditor.scaleSelectedView(scale)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
            }
        })

        binding.layoutWidthLabels.setOnClickListener {
            shownEditablePropertyDialog = LayoutComponentEditableProperty.WIDTH
        }
        binding.seekBarWidth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (selectedViewIsScreen && fromUser && selectedAspectRatio.ratio != null) {
                    enforceAspectRatio(selectedAspectRatio, WIDTH)
                } else {
                    var widthScale = progress / binding.seekBarWidth.max.toFloat()
                    currentWidthScale = widthScale
                    binding.textWidth.text = (binding.seekBarWidth.max * currentWidthScale + selectedViewMinSize).toInt().toString()
                }
                binding.viewLayoutEditor.scaleSelectedView(currentWidthScale, currentHeightScale)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
            }
        })

        binding.layoutHeightLabels.setOnClickListener {
            shownEditablePropertyDialog = LayoutComponentEditableProperty.HEIGHT
        }
        binding.seekBarHeight.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (selectedViewIsScreen && fromUser && selectedAspectRatio.ratio != null) {
                    enforceAspectRatio(selectedAspectRatio, HEIGHT)
                } else {
                    var heightScale = progress / binding.seekBarHeight.max.toFloat()
                    currentHeightScale = heightScale
                    binding.textHeight.text = (binding.seekBarHeight.max * currentHeightScale + selectedViewMinSize).toInt().toString()
                }
                binding.viewLayoutEditor.scaleSelectedView(currentWidthScale, currentHeightScale)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
            }
        })

        binding.seekBarAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val alpha = progress / 100f
                binding.viewLayoutEditor.setSelectedViewAlpha(alpha)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        val aspectOptions = listOf(
            resources.getString(R.string.aspect_ratio_4_3),
            resources.getString(R.string.aspect_ratio_16_9),
            resources.getString(R.string.aspect_ratio_unrestricted),
        )
        val aspectAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, aspectOptions)
        aspectAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerAspectRatio.adapter = aspectAdapter
        binding.spinnerAspectRatio.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (!selectedViewIsScreen || updatingAspectSpinner) return
                selectedAspectRatio = ScreenAspectRatio.entries[position]
                when (selectedScreenComponent) {
                    LayoutComponent.TOP_SCREEN -> topAspectRatio = selectedAspectRatio
                    LayoutComponent.BOTTOM_SCREEN -> bottomAspectRatio = selectedAspectRatio
                    else -> { }
                }
                enforceAspectRatio(selectedAspectRatio, WIDTH)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.checkboxAboveScreen.setOnCheckedChangeListener { _, isChecked ->
            binding.viewLayoutEditor.setSelectedScreenOnTop(isChecked)
        }

        binding.buttonHideControls.setOnClickListener {
            hideScalingControls()
        }

        hideScalingControls(false)

        // ff-bgm1x: 원래 아래 패널에 "새 편집 패널" 버튼을 넣고, 새 패널을 쓰면 아래 패널을 숨긴다
        binding.layoutControls.addView(
            android.widget.Button(context, null, 0, R.style.Button_Editor).apply {
                setText(R.string.ff_layout_new_panel)
                setOnClickListener { setNewPanel(true) }
            },
            binding.layoutControls.childCount - 1,
        )
        if (useNewPanel) hideBottomControls(false)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return if (binding.viewLayoutEditor.handleKeyDown(event)) {
            true
        } else {
            super.onKeyDown(keyCode, event)
        }
    }

    // ff-bgm1x: 새 편집 패널 ↔ 원래 아래 패널
    private fun setNewPanel(enabled: Boolean) {
        useNewPanel = enabled
        toolPreferences.edit().putBoolean(PREF_NEW_PANEL, enabled).apply()
        binding.viewLayoutEditor.clearSelection()
        if (enabled) {
            hideBottomControls(false)
            hideScalingControls(false)
        } else {
            showBottomControls(false)
        }
    }

    fun handleBackNavigation() {
        if (areScalingControlsShown) {
            hideScalingControls(true)
        } else {
            openMenu()
        }
    }

    fun saveEditorState(): ScreenEditorState {
        return ScreenEditorState(
            isMenuShown = false,
            isPropertiesDialogShown = showLayoutPropertiesDialog,
            isBackgroundPropertiesDialogShown = showBackgroundPropertiesDialog,
        )
    }

    fun updateBackground(background: RuntimeBackground) {
        picasso.load(background.background?.uri).into(binding.imageBackground, object : Callback {
            override fun onSuccess() {
                binding.imageBackground.setBackgroundMode(background.mode)
            }

            override fun onError(e: Exception?) {
                e?.printStackTrace()
                Toast.makeText(context, R.string.layout_background_load_failed, Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun openButtonsMenu() {
        hideBottomControls()
        val instantiatedComponents = binding.viewLayoutEditor.getInstantiatedComponents()
        val componentsToShow = LayoutComponent.entries.filterNot {
            instantiatedComponents.contains(it)
        }

        val themedContext = android.view.ContextThemeWrapper(context, R.style.AppTheme)
        val dialogBuilder = AlertDialog.Builder(themedContext)
            .setTitle(R.string.choose_component)
            .setNegativeButton(R.string.cancel) { dialog, _ ->
                dialog.cancel()
            }

        if (componentsToShow.isNotEmpty()) {
            dialogBuilder.setItems(componentsToShow.map { resources.getString(getLayoutComponentName(it)) }.toTypedArray()) { _, which ->
                val component = componentsToShow[which]
                binding.viewLayoutEditor.addLayoutComponent(component)
            }
        } else {
            dialogBuilder.setMessage(R.string.no_more_components)
        }

        dialogBuilder.showInCurrentWindow()
    }

    private fun openMenu() {
        listener?.onStoreLayoutChanges()
        val values = MenuOption.entries
        val options = Array(values.size) { i -> resources.getString(values[i].stringRes) }

        val themedContext = android.view.ContextThemeWrapper(context, R.style.AppTheme)
        AlertDialog.Builder(themedContext)
            .setTitle(R.string.menu)
            .setItems(options) { _, which -> onMenuOptionSelected(values[which]) }
            .setNegativeButton(R.string.cancel, null)
            .showInCurrentWindow()
    }

    private fun onMenuOptionSelected(option: MenuOption) {
        when (option) {
            MenuOption.PROPERTIES -> openPropertiesDialog()
            MenuOption.BACKGROUNDS -> openBackgroundsConfigDialog()
            MenuOption.SKIN -> listener?.openSkinPicker()
            MenuOption.REVERT -> viewModel.revertLayoutChanges()
            MenuOption.RESET -> viewModel.resetLayout()
            MenuOption.SAVE_AND_EXIT -> {
                if (viewModel.currentLayoutHasName()) {
                    listener?.onSaveLayoutAndExit()
                } else {
                    showLayoutNameInputDialog()
                }
            }
            MenuOption.EXIT_WITHOUT_SAVING -> listener?.onExit()
        }
    }

    private fun openPropertiesDialog() {
        listener?.onStoreLayoutChanges()
        showLayoutPropertiesDialog = true
    }

    private fun openBackgroundsConfigDialog() {
        listener?.onStoreLayoutChanges()
        showBackgroundPropertiesDialog = true
    }

    private fun showLayoutNameInputDialog() {
        nameInputDialogState.show(
            initialText = resources.getString(R.string.custom_layout_default_name),
            onConfirm = {
                viewModel.setCurrentLayoutName(it)
                listener?.onSaveLayoutAndExit()
            },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        )
    }

    private fun showBottomControls(animate: Boolean = true) {
        if (areBottomControlsShown) {
            return
        }

        binding.layoutControls.clearAnimation()
        if (animate) {
            binding.layoutControls
                .animate()
                .y(binding.layoutControls.bottom.toFloat() - binding.layoutControls.height.toFloat())
                .setDuration(CONTROLS_SLIDE_ANIMATION_DURATION_MS)
                .withStartAction {
                    binding.layoutControls.isVisible = true
                }
                .start()
        } else {
            binding.layoutControls.isVisible = true
        }

        areBottomControlsShown = true
    }

    private fun hideBottomControls(animate: Boolean = true) {
        if (!areBottomControlsShown) {
            return
        }

        if (animate) {
            binding.layoutControls.clearAnimation()
            binding.layoutControls.animate()
                .y(binding.layoutControls.bottom.toFloat())
                .setDuration(CONTROLS_SLIDE_ANIMATION_DURATION_MS)
                .withEndAction {
                    binding.layoutControls.isGone = true
                }
                .start()
        } else {
            binding.layoutControls.isGone = true
        }

        areBottomControlsShown = false
    }

    private fun showScalingControls(
        widthScale: Float,
        heightScale: Float,
        maxWidth: Int,
        maxHeight: Int,
        minSize: Int,
        isScreen: Boolean,
        alpha: Float,
        onTop: Boolean,
        animate: Boolean = true,
    ) {
        binding.layoutScalingContainer.animate().cancel()

        if (isScreen) {
            binding.seekBarWidth.apply {
                max = maxWidth - minSize
                progress = (widthScale * (maxWidth - minSize)).toInt()
            }
            binding.textWidth.text = ((maxWidth - minSize) * widthScale + minSize).toInt().toString()

            binding.seekBarHeight.apply {
                max = maxHeight - minSize
                progress = (heightScale * (maxHeight - minSize)).toInt()
            }
            binding.textHeight.text = ((maxHeight - minSize) * heightScale + minSize).toInt().toString()
        } else {
            binding.seekBarSize.apply {
                max = min(maxWidth - minSize, maxHeight - minSize)
                progress = (widthScale * (maxWidth - minSize)).roundToInt()
            }
            binding.textSize.text = ((maxWidth - minSize) * widthScale + minSize).toInt().toString()
        }

        binding.seekBarAlpha.progress = (alpha * 100).toInt()
        binding.checkboxAboveScreen.isChecked = onTop
        updatingAspectSpinner = true
        binding.spinnerAspectRatio.setSelection(selectedAspectRatio.ordinal, false)
        updatingAspectSpinner = false

        binding.layoutSizeLabels.isVisible = !isScreen
        binding.seekBarSize.isVisible = !isScreen
        binding.layoutWidthLabels.isVisible = isScreen
        binding.seekBarWidth.isVisible = isScreen
        binding.layoutHeightLabels.isVisible = isScreen
        binding.seekBarHeight.isVisible = isScreen
        binding.layoutAlphaLabels.isVisible = isScreen
        binding.seekBarAlpha.isVisible = isScreen
        binding.layoutAspectRatio.isVisible = isScreen
        binding.checkboxAboveScreen.isVisible = isScreen
        // ff-bgm1x: 정렬은 버튼에도 쓴다. 꽉 채우기는 화면만, 같은 크기는 버튼만
        binding.buttonFillScreen.isVisible = isScreen
        binding.buttonSameSize.isVisible = !isScreen && binding.viewLayoutEditor.selectionCount() > 1

        currentWidthScale = widthScale
        currentHeightScale = heightScale
        selectedViewMinSize = minSize

        if (!areScalingControlsShown) {
            if (animate) {
                binding.layoutScalingContainer.isVisible = true
                binding.layoutScalingContainer.post {
                    binding.layoutScalingContainer
                        .animate()
                        .y(binding.root.bottom.toFloat() - binding.layoutScalingContainer.height.toFloat())
                        .setDuration(CONTROLS_SLIDE_ANIMATION_DURATION_MS)
                        .withEndAction(null)
                        .start()
                }
            } else {
                binding.layoutScalingContainer.isVisible = true
                binding.layoutScalingContainer.y = binding.root.bottom.toFloat() - binding.layoutScalingContainer.height.toFloat()
            }

            areScalingControlsShown = true
        }

        binding.layoutScalingContainer.requestFocus()
    }

    private fun hideScalingControls(animate: Boolean = true) {
        if (!areScalingControlsShown) {
            return
        }

        binding.layoutScalingContainer.animate().cancel()

        if (animate) {
            binding.layoutScalingContainer.post {
                binding.layoutScalingContainer
                    .animate()
                    .y(binding.root.bottom.toFloat())
                    .setDuration(CONTROLS_SLIDE_ANIMATION_DURATION_MS)
                    .withEndAction {
                        binding.layoutScalingContainer.isInvisible = true
                    }
                    .start()
            }
        } else {
            binding.layoutScalingContainer.y = binding.layoutScalingContainer.bottom.toFloat()
            binding.layoutScalingContainer.isInvisible = true
        }

        areScalingControlsShown = false
    }

    private fun updateToolLabels() {
        binding.buttonMargin.text = resources.getString(R.string.ff_layout_margin, layoutMargin)
        binding.buttonGrid.text = resources.getString(R.string.ff_layout_grid, binding.viewLayoutEditor.gridSize)
    }

    private fun enforceAspectRatio(aspectRatio: ScreenAspectRatio, priority: AspectRatioEnforcementPriority) {
        val ratio = aspectRatio.ratio ?: return

        val displayRatio = binding.seekBarWidth.max.toFloat() / binding.seekBarHeight.max.toFloat()
        var width: Float
        var height: Float

        when (priority) {
            WIDTH -> {
                width = binding.seekBarWidth.progress.toFloat() + selectedViewMinSize
                height = width / ratio
            }
            HEIGHT -> {
                height = binding.seekBarHeight.progress.toFloat() + selectedViewMinSize
                width = height * ratio
            }
        }

        val minWidth = selectedViewMinSize * aspectRatio.ratio
        val minHeight = selectedViewMinSize.toFloat()
        val maxWidth = min(binding.seekBarWidth.max.toFloat() + selectedViewMinSize, (binding.seekBarHeight.max + selectedViewMinSize) * ratio)
        val maxHeight = min(binding.seekBarHeight.max.toFloat() + selectedViewMinSize, (binding.seekBarWidth.max + selectedViewMinSize) / ratio)

        // Enforce min size
        if (width < minWidth) {
            width = minWidth
            height = width / ratio
        }
        if (height < minHeight) {
            height = minHeight
            width = height * ratio
        }

        // Enforce max size
        if (displayRatio > ratio) {
            if (width > maxWidth) {
                width = maxWidth
                height = width / ratio
            }
        } else {
            if (height > maxHeight) {
                height = maxHeight
                width = height * ratio
            }
        }

        currentWidthScale = ((width - selectedViewMinSize) / binding.seekBarWidth.max.toFloat()).coerceIn(0f, 1f)
        binding.seekBarWidth.progress = (currentWidthScale * binding.seekBarWidth.max).toInt()
        binding.textWidth.text = (binding.seekBarWidth.max * currentWidthScale + selectedViewMinSize).toInt().toString()

        currentHeightScale = ((height - selectedViewMinSize) / binding.seekBarHeight.max.toFloat()).coerceIn(0f, 1f)
        binding.seekBarHeight.progress = (currentHeightScale * binding.seekBarHeight.max).toInt()
        binding.textHeight.text = (binding.seekBarHeight.max * currentHeightScale + selectedViewMinSize).toInt().toString()
    }

    /**
     * Allows an [AlertDialog] to be shown on a window that is not the main application window. This is required when the layout editor is being shown on a secondary display.
     */
    private fun AlertDialog.Builder.showInCurrentWindow(): AlertDialog {
        val dialog = create()
        dialog.window?.apply {
            setType(WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG)
            attributes.token = this@LayoutEditorManagerView.windowToken
        }
        dialog.show()
        return dialog
    }
}