package com.anas.applocker

import android.content.Context
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Persisted set of package names the user has chosen to lock.
 *
 * PERFORMANCE-CRITICAL: [LockAccessibilityService.onAccessibilityEvent] fires on the main
 * thread for every single window-state change on the device, so [isLocked] must be an O(1)
 * pure-memory lookup with zero disk I/O. SharedPreferences.getStringSet() would otherwise
 * involve a HashMap lookup + XML-backed I/O guard on every check, which is exactly the kind
 * of per-event disk touch that causes jank/battery drain on low-end chipsets.
 *
 * Disk is only touched:
 *  - once, lazily, the first time this class is used per-process (cheap - just a normal
 *    SharedPreferences file open, already memory-mapped by the framework after first read)
 *  - asynchronously, off the main thread, whenever the user actually toggles a lock
 */
class LockedAppsStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("locked_apps", Context.MODE_PRIVATE)

    /** In-memory HashSet is the source of truth for every hot-path read. */
    private val cache: MutableSet<String> = Collections.synchronizedSet(
        (prefs.getStringSet(KEY, emptySet()) ?: emptySet()).toMutableSet()
    )

    /** O(1), thread-safe, zero disk I/O. Safe to call from onAccessibilityEvent(). */
    fun isLocked(packageName: String): Boolean = cache.contains(packageName)

    /** Snapshot copy - safe for a caller to iterate/mutate without touching the live cache. */
    fun getLockedPackages(): Set<String> = synchronized(cache) { cache.toSet() }

    fun setLocked(packageName: String, locked: Boolean) {
        val changed = if (locked) cache.add(packageName) else cache.remove(packageName)
        if (!changed) return
        persistAsync()
    }

    /** Bulk import (e.g. from Backup & Restore) - one disk write instead of N. */
    fun setLockedBulk(packageNames: Collection<String>, locked: Boolean) {
        var anyChanged = false
        synchronized(cache) {
            for (pkg in packageNames) {
                val changed = if (locked) cache.add(pkg) else cache.remove(pkg)
                if (changed) anyChanged = true
            }
        }
        if (anyChanged) persistAsync()
    }

    private fun persistAsync() {
        val snapshot = getLockedPackages()
        ioExecutor.execute {
            prefs.edit().putStringSet(KEY, snapshot).apply()
        }
    }

    companion object {
        private const val KEY = "locked_packages"

        // Single-thread executor: writes are tiny and rare (only on user toggle), so a
        // full thread pool would be wasted RAM/overhead on a 1-2GB device.
        private val ioExecutor = Executors.newSingleThreadExecutor()
    }
}
