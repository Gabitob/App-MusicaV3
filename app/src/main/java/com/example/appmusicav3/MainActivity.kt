package com.example.appmusicav3

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val path: String,
    val uri: Uri,
    val albumId: Long
)

@Suppress("DEPRECATION")
class MainActivity : ComponentActivity() {

    private var mediaPlayer: MediaPlayer? = null
    private var songs by mutableStateOf<List<Song>>(emptyList())
    private var currentSongIndex by mutableStateOf(-1)
    private var isPlaying by mutableStateOf(false)
    private var currentPosition by mutableStateOf(0f)
    private var duration by mutableStateOf(0f)
    private var searchQuery by mutableStateOf("")
    private var isShuffleEnabled by mutableStateOf(false)
    private var playlists by mutableStateOf<Map<String, List<Long>>>(emptyMap())
    private var selectedPlaylistName by mutableStateOf<String?>(null)
    private var isPlaybackMinimized by mutableStateOf(false)
    private var pendingUpdate: Triple<Song, String, String>? = null

    private val displayedSongs by derivedStateOf {
        val baseList = if (selectedPlaylistName == null || selectedPlaylistName == "VIEW_ALL_PLAYLISTS") {
            filteredSongs
        } else {
            val playlistIds = playlists[selectedPlaylistName] ?: emptyList()
            songs.filter { it.id in playlistIds }
        }
        baseList
    }

    private val filteredSongs by derivedStateOf {
        if (searchQuery.isEmpty()) {
            songs
        } else {
            songs.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                        it.artist.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            loadMusic()
        } else {
            Toast.makeText(this, "Permissão negada para acessar músicas", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        checkPermissions()

        var songToEdit by mutableStateOf<Song?>(null)

        setContent {
            val listState = rememberLazyListState()
            val coroutineScope = rememberCoroutineScope()

            AppNavigation(
                songs = displayedSongs,
                listState = listState,
                currentSongIndex = currentSongIndex,
                isPlaying = isPlaying,
                currentPosition = currentPosition,
                duration = duration,
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                onSongSelect = { index -> 
                    val song = filteredSongs[index]
                    val originalIndex = songs.indexOf(song)
                    playSong(originalIndex)
                },
                onSongLongClick = { songToEdit = it },
                onPlayPauseClick = { togglePlayPause() },
                onNextClick = { playNext() },
                onPreviousClick = { playPrevious() },
                onSeek = { pos -> seekTo(pos) },
                onStop = { stopMusic() },
                isPlaybackMinimized = isPlaybackMinimized,
                onToggleMinimize = { isPlaybackMinimized = !isPlaybackMinimized },
                isShuffleEnabled = isShuffleEnabled,
                onShuffleToggle = { isShuffleEnabled = !isShuffleEnabled },
                playlists = playlists.keys.toList(),
                selectedPlaylistName = selectedPlaylistName,
                onPlaylistSelect = { selectedPlaylistName = it },
                onAddToPlaylist = { song, playlistName -> 
                    val currentList = playlists[playlistName] ?: emptyList()
                    if (!currentList.contains(song.id)) {
                        playlists = playlists + (playlistName to (currentList + song.id))
                        Toast.makeText(this, "Adicionado à $playlistName", Toast.LENGTH_SHORT).show()
                    }
                },
                onRemoveFromPlaylist = { song, playlistName ->
                    val currentList = playlists[playlistName] ?: emptyList()
                    if (currentList.contains(song.id)) {
                        playlists = playlists + (playlistName to (currentList - song.id))
                        Toast.makeText(this, "Removido de $playlistName", Toast.LENGTH_SHORT).show()
                    }
                },
                onDeletePlaylist = { playlistName ->
                    playlists = playlists - playlistName
                    selectedPlaylistName = "VIEW_ALL_PLAYLISTS"
                    Toast.makeText(this, "Playlist $playlistName excluída", Toast.LENGTH_SHORT).show()
                },
                onCreatePlaylist = { name ->
                    if (!playlists.containsKey(name)) {
                        playlists = playlists + (name to emptyList())
                    }
                },
                onAlphabetClick = { letter ->
                    val index = filteredSongs.indexOfFirst { 
                        if (letter == "#") {
                            !it.title.first().isLetter()
                        } else {
                            it.title.startsWith(letter, ignoreCase = true)
                        }
                    }
                    if (index != -1) {
                        coroutineScope.launch {
                            listState.animateScrollToItem(index)
                        }
                    }
                }
            )

            songToEdit?.let { song ->
                EditSongDialog(
                    song = song,
                    onDismiss = { songToEdit = null },
                    onConfirm = { newTitle, newArtist ->
                        updateSongMetadata(song, newTitle, newArtist)
                        songToEdit = null
                    }
                )
            }
        }

        lifecycleScope.launch {
            while (true) {
                if (isPlaying) {
                    currentPosition = mediaPlayer?.currentPosition?.toFloat() ?: 0f
                }
                delay(1000.milliseconds)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            pendingUpdate?.let { (song, title, artist) ->
                updateSongMetadata(song, title, artist)
            }
            pendingUpdate = null
        }
    }

    private fun checkPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val allGranted = permissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            loadMusic()
        } else {
            requestPermissionLauncher.launch(permissions)
        }
    }

    private fun loadMusic() {
        lifecycleScope.launch(Dispatchers.IO) {
            val songList = mutableListOf<Song>()
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }

            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.ALBUM_ID
            )

            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

            contentResolver.query(
                collection,
                projection,
                selection,
                null,
                "${MediaStore.Audio.Media.TITLE} ASC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val title = cursor.getString(titleColumn) ?: "Desconhecido"
                    val artist = cursor.getString(artistColumn) ?: "Artista Desconhecido"
                    val path = cursor.getString(dataColumn) ?: ""
                    val albumId = cursor.getLong(albumIdColumn)
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                    if (path.endsWith(".mp3", ignoreCase = true)) {
                        songList.add(Song(id, title, artist, path, contentUri, albumId))
                    }
                }
            }

