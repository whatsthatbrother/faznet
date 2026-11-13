package com.faznet.tv.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.faznet.tv.R
import com.faznet.tv.data.Playlist
import com.faznet.tv.data.Repository
import com.faznet.tv.data.safeMessage
import com.faznet.tv.ui.compose.BackButton
import com.faznet.tv.ui.compose.FaznetTheme
import com.faznet.tv.ui.compose.FocusBox
import com.faznet.tv.ui.compose.FormField
import com.faznet.tv.ui.compose.Fz
import com.faznet.tv.ui.compose.IconAction
import com.faznet.tv.ui.compose.PillButton
import com.faznet.tv.ui.compose.ScreenTitle
import com.faznet.tv.ui.compose.SideItem
import com.faznet.tv.ui.compose.screen
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The playlists: OK on one makes it the active one; "Add playlist" opens a form on the same screen. */
class PlaylistsActivity : AppCompatActivity() {

    private lateinit var repo: Repository
    private var playlists by mutableStateOf<List<Playlist>>(emptyList())
    private var activeId by mutableStateOf<String?>(null)
    /** Increased when the list changed: the active playlist takes the remote's focus once. */
    private var listVersion by mutableStateOf(0)
    private var adding by mutableStateOf(false)

    // The add form
    private var type by mutableStateOf(Playlist.TYPE_URL)
    private var name by mutableStateOf("")
    private var url by mutableStateOf("")
    private var server by mutableStateOf("")
    private var user by mutableStateOf("")
    private var pass by mutableStateOf("")
    private var pickedUri by mutableStateOf<Uri?>(null)
    private var pickedLabel by mutableStateOf<String?>(null)
    /** A file is being read in: one import at a time (a second Save used to read the same file in twice). */
    private var importing by mutableStateOf(false)

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pickedUri = uri
            pickedLabel = displayName(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = Repository.get(this)
        refresh()
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_ADD, false)) openForm()
        val tv = isTv()
        window.setBackgroundDrawable(null)
        setContent { FaznetTheme { Screen(tv) } }
    }

    private fun refresh() {
        playlists = repo.playlists()
        activeId = repo.activeId
        listVersion++
    }

    private fun openForm() {
        type = Playlist.TYPE_URL
        name = ""; url = ""; server = ""; user = ""; pass = ""
        pickedUri = null; pickedLabel = null
        adding = true
    }

    private fun confirmDelete(p: Playlist) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.delete_confirm, p.name))
            .setPositiveButton(R.string.delete) { _, _ -> repo.removePlaylist(p.id); refresh() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pick() {
        try {
            pickFile.launch(arrayOf("*/*"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.err_no_picker, Toast.LENGTH_LONG).show()
        }
    }

    private fun saveForm() {
        if (importing) return
        val label = name.trim()
        val id = repo.newId()
        when (type) {
            Playlist.TYPE_XTREAM -> {
                val s = server.trim()
                val u = user.trim()
                if (s.isEmpty() || u.isEmpty() || pass.isEmpty()) return required()
                save(Playlist(id, label.ifEmpty { getString(R.string.type_xtream) }, Playlist.TYPE_XTREAM, s, u, pass))
            }
            Playlist.TYPE_FILE -> {
                val uri = pickedUri ?: return required()
                val file = displayName(uri)
                Toast.makeText(this, R.string.importing, Toast.LENGTH_SHORT).show()
                importing = true
                lifecycleScope.launch {
                    try {
                        repo.importFile(uri, id)
                        save(Playlist(id, label.ifEmpty { file.substringBeforeLast('.') }, Playlist.TYPE_FILE, file))
                    } catch (e: Exception) {
                        runCatching { repo.cacheFile(id).delete() } // no half-copied file left behind
                        Toast.makeText(this@PlaylistsActivity, getString(R.string.err_import, e.safeMessage()), Toast.LENGTH_LONG).show()
                    } finally {
                        importing = false
                    }
                }
            }
            else -> {
                var u = url.trim()
                if (u.isEmpty()) return required()
                if (!u.contains("://")) u = "http://$u"
                save(Playlist(id, label.ifEmpty { getString(R.string.default_playlist_name) }, Playlist.TYPE_URL, u))
            }
        }
    }

    private fun required() {
        Toast.makeText(this, R.string.err_required, Toast.LENGTH_SHORT).show()
    }

    private fun save(p: Playlist) {
        repo.addPlaylist(p)
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

    @Composable
    private fun Screen(tv: Boolean) {
        Column(Modifier.fillMaxSize().screen(tv)) {
            Row(Modifier.fillMaxWidth().height(76.dp).padding(start = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                BackButton { if (adding) adding = false else finish() }
                ScreenTitle(stringResource(if (adding) R.string.add_playlist else R.string.playlists), Modifier.weight(1f))
                if (!adding) AddButton()
            }
            if (adding) {
                BackHandler { adding = false }
                Form()
            } else List()
        }
    }

    @Composable
    private fun AddButton() {
        val requester = remember { FocusRequester() }
        FocusBox(
            onClick = ::openForm, modifier = Modifier.height(50.dp).focusRequester(requester),
            shape = RoundedCornerShape(25.dp), color = Fz.accent, focusedColor = Fz.focusLight,
            focusedBrush = null, outline = false, focusScale = 1.06f,
        ) { focused ->
            Row(Modifier.padding(start = 18.dp, end = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_add), null, tint = if (focused) Fz.onLight else Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.add_playlist), color = if (focused) Fz.onLight else Color.White, fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold)
            }
        }
        // With no playlists, adding one is the only thing to do here.
        if (playlists.isEmpty()) LaunchedEffect(listVersion) { delay(80); runCatching { requester.requestFocus() } }
    }

    @Composable
    private fun List() {
        val list = playlists
        val active = activeId
        val at = remember(listVersion) { list.indexOfFirst { it.id == active }.coerceAtLeast(0) }
        val state = rememberLazyListState(initialFirstVisibleItemIndex = at)
        val focusMe = remember { FocusRequester() }
        LazyColumn(
            Modifier.fillMaxSize(), state = state,
            contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(list) { i, p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlaylistRow(p, p.id == active, Modifier.weight(1f).then(if (i == at) Modifier.focusRequester(focusMe) else Modifier))
                    Spacer(Modifier.width(12.dp))
                    IconAction(R.drawable.ic_delete, stringResource(R.string.delete), { confirmDelete(p) })
                }
            }
        }
        // The active playlist takes the remote's focus once, when the screen opens or the list changed (not every
        // time its row is drawn again, which pulled the focus back to it while scrolling a long list).
        if (list.isNotEmpty()) LaunchedEffect(listVersion) { delay(80); runCatching { focusMe.requestFocus() } }
    }

    @Composable
    private fun PlaylistRow(p: Playlist, active: Boolean, modifier: Modifier) {
        // Only the kind of playlist — never its address or account.
        val kind = stringResource(when (p.type) {
            Playlist.TYPE_XTREAM -> R.string.type_xtream
            Playlist.TYPE_FILE -> R.string.type_file
            else -> R.string.type_url
        })
        val source = if (p.isRemote) "${stringResource(R.string.from_web)} · $kind" else kind
        FocusBox(
            onClick = { repo.activeId = p.id; finish() },
            modifier = modifier.heightIn(min = 72.dp),
            shape = RoundedCornerShape(14.dp), color = Fz.surface, focusedColor = Fz.focusLight,
            focusedBrush = null, outline = false, focusScale = 1.02f,
        ) { focused ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp)) {
                    if (active) Icon(painterResource(R.drawable.ic_check), null, tint = if (focused) Fz.onLight else Fz.accent)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, color = if (focused) Fz.onLight else Fz.text, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(source, color = if (focused) Fz.onLight.copy(alpha = 0.6f) else Fz.dim, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    @Composable
    private fun Form() {
        val first = remember { FocusRequester() }
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 620.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Playlist.TYPE_URL to R.string.type_url, Playlist.TYPE_XTREAM to R.string.type_xtream,
                        Playlist.TYPE_FILE to R.string.type_file).forEachIndexed { i, (t, label) ->
                        SideItem(stringResource(label), selected = type == t, vertical = false, onClick = { type = t },
                            modifier = if (i == 0) Modifier.focusRequester(first) else Modifier)
                    }
                }
                FormField(name, { name = it }, stringResource(R.string.name_hint))
                when (type) {
                    Playlist.TYPE_XTREAM -> {
                        FormField(server, { server = it }, stringResource(R.string.server_hint), keyboardType = KeyboardType.Uri)
                        FormField(user, { user = it }, stringResource(R.string.username_hint))
                        FormField(pass, { pass = it }, stringResource(R.string.password_hint), keyboardType = KeyboardType.Password,
                            password = true)
                    }
                    Playlist.TYPE_FILE -> Row(verticalAlignment = Alignment.CenterVertically) {
                        PillButton(stringResource(R.string.choose_file), primary = false, ::pick)
                        Spacer(Modifier.width(14.dp))
                        Text(pickedLabel ?: stringResource(R.string.no_file_chosen), color = Fz.dim, fontSize = 15.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                    else -> FormField(url, { url = it }, stringResource(R.string.url_hint), keyboardType = KeyboardType.Uri)
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(stringResource(if (importing) R.string.importing else R.string.save), primary = true, ::saveForm)
                    PillButton(stringResource(R.string.cancel), primary = false, { adding = false })
                }
            }
        }
        LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    }

    companion object {
        const val EXTRA_ADD = "add"
    }
}
