package com.anas.applocker

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: android.graphics.drawable.Drawable
)

class AppsListFragment : Fragment(R.layout.fragment_apps_list) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recyclerView = view.findViewById<RecyclerView>(R.id.appsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        val pm = requireContext().packageManager
        val store = LockedAppsStore(requireContext())
        val myPackage = requireContext().packageName

        // Only show apps with a launcher entry (real user-facing apps),
        // skip ourselves so the locker can't lock itself out.
        val launcherIntent = android.content.Intent(android.content.Intent.ACTION_MAIN)
        launcherIntent.addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val resolveInfos = pm.queryIntentActivities(launcherIntent, 0)

        val apps = resolveInfos
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != myPackage }
            .mapNotNull { pkg ->
                try {
                    val appInfo: ApplicationInfo = pm.getApplicationInfo(pkg, 0)
                    InstalledApp(
                        packageName = pkg,
                        label = pm.getApplicationLabel(appInfo).toString(),
                        icon = pm.getApplicationIcon(appInfo)
                    )
                } catch (e: PackageManager.NameNotFoundException) {
                    null
                }
            }
            .sortedBy { it.label.lowercase() }

        recyclerView.adapter = AppsAdapter(apps, store)
    }
}

class AppsAdapter(
    private val apps: List<InstalledApp>,
    private val store: LockedAppsStore
) : RecyclerView.Adapter<AppsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val name: TextView = view.findViewById(R.id.appName)
        val checkbox: CheckBox = view.findViewById(R.id.appLockCheckbox)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        holder.icon.setImageDrawable(app.icon)
        holder.name.text = app.label

        // Clear listener before setting checked state to avoid firing on recycled views
        holder.checkbox.setOnCheckedChangeListener(null)
        holder.checkbox.isChecked = store.isLocked(app.packageName)
        holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
            store.setLocked(app.packageName, isChecked)
        }
    }

    override fun getItemCount(): Int = apps.size
}
