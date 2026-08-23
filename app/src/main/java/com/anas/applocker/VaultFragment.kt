package com.anas.applocker

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File

/**
 * File Vault. Files picked here are copied into this app's private
 * internal storage (filesDir/vault) — a location Android does not
 * expose to the Gallery, Downloads, or any file manager, since it
 * lives inside the app's own sandbox.
 *
 * Exporting a file always goes through Android's system folder
 * picker (Android will not let any app write to arbitrary locations
 * silently — this is an OS-level privacy restriction, not a choice
 * this app makes). What we control is *where the picker opens*:
 *   - "Original Location" -> picker opens already inside the folder
 *     the file was originally imported from (one tap to save)
 *   - "New Folder" / "Custom Location" -> picker opens at the default
 *     root, where the user can create a new folder or browse anywhere
 */
class VaultFragment : Fragment(R.layout.fragment_vault) {

    private lateinit var vaultDir: File
    private lateinit var adapter: VaultAdapter
    private lateinit var metadataStore: VaultMetadataStore
    private var pendingExportFile: File? = null

    private val pickFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { onFilePicked(it) }
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { onExportLocationChosen(it) }
            } else {
                pendingExportFile = null
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        vaultDir = File(requireContext().filesDir, "vault").apply { if (!exists()) mkdirs() }
        metadataStore = VaultMetadataStore(requireContext())

        val recyclerView = view.findViewById<RecyclerView>(R.id.vaultRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = VaultAdapter(
            onRemove = { file -> removeFile(file) },
            onExport = { file -> showExportOptions(file) }
        )
        recyclerView.adapter = adapter

        view.findViewById<FloatingActionButton>(R.id.addFileFab).setOnClickListener {
            pickFileLauncher.launch(arrayOf("*/*"))
        }

        refreshList()
    }

    private fun refreshList() {
        adapter.setFiles(vaultDir.listFiles()?.toList() ?: emptyList())
    }

    // ---------- Import (file -> vault) ----------

    private fun onFilePicked(uri: Uri) {
        val name = queryDisplayName(uri) ?: "file_${System.currentTimeMillis()}"

        AlertDialog.Builder(requireContext())
            .setTitle("Import \"$name\"")
            .setMessage("Move it into the vault (delete the original) or keep a copy in both places?")
            .setPositiveButton("Move") { _, _ -> importFile(uri, name, deleteOriginal = true) }
            .setNegativeButton("Copy") { _, _ -> importFile(uri, name, deleteOriginal = false) }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun queryDisplayName(uri: Uri): String? {
        val cursor = requireContext().contentResolver.query(uri, null, null, null, null)
        cursor.use {
            if (it != null && it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return it.getString(idx)
            }
        }
        return null
    }

    private fun importFile(uri: Uri, name: String, deleteOriginal: Boolean) {
        try {
            val destination = File(vaultDir, name)
            requireContext().contentResolver.openInputStream(uri).use { input ->
                destination.outputStream().use { output ->
                    input?.copyTo(output)
                }
            }

            // Remember where this came from so "Export -> Original Location"
            // can hint the system picker to open there later.
            metadataStore.setOriginalUri(name, uri.toString())

            if (deleteOriginal) {
                try {
                    requireContext().contentResolver.delete(uri, null, null)
                } catch (e: SecurityException) {
                    Toast.makeText(
                        requireContext(),
                        "Copied to vault, but couldn't delete the original (no permission)",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            refreshList()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Export (vault -> user-chosen location) ----------

    private fun showExportOptions(file: File) {
        val hasOriginal = metadataStore.getOriginalUri(file.name) != null

        val options = mutableListOf("New Folder", "Custom Location")
        if (hasOriginal) options.add(0, "Original Location")

        AlertDialog.Builder(requireContext())
            .setTitle("Export \"${file.name}\"")
            .setItems(options.toTypedArray()) { _, index ->
                when (options[index]) {
                    "Original Location" -> startExport(file, useOriginalLocationHint = true)
                    else -> startExport(file, useOriginalLocationHint = false)
                }
            }
            .show()
    }

    private fun startExport(file: File, useOriginalLocationHint: Boolean) {
        pendingExportFile = file

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_TITLE, file.name)

            if (useOriginalLocationHint && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val originalUriString = metadataStore.getOriginalUri(file.name)
                originalUriString?.let {
                    try {
                        putExtra(Intent.EXTRA_INITIAL_URI, Uri.parse(it))
                    } catch (e: Exception) {
                        // Hint failed to apply — picker just opens at its usual default, no crash.
                    }
                }
            }
        }

        try {
            exportLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Couldn't open the folder picker: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun onExportLocationChosen(destinationUri: Uri) {
        val sourceFile = pendingExportFile ?: return
        pendingExportFile = null

        try {
            requireContext().contentResolver.openOutputStream(destinationUri).use { output ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(output ?: return)
                }
            }

            AlertDialog.Builder(requireContext())
                .setTitle("Exported")
                .setMessage("\"${sourceFile.name}\" was saved. Remove it from the vault too?")
                .setPositiveButton("Remove from vault") { _, _ ->
                    sourceFile.delete()
                    metadataStore.remove(sourceFile.name)
                    refreshList()
                }
                .setNegativeButton("Keep in vault too", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun removeFile(file: File) {
        AlertDialog.Builder(requireContext())
            .setTitle("Remove \"${file.name}\"?")
            .setMessage("This permanently deletes the file from the vault. It cannot be undone unless you've exported a copy elsewhere.")
            .setPositiveButton("Remove") { _, _ ->
                file.delete()
                metadataStore.remove(file.name)
                refreshList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

/** Remembers, per vault filename, the content Uri it was originally imported from. */
class VaultMetadataStore(context: Context) {
    private val prefs = context.getSharedPreferences("vault_metadata", Context.MODE_PRIVATE)

    fun setOriginalUri(fileName: String, uri: String) {
        prefs.edit().putString(fileName, uri).apply()
    }

    fun getOriginalUri(fileName: String): String? = prefs.getString(fileName, null)

    fun remove(fileName: String) {
        prefs.edit().remove(fileName).apply()
    }
}

class VaultAdapter(
    private val onRemove: (File) -> Unit,
    private val onExport: (File) -> Unit
) : RecyclerView.Adapter<VaultAdapter.ViewHolder>() {

    private var files: List<File> = emptyList()

    fun setFiles(newFiles: List<File>) {
        files = newFiles
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.fileName)
        val export: TextView = view.findViewById(R.id.exportFile)
        val delete: TextView = view.findViewById(R.id.deleteFile)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_vault_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        holder.name.text = file.name
        holder.export.setOnClickListener { onExport(file) }
        holder.delete.setOnClickListener { onRemove(file) }
    }

    override fun getItemCount(): Int = files.size
}
