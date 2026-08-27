package com.anas.applocker

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton

data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: Drawable
)

/**
 * "Locked Apps" tab on the dashboard. Shows only the apps the user has actually chosen
 * to lock (with a Remove action per row), plus a "+" FAB that opens [AppPickerActivity]
 * to add more. The full installed-apps list with checkboxes now lives in the picker,
 * not here - this screen is meant to read like a curated list, the same way the File
 * Vault tab shows only what's already in the vault.
 */
class AppsListFragment : Fragment(R.layout.fragment_apps_list) {

    private lateinit var store: LockedAppsStore
    private lateinit var adapter: LockedAppsAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        store = LockedAppsStore(requireContext())

        val recyclerView = view.findViewById<RecyclerView>(R.id.appsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = LockedAppsAdapter { pkg ->
            store.setLocked(pkg, false)
            refreshList()
        }
        recyclerView.adapter = adapter

        view.findViewById<FloatingActionButton>(R.id.addAppFab).setOnClickListener {
            startActivity(Intent(requireContext(), AppPickerActivity::class.java))
        }

        ThemeManager.applyToFab(requireContext(), view.findViewById(R.id.addAppFab))
    }

    override fun onResume() {
        super.onResume()
        // Picking apps happens in a separate Activity, so refresh whenever we come back to it.
        refreshList()
    }

    private fun refreshList() {
        val pm = requireContext().packageManager
        val apps = store.getLockedPackages().mapNotNull { pkg ->
            try {
                val appInfo: ApplicationInfo = pm.getApplicationInfo(pkg, 0)
                InstalledApp(
                    packageName = pkg,
                    label = pm.getApplicationLabel(appInfo).toString(),
                    icon = pm.getApplicationIcon(appInfo)
                )
            } catch (e: PackageManager.NameNotFoundException) {
                // App was uninstalled since being locked - drop it from the store too.
                store.setLocked(pkg, false)
                null
            }
        }.sortedBy { it.label.lowercase() }

        adapter.setApps(apps)
        view?.findViewById<TextView>(R.id.emptyStateText)?.visibility =
            if (apps.isEmpty()) View.VISIBLE else View.GONE
    }
}

class LockedAppsAdapter(
    private val onRemove: (String) -> Unit
) : RecyclerView.Adapter<LockedAppsAdapter.ViewHolder>() {

    private var apps: List<InstalledApp> = emptyList()

    fun setApps(newApps: List<InstalledApp>) {
        apps = newApps
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val name: TextView = view.findViewById(R.id.appName)
        val remove: TextView = view.findViewById(R.id.removeAppButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_locked_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        holder.icon.setImageDrawable(app.icon)
        holder.name.text = app.label
        holder.remove.setOnClickListener { onRemove(app.packageName) }
    }

    override fun getItemCount(): Int = apps.size
}