            withContext(Dispatchers.Main) {
                songs = songList.sortedBy { it.title.lowercase() }
            }
        }
    }

    private fun playSong(index: Int) {
        if (index !in songs.indices) return

        mediaPlayer?.stop()
        mediaPlayer?.release()

        currentSongIndex = index
        val song = songs[index]

        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(this@MainActivity, song.uri)
                prepare()
                start()
                this@MainActivity.duration = this.duration.toFloat()
                setOnCompletionListener {
                    playNext()
                }
                this@MainActivity.isPlaying = true
            } catch (e: Exception) {
                this@MainActivity.isPlaying = false
                Toast.makeText(this@MainActivity, "Erro ao tocar música: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun togglePlayPause() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                isPlaying = false
            } else {
                it.start()
                isPlaying = true
            }
        }
    }

    private fun playNext() {
        if (songs.isNotEmpty()) {
            val nextIndex = if (isShuffleEnabled) {
                songs.indices.random()
            } else {
                (currentSongIndex + 1) % songs.size
            }
            playSong(nextIndex)
        }
    }

    private fun playPrevious() {
        if (songs.isNotEmpty()) {
            val prevIndex = if (currentSongIndex <= 0) songs.size - 1 else currentSongIndex - 1
            playSong(prevIndex)
        }
    }

    private fun seekTo(pos: Float) {
        mediaPlayer?.seekTo(pos.toInt())
        currentPosition = pos
    }

    private fun stopMusic() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        currentSongIndex = -1
        isPlaying = false
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun updateSongMetadata(song: Song, newTitle: String, newArtist: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                try {
                    val file = java.io.File(song.path)
                    if (file.exists() && file.canWrite()) {
                        val audioFile = org.jaudiotagger.audio.AudioFileIO.read(file)
                        val tag = audioFile.tag ?: org.jaudiotagger.tag.id3.ID3v23Tag()
                        tag.setField(org.jaudiotagger.tag.FieldKey.TITLE, newTitle)
                        tag.setField(org.jaudiotagger.tag.FieldKey.ARTIST, newArtist)
                        audioFile.tag = tag
                        audioFile.commit()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MottaMusic", "Erro ao editar tag ID3: ${e.message}")
                }

                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
                val contentValues = ContentValues().apply {
                    put(MediaStore.Audio.Media.TITLE, newTitle)
                    put(MediaStore.Audio.Media.ARTIST, newArtist)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val fileName = if (newTitle.endsWith(".mp3", true)) newTitle else "$newTitle.mp3"
                        put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                    }
                }

                try {
                    val updatedRows = contentResolver.update(uri, contentValues, null, null)
                    if (updatedRows > 0) {
                        MediaScannerConnection.scanFile(this@MainActivity, arrayOf(song.path), null) { _, _ -> }
                        withContext(Dispatchers.Main) {
                            songs = songs.map {
                                if (it.id == song.id) it.copy(title = newTitle, artist = newArtist) else it
                            }.sortedBy { it.title.lowercase() }
                            Toast.makeText(this@MainActivity, "Nome alterado!", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (securityException: SecurityException) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val recoverableSecurityException = securityException as? android.app.RecoverableSecurityException
                            ?: throw securityException
                        pendingUpdate = Triple(song, newTitle, newArtist)
                        val intentSender = recoverableSecurityException.userAction.actionIntent.intentSender
                        withContext(Dispatchers.Main) {
                            @Suppress("DEPRECATION")
                            startIntentSenderForResult(intentSender, 1001, null, 0, 0, 0)
                        }
                    } else throw securityException
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Erro: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

@Composable
fun AppNavigation(
    songs: List<Song>,
    listState: LazyListState,
    currentSongIndex: Int,
    isPlaying: Boolean,
    currentPosition: Float,
    duration: Float,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSongSelect: (Int) -> Unit,
    onSongLongClick: (Song) -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onSeek: (Float) -> Unit,
    onStop: () -> Unit,
    isPlaybackMinimized: Boolean,
    onToggleMinimize: () -> Unit,
    onAlphabetClick: (String) -> Unit,
    isShuffleEnabled: Boolean,
    onShuffleToggle: () -> Unit,
    playlists: List<String>,
    selectedPlaylistName: String?,
    onPlaylistSelect: (String?) -> Unit,
    onAddToPlaylist: (Song, String) -> Unit,
    onRemoveFromPlaylist: (Song, String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onCreatePlaylist: (String) -> Unit
) {
    val darkColors = darkColors(
        primary = Color(0xFFBB86FC),
        primaryVariant = Color(0xFF3700B3),
        secondary = Color(0xFF03DAC6),
        background = Color(0xFF121212),
        surface = Color(0xFF1E1E1E),
        onPrimary = Color.Black,
        onSecondary = Color.Black,
        onBackground = Color.White,
        onSurface = Color.White
    )

    MaterialTheme(colors = darkColors) {
        Scaffold(
            topBar = {
                Column(modifier = Modifier.background(MaterialTheme.colors.surface)) {
                    TopAppBar(
                        title = { Text("Motta Music", fontWeight = FontWeight.Bold) },
                        backgroundColor = Color.Transparent,
                        elevation = 0.dp
                    )
                    SearchBar(
                        query = searchQuery,
                        onQueryChange = onSearchQueryChange,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    TabRow(
                        selectedTabIndex = if (selectedPlaylistName == null) 0 else 1,
                        backgroundColor = Color.Transparent,
                        contentColor = MaterialTheme.colors.primary,
                        indicator = { tabPositions ->
                            TabRowDefaults.Indicator(
                                modifier = Modifier.tabIndicatorOffset(tabPositions[if (selectedPlaylistName == null) 0 else 1]),
                                color = MaterialTheme.colors.primary
                            )
                        }
                    ) {
                        Tab(
                            selected = selectedPlaylistName == null,
                            onClick = { onPlaylistSelect(null) },
                            text = { Text("Músicas") }
                        )
                        Tab(
                            selected = selectedPlaylistName != null,
                            onClick = { onPlaylistSelect("VIEW_ALL_PLAYLISTS") },
                            text = { Text("Playlists") }
                        )
                    }
                    if (selectedPlaylistName == null) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onShuffleToggle) {
                                Icon(
                                    imageVector = Icons.Default.Shuffle,
                                    contentDescription = "Aleatório",
                                    tint = if (isShuffleEnabled) MaterialTheme.colors.primary else Color.Gray
                                )
                            }
                            var showNewPlaylistDialog by remember { mutableStateOf(false) }
                            TextButton(onClick = { showNewPlaylistDialog = true }) {
                                Text("+ Nova Playlist", color = MaterialTheme.colors.primary)
                            }
                            if (showNewPlaylistDialog) {
                                var newPlaylistName by remember { mutableStateOf("") }
                                AlertDialog(
                                    onDismissRequest = { showNewPlaylistDialog = false },
                                    title = { Text("Nova Playlist") },
                                    text = {
                                        OutlinedTextField(
                                            value = newPlaylistName,
                                            onValueChange = { newPlaylistName = it },
                                            label = { Text("Nome da Playlist") },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    },
                                    confirmButton = {
                                        Button(onClick = {
                                            if (newPlaylistName.isNotBlank()) {
                                                onCreatePlaylist(newPlaylistName)
                                                showNewPlaylistDialog = false
                                            }
                                        }) { Text("Criar") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showNewPlaylistDialog = false }) { Text("Cancelar") }
                                    }
                                )
                            }
                        }
                    } else if (selectedPlaylistName != "VIEW_ALL_PLAYLISTS") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Playlist: $selectedPlaylistName",
                                style = MaterialTheme.typography.h6,
                                color = MaterialTheme.colors.primary,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { onPlaylistSelect("VIEW_ALL_PLAYLISTS") }) {
                                Text("Voltar", color = Color.Gray)
                            }
                        }
                    }
                }
            },
            bottomBar = {
                if (currentSongIndex != -1 && currentSongIndex < songs.size) {
                    PlaybackControls(
                        song = songs[currentSongIndex],
                        isPlaying = isPlaying,
                        currentPosition = currentPosition,
                        duration = duration,
                        isMinimized = isPlaybackMinimized,
                        onToggleMinimize = onToggleMinimize,
                        onPlayPauseClick = onPlayPauseClick,
                        onNextClick = onNextClick,
                        onPreviousClick = onPreviousClick,
                        onSeek = onSeek,
                        onStop = onStop
                    )
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0xFF1E1E1E), Color(0xFF121212))))
                    .padding(padding)
            ) {
                if (selectedPlaylistName == "VIEW_ALL_PLAYLISTS") {
                    val sortedPlaylistNames = playlists.sorted()
                    var playlistToDelete by remember { mutableStateOf<String?>(null) }
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(sortedPlaylistNames) { _, playlistName ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f).clickable { onPlaylistSelect(playlistName) },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier.size(50.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colors.primary.copy(alpha = 0.2f)),
                                        contentAlignment = Alignment.Center
                                    ) { Icon(Icons.Default.QueueMusic, null, tint = MaterialTheme.colors.primary) }
                                    Spacer(Modifier.width(16.dp))
                                    Text(playlistName, style = MaterialTheme.typography.h6, color = Color.White)
                                }
                                IconButton(onClick = { playlistToDelete = playlistName }) {
                                    Icon(Icons.Default.Delete, "Excluir", tint = Color.Red.copy(alpha = 0.6f))
                                }
                            }
                            Divider(color = Color.White.copy(alpha = 0.05f), thickness = 1.dp)
                        }
                    }
                    playlistToDelete?.let { name ->
                        AlertDialog(
                            onDismissRequest = { playlistToDelete = null },
                            title = { Text("Excluir Playlist") },
                            text = { Text("Quer deletar mesmo meu chapa?") },
                            confirmButton = {
                                Button(
                                    onClick = { onDeletePlaylist(name); playlistToDelete = null },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color.Red)
                                ) { Text("Deletar", color = Color.White) }
                            },
                            dismissButton = {
                                TextButton(onClick = { playlistToDelete = null }) { Text("Cancelar", color = Color.Gray) }
                            }
                        )
                    }
                } else if (songs.isEmpty() && selectedPlaylistName != null) {
                    Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                        Text("Esta playlist está vazia", style = MaterialTheme.typography.h6, color = Color.Gray)
                        TextButton(onClick = { onPlaylistSelect("VIEW_ALL_PLAYLISTS") }) {
                            Text("Voltar para Playlists", color = MaterialTheme.colors.primary)
                        }
                    }
                } else if (songs.isEmpty()) {
                    Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                        Text("Nenhuma música encontrada", style = MaterialTheme.typography.h6, color = Color.Gray)
                    }
                } else {
                    Row(Modifier.fillMaxSize()) {
                        LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
                            itemsIndexed(items = songs, key = { _, song -> song.id }) { index, song ->
                                SongItem(
                                    song = song,
                                    isSelected = index == currentSongIndex,
                                    onClick = { onSongSelect(index) },
                                    onLongClick = { onSongLongClick(song) },
                                    playlists = playlists,
                                    currentPlaylist = selectedPlaylistName,
                                    onAddToPlaylist = { onAddToPlaylist(song, it) },
                                    onRemoveFromPlaylist = { onRemoveFromPlaylist(song, selectedPlaylistName!!) }
                                )
                                if (index < songs.size - 1) {
                                    Divider(Modifier.padding(horizontal = 16.dp), Color.White.copy(alpha = 0.05f), 1.dp)
                                }
                            }
                        }
                        if (selectedPlaylistName == null) AlphabetScroller(onAlphabetClick)
                    }
                }
            }
        }
    }
}

