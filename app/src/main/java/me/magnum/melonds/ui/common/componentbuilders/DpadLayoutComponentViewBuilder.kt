package me.magnum.melonds.ui.common.componentbuilders

import android.content.Context
import android.view.View
import android.widget.ImageView
import me.magnum.melonds.R
import me.magnum.melonds.ui.common.LayoutComponentViewBuilder

class DpadLayoutComponentViewBuilder : LayoutComponentViewBuilder() {
    override fun build(context: Context): View {
        return ImageView(context).apply {
            // ff-bgm1x: 스킨 그림이 있으면 그것
            val skin = me.magnum.melonds.ui.layouteditor.skin.FfSkinButtons.current?.dpad
            if (skin != null) setImageBitmap(skin) else setImageResource(R.drawable.keypad)
        }
    }

    override fun getAspectRatio() = 1f
}