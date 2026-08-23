package com.anas.applocker

import android.content.Context

/** Simple persisted set of package names the user has chosen to lock. */
class LockedAppsStore(context: Context) {

    private val prefs = context.getSharedPreferences("locked_apps", Context.MODE_PRIVATE)

    fun getLockedPackages(): Set<String> =
        prefs.getStringSet(KEY, emptySet()) ?: emptySet()

    fun setLocked(packageName: String, locked: Boolean) {
        val current = getLockedPackages().toMutableSet()
        if (locked) current.add(packageName) else current.remove(packageName)
        prefs.edit().putStringSet(KEY, current).apply()
    }

    fun isLocked(packageName: String): Boolean = getLockedPackages().contains(packageName)

    companion object {
        private const val KEY = "locked_packages"
    }
}