@Composable
fun AlphabetScroller(onAlphabetClick: (String) -> Unit) {
    val alphabet = remember { ('A'..'Z').map { it.toString() } + "#" }
    var selectedLetter by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxHeight().width(32.dp).padding(vertical = 8.dp),
        Arrangement.SpaceEvenly,
        Alignment.CenterHorizontally
    ) {
        alphabet.forEach { letter ->
            val isSelected = selectedLetter == letter
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(if (isSelected) MaterialTheme.colors.primary else Color.Transparent)
                    .clickable { selectedLetter = letter; onAlphabetClick(letter) },
                Alignment.Center
            ) {
                Text(letter, style = MaterialTheme.typography.caption, color = if (isSelected) Color.Black else MaterialTheme.colors.primary.copy(alpha = 0.7f), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun SearchBar(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    TextField(
        value = query, onValueChange = onQueryChange, modifier = modifier.heightIn(min = 56.dp).clip(RoundedCornerShape(28.dp)),
        placeholder = { Text("Pesquisar músicas ou artistas...", color = Color.Gray) },
        leadingIcon = { Icon(Icons.Default.Search, null, tint = Color.Gray) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Default.Close, "Limpar", tint = Color.Gray) } },
        colors = TextFieldDefaults.textFieldColors(backgroundColor = Color.White.copy(alpha = 0.05f), focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent, cursorColor = MaterialTheme.colors.primary, textColor = Color.White),
        singleLine = true
    )
}

@Composable
fun EditSongDialog(song: Song, onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var title by remember { mutableStateOf(song.title) }
    var artist by remember { mutableStateOf(song.artist) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Editar Informações") },
        text = {
            Column {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Título") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = artist, onValueChange = { artist = it }, label = { Text("Artista") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { Button(onClick = { onConfirm(title, artist) }) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SongItem(song: Song, isSelected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, playlists: List<String>, currentPlaylist: String? = null, onAddToPlaylist: (String) -> Unit, onRemoveFromPlaylist: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    var showPlaylistMenu by remember { mutableStateOf(false) }
    val albumArtUri = remember(song.albumId) { ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), song.albumId) }
    Row(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).background(if (isSelected) Color.White.copy(alpha = 0.1f) else Color.Transparent).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(50.dp).clip(RoundedCornerShape(8.dp)).background(Color.DarkGray), Alignment.Center) {
            AsyncImage(albumArtUri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, error = rememberVectorPainter(Icons.Default.PlayArrow), placeholder = rememberVectorPainter(Icons.Default.PlayArrow))
            if (isSelected) Box(Modifier.fillMaxSize().background(MaterialTheme.colors.primary.copy(alpha = 0.3f)), Alignment.Center) { Icon(Icons.Default.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(24.dp)) }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.subtitle1, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, color = if (isSelected) MaterialTheme.colors.primary else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, style = MaterialTheme.typography.body2, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "Opções", tint = Color.White.copy(alpha = 0.6f)) }
            DropdownMenu(showMenu, { showMenu = false }, modifier = Modifier.background(Color(0xFF2C2C2C))) {
                DropdownMenuItem({ showMenu = false; onLongClick() }) { Text("Editar", color = Color.White) }
                DropdownMenuItem({ showMenu = false; showPlaylistMenu = true }) { Text("Adicionar na playlist", color = Color.White) }
                if (currentPlaylist != null && currentPlaylist != "VIEW_ALL_PLAYLISTS") DropdownMenuItem({ showMenu = false; onRemoveFromPlaylist() }) { Text("Remover desta playlist", color = Color.Red) }
            }
            DropdownMenu(showPlaylistMenu, { showPlaylistMenu = false }, modifier = Modifier.background(Color(0xFF2C2C2C))) {
                if (playlists.isEmpty()) DropdownMenuItem({ showPlaylistMenu = false }) { Text("Nenhuma playlist", color = Color.Gray) }
                playlists.forEach { playlist -> DropdownMenuItem({ onAddToPlaylist(playlist); showPlaylistMenu = false }) { Text(playlist, color = Color.White) } }
            }
        }
    }
}

