package com.faznet.tv.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.faznet.tv.R
import com.faznet.tv.data.Playlist
import com.faznet.tv.data.Repository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class PlaylistsActivity : AppCompatActivity() {

    private lateinit var repo: Repository
    private lateinit var adapter: PlaylistAdapter
    private var pickedUri: Uri? = null
    private var pickedLabel: TextView? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pickedUri = uri
            pickedLabel?.text = displayName(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlists)
        repo = Repository.get(this)

        adapter = PlaylistAdapter(
            onSelect = { p -> repo.activeId = p.id; finish() },
            onDelete = { p -> confirmDelete(p) },
        )
        val rv = findViewById<RecyclerView>(R.id.rvPlaylists)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnAdd).setOnClickListener { showAddDialog() }
        refresh()

        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_ADD, false)) showAddDialog()
    }

    private fun refresh() {
        adapter.submit(repo.playlists(), repo.activeId)
        if (adapter.itemCount == 0) findViewById<View>(R.id.btnAdd).requestFocus()
    }

    private fun confirmDelete(p: Playlist) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.delete_confirm, p.name))
            .setPositiveButton(R.string.delete) { _, _ -> repo.removePlaylist(p.id); refresh() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAddDialog() {
        pickedUri = null
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_add_playlist, null)
        val rg = v.findViewById<RadioGroup>(R.id.rgType)
        val etName = v.findViewById<EditText>(R.id.etName)
        val etUrl = v.findViewById<EditText>(R.id.etUrl)
        val boxXtream = v.findViewById<View>(R.id.boxXtream)
        val etServer = v.findViewById<EditText>(R.id.etServer)
        val etUser = v.findViewById<EditText>(R.id.etUser)
        val etPass = v.findViewById<EditText>(R.id.etPass)
        val boxFile = v.findViewById<View>(R.id.boxFile)
        pickedLabel = v.findViewById(R.id.tvFile)

        rg.setOnCheckedChangeListener { _, id ->
            etUrl.visibility = if (id == R.id.rbUrl) View.VISIBLE else View.GONE
            boxXtream.visibility = if (id == R.id.rbXtream) View.VISIBLE else View.GONE
            boxFile.visibility = if (id == R.id.rbFile) View.VISIBLE else View.GONE
        }
        v.findViewById<View>(R.id.btnPick).setOnClickListener {
            try {
                pickFile.launch(arrayOf("*/*"))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, R.string.err_no_picker, Toast.LENGTH_LONG).show()
            }
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_playlist)
            .setView(v)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = etName.text.toString().trim()
                val id = repo.newId()
                when (rg.checkedRadioButtonId) {
                    R.id.rbXtream -> {
                        val server = etServer.text.toString().trim()
                        val user = etUser.text.toString().trim()
                        val pass = etPass.text.toString()
                        if (server.isEmpty() || user.isEmpty() || pass.isEmpty()) return@setOnClickListener required()
                        val host = Uri.parse(Playlist.normalizeServer(server)).host ?: server
                        save(dialog, Playlist(id, name.ifEmpty { host }, Playlist.TYPE_XTREAM, server, user, pass))
                    }
                    R.id.rbFile -> {
                        val uri = pickedUri ?: return@setOnClickListener required()
                        val label = displayName(uri)
                        Toast.makeText(this, R.string.importing, Toast.LENGTH_SHORT).show()
                        lifecycleScope.launch {
                            try {
                                repo.importFile(uri, id)
                                save(dialog, Playlist(id, name.ifEmpty { label.substringBeforeLast('.') }, Playlist.TYPE_FILE, label))
                            } catch (e: Exception) {
                                Toast.makeText(this@PlaylistsActivity, getString(R.string.err_import, e.message ?: ""), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    else -> {
                        var url = etUrl.text.toString().trim()
                        if (url.isEmpty()) return@setOnClickListener required()
                        if (!url.contains("://")) url = "http://$url"
                        val host = Uri.parse(url).host ?: getString(R.string.default_playlist_name)
                        save(dialog, Playlist(id, name.ifEmpty { host }, Playlist.TYPE_URL, url))
                    }
                }
            }
        }
        dialog.show()
    }

    private fun required() {
        Toast.makeText(this, R.string.err_required, Toast.LENGTH_SHORT).show()
    }

    private fun save(dialog: AlertDialog, p: Playlist) {
        repo.addPlaylist(p)
        dialog.dismiss()
        finish()
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "playlist.m3u"
    }

    companion object {
        const val EXTRA_ADD = "add"
    }
}

class PlaylistAdapter(
    private val onSelect: (Playlist) -> Unit,
    private val onDelete: (Playlist) -> Unit,
) : RecyclerView.Adapter<PlaylistAdapter.VH>() {

    private var items: List<Playlist> = emptyList()
    private var activeId: String? = null

    fun submit(list: List<Playlist>, active: String?) {
        items = list
        activeId = active
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val row: View = v.findViewById(R.id.rowMain)
        val active: ImageView = v.findViewById(R.id.ivActive)
        val name: TextView = v.findViewById(R.id.tvPlName)
        val source: TextView = v.findViewById(R.id.tvPlSource)
        val delete: View = v.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_playlist, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val p = items[position]
        h.name.text = p.name
        h.source.text = if (p.isRemote) "${h.itemView.context.getString(R.string.from_web)} · ${p.describe()}" else p.describe()
        h.active.visibility = if (p.id == activeId) View.VISIBLE else View.INVISIBLE
        h.row.setOnClickListener { onSelect(p) }
        h.delete.setOnClickListener { onDelete(p) }
        if (p.id == activeId) h.row.post { h.row.requestFocus() }
    }
}
