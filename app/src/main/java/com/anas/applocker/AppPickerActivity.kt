package com.anas.applocker

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class AppPickerActivity : AppCompatActivity() {

    private lateinit var store: LockedAppsStore
    private lateinit var adapter: PickerAdapter
    private var allApps: List<InstalledApp> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)

        store = LockedAppsStore(this)

        findViewById<TextView>(R.id.pickerBackButton).setOnClickListener { finish() }
        findViewById<TextView>(R.id.pickerDoneButton).setOnClickListener { finish() }

        val recyclerView = findViewById<RecyclerView>(R.id.pickerRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = PickerAdapter(store)
        recyclerView.adapter = adapter

        loadInstalledApps()

        findViewById<EditText>(R.id.searchInput).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun loadInstalledApps() {
        val pm = packageManager
        val myPackage = packageName

        val launcherIntent = android.content.Intent(android.content.Intent.ACTION_MAIN)
        launcherIntent.addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val resolveInfos = pm.queryIntentActivities(launcherIntent, 0)

        allApps = resolveInfos
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

        adapter.setApps(allApps)
    }

    private fun filter(query: String) {
        if (query.isBlank()) {
            adapter.setApps(allApps)
        } else {
            adapter.setApps(allApps.filter { it.label.contains(query, ignoreCase = true) })
        }
    }
}

class PickerAdapter(
    private val store: LockedAppsStore
) : RecyclerView.Adapter<PickerAdapter.ViewHolder>() {

    private var apps: List<InstalledApp> = emptyList()

    fun setApps(newApps: List<InstalledApp>) {
        apps = newApps
        notifyDataSetChanged()
    }

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

        holder.checkbox.setOnCheckedChangeListener(null)
        holder.checkbox.isChecked = store.isLocked(app.packageName)
        holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
            store.setLocked(app.packageName, isChecked)
        }
    }

    override fun getItemCount(): Int = apps.size
}
