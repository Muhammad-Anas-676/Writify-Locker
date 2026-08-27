package com.anas.applocker

import android.content.Context
import android.graphics.Color
import android.widget.TextView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout

/**
 * A small preset accent-color palette the user can pick from in Settings. Android
 * doesn't allow re-generating compiled `colors.xml` resources at runtime, so instead
 * every screen that shows the accent color reads it from here (backed by
 * [SettingsStore]) and tints itself programmatically in onResume/onViewCreated.
 */
object ThemeManager {

    data class Swatch(val id: String, val label: String, val color: Int)

    val PALETTE = listOf(
        Swatch("gold", "Gold", Color.parseColor("#C9A15A")),
        Swatch("blue", "Blue", Color.parseColor("#3B82F6")),
        Swatch("green", "Green", Color.parseColor("#10B981")),
        Swatch("red", "Red", Color.parseColor("#C1666B")),
        Swatch("purple", "Purple", Color.parseColor("#8B5CF6")),
        Swatch("teal", "Teal", Color.parseColor("#14B8A6"))
    )

    const val DEFAULT_ID = "gold"

    fun getAccentColor(context: Context): Int {
        val id = SettingsStore(context).getAccentColorId()
        return PALETTE.firstOrNull { it.id == id }?.color ?: PALETTE.first().color
    }

    /** Tints the small set of accent-colored views shared by the dashboard tabs. */
    fun applyToTabLayout(context: Context, tabLayout: TabLayout) {
        val color = getAccentColor(context)
        tabLayout.setSelectedTabIndicatorColor(color)
        tabLayout.setTabTextColors(tabLayout.tabTextColors?.defaultColor ?: Color.GRAY, color)
    }

    fun applyToFab(context: Context, fab: FloatingActionButton) {
        fab.backgroundTintList = android.content.res.ColorStateList.valueOf(getAccentColor(context))
    }

    fun applyToTextView(context: Context, textView: TextView) {
        textView.setTextColor(getAccentColor(context))
    }
}
