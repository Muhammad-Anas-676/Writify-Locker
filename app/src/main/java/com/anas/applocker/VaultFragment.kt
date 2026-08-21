package com.anas.applocker

import android.app.AlertDialog
import android.net.Uri
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
 */
class VaultFragment : Fragment(R.layout.fragment_vault) {

    private lateinit var vaultDir: File
    private lateinit var adapter: VaultAdapter

    private val pickFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { onFilePicked(it) }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        vaultDir = File(requireContext().filesDir, "vault").apply { if (!exists()) mkdirs() }

        val recyclerView = view.findViewById<RecyclerView>(R.id.vaultRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = VaultAdapter(vaultDir) { refreshList() }
        recyclerView.adapter = adapter

        view.findViewById<FloatingActionButton>(R.id.addFileFab).setOnClickListener {
            pickFileLauncher.launch(arrayOf("*/*"))
        }

        refreshList()
    }

    private fun refreshList() {
        adapter.setFiles(vaultDir.listFiles()?.toList() ?: emptyList())
    }

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

            if (deleteOriginal) {
                // Best-effort delete. Whether this succeeds depends on the
                // permissions the picker's content provider grants us.
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
}

class VaultAdapter(
    private val vaultDir: File,
    private val onChanged: () -> Unit
) : RecyclerView.Adapter<VaultAdapter.ViewHolder>() {

    private var files: List<File> = emptyList()

    fun setFiles(newFiles: List<File>) {
        files = newFiles
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.fileName)
        val delete: TextView = view.findViewById(R.id.deleteFile)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_vault_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        holder.name.text = file.name
        holder.delete.setOnClickListener {
            file.delete()
            onChanged()
        }
    }

    override fun getItemCount(): Int = files.size
}