@Composable
fun PlaybackControls(song: Song, isPlaying: Boolean, currentPosition: Float, duration: Float, isMinimized: Boolean, onToggleMinimize: () -> Unit, onPlayPauseClick: () -> Unit, onNextClick: () -> Unit, onPreviousClick: () -> Unit, onSeek: (Float) -> Unit, onStop: () -> Unit) {
    var sliderPosition by remember(currentPosition) { mutableStateOf(currentPosition) }
    var isDragging by remember { mutableStateOf(false) }
    Surface(elevation = 16.dp, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), color = Color(0xFF1E1E1E)) {
        Column(Modifier.fillMaxWidth().padding(if (isMinimized) 8.dp else 20.dp)) {
            if (isMinimized) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onToggleMinimize() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val albumArtUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), song.albumId)
                    AsyncImage(albumArtUri, null, Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Crop, error = rememberVectorPainter(Icons.Default.PlayArrow))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text(song.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.body1, color = Color.White) }
                    IconButton(onClick = onPlayPauseClick) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = MaterialTheme.colors.primary) }
                    IconButton(onClick = onToggleMinimize) { Icon(Icons.Default.KeyboardArrowUp, "Expandir", tint = Color.Gray) }
                    IconButton(onClick = onStop) { Icon(Icons.Default.Close, "Fechar", tint = Color.Gray) }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val albumArtUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), song.albumId)
                    Box(Modifier.size(60.dp).clip(RoundedCornerShape(12.dp)).background(Color.DarkGray)) { AsyncImage(albumArtUri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(song.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.h6, color = Color.White)
                        Text(song.artist, style = MaterialTheme.typography.body2, color = MaterialTheme.colors.primary)
                    }
                    IconButton(onClick = onToggleMinimize) { Icon(Icons.Default.KeyboardArrowDown, "Minimizar", tint = Color.Gray) }
                    IconButton(onClick = onStop) { Icon(Icons.Default.Close, "Fechar", tint = Color.Gray) }
                }
                Spacer(Modifier.height(8.dp))
                Slider(
                    value = if (isDragging) sliderPosition else (if (duration > 0) currentPosition.coerceIn(0f, duration) else 0f),
                    onValueChange = { isDragging = true; sliderPosition = it },
                    onValueChangeFinished = { onSeek(sliderPosition); isDragging = false },
                    valueRange = 0f..(if (duration > 0) duration else 1f),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colors.primary,
                        activeTrackColor = MaterialTheme.colors.primary,
                        inactiveTrackColor = Color.Gray.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
                    IconButton(onClick = onPreviousClick) { Icon(Icons.Default.SkipPrevious, "Anterior", tint = Color.White, modifier = Modifier.size(32.dp)) }
                    FloatingActionButton(onPlayPauseClick, backgroundColor = MaterialTheme.colors.primary, contentColor = Color.Black) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (isPlaying) "Pausar" else "Reproduzir", modifier = Modifier.size(36.dp)) }
                    IconButton(onClick = onNextClick) { Icon(Icons.Default.SkipNext, "Próxima", tint = Color.White, modifier = Modifier.size(32.dp)) }
                }
            }
        }
    }
}
