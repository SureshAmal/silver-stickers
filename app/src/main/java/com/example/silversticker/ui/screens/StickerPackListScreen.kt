package com.example.silversticker.ui.screens

import androidx.activity.compose.BackHandler

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.silversticker.models.StickerPack
import com.example.silversticker.ui.components.StaticStickerImage
import com.example.silversticker.ui.theme.SilverMotion
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.window.Dialog
import android.net.Uri
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class PackViewMode { List, Grid }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerPackListScreen(
    stickerPacks: List<StickerPack>,
    targetPackage: String,
    onTargetPackageChange: (String) -> Unit,
    onCreatePackClick: () -> Unit,
    onPackClick: (StickerPack) -> Unit,
    onRefresh: () -> Unit = {},
    onImportPacksSelected: (List<Uri>) -> Unit,
    onDeletePacks: (List<String>) -> Unit,
    onSharePacks: (List<String>) -> Unit,
    isImporting: Boolean = false,
    importStatus: String = ""
) {
    val coroutineScope = rememberCoroutineScope()
    var selectedPackIds by remember { mutableStateOf(setOf<String>()) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var deletingPackIds by remember { mutableStateOf(setOf<String>()) }
    var viewMode by rememberSaveable { mutableStateOf(PackViewMode.List) }

    BackHandler(enabled = isSelectionMode) {
        selectedPackIds = emptySet()
        isSelectionMode = false
    }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            onImportPacksSelected(uris)
        }
    }

    LaunchedEffect(Unit) {
        android.util.Log.d("StickerPackListScreen", "Screen shown, refreshing. Current count: ${stickerPacks.size}")
        onRefresh()
    }

    if (isImporting) {
        Dialog(onDismissRequest = {}) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Text(
                        text = if (importStatus.isNotBlank()) importStatus else "Importing sticker packs...",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Text(
                        text = "Extracting stickers and metadata...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    title = { Text("${selectedPackIds.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = {
                            selectedPackIds = emptySet()
                            isSelectionMode = false
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            if (selectedPackIds.isNotEmpty()) {
                                onSharePacks(selectedPackIds.toList())
                            }
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "Share selected packs")
                        }
                        IconButton(onClick = {
                            if (selectedPackIds.isNotEmpty()) {
                                coroutineScope.launch {
                                    deletingPackIds = deletingPackIds + selectedPackIds
                                    delay(380) // Smooth finish for collapse animation
                                    onDeletePacks(selectedPackIds.toList())
                                    selectedPackIds = emptySet()
                                    isSelectionMode = false
                                    deletingPackIds = emptySet()
                                }
                            }
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete selected packs")
                        }
                    }
                )
            } else {
                LargeTopAppBar(
                    title = { Text("Silver Sticker") },
                    actions = {
                        IconButton(
                            onClick = {
                                viewMode = if (viewMode == PackViewMode.List) PackViewMode.Grid else PackViewMode.List
                            }
                        ) {
                            Icon(
                                if (viewMode == PackViewMode.List) Icons.Default.GridView else Icons.Default.ViewList,
                                contentDescription = if (viewMode == PackViewMode.List) "Show as grid" else "Show as list"
                            )
                        }
                        IconButton(onClick = {
                            importLauncher.launch(
                                arrayOf(
                                    "*/*",
                                    "application/octet-stream",
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "application/vnd.whatsapp.sticker"
                                )
                            )
                        }) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = "Import .wastickers or ZIP"
                            )
                        }
                        var showMenu by remember { mutableStateOf(false) }
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                if (targetPackage == "com.whatsapp") Icons.AutoMirrored.Filled.Chat else Icons.Default.Business,
                                contentDescription = "Target App"
                            )
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("WhatsApp") },
                                onClick = {
                                    onTargetPackageChange("com.whatsapp")
                                    showMenu = false
                                },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("WhatsApp Business") },
                                onClick = {
                                    onTargetPackageChange("com.whatsapp.w4b")
                                    showMenu = false
                                },
                                leadingIcon = { Icon(Icons.Default.Business, contentDescription = null) }
                            )
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!isSelectionMode) {
                ExtendedFloatingActionButton(
                    onClick = onCreatePackClick,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Create Pack") }
                )
            }
        }
    ) { padding ->
        if (stickerPacks.isEmpty()) {
            // Animated empty state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                var visible by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { visible = true }

                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(SilverMotion.standard(320)) + scaleIn(
                        initialScale = 0.92f,
                        animationSpec = SilverMotion.expressiveSpring()
                    )
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "🎨",
                            style = MaterialTheme.typography.displayLarge
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "No sticker packs yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            AnimatedContent(
                targetState = viewMode,
                transitionSpec = {
                    (fadeIn(SilverMotion.standardEnter(SilverMotion.Medium1)) +
                            scaleIn(initialScale = 0.96f, animationSpec = SilverMotion.spatialSpring())) togetherWith
                            (fadeOut(SilverMotion.standardExit(SilverMotion.Short4)) +
                                    scaleOut(targetScale = 0.98f, animationSpec = SilverMotion.standardExit(SilverMotion.Short4)))
                },
                label = "pack_view_mode",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                val onItemClick: (StickerPack, Boolean) -> Unit = { pack, isSelected ->
                    if (isSelectionMode) {
                        selectedPackIds = if (isSelected) selectedPackIds - pack.identifier else selectedPackIds + pack.identifier
                        if (selectedPackIds.isEmpty()) isSelectionMode = false
                    } else {
                        onPackClick(pack)
                    }
                }
                val onItemLongClick: (StickerPack) -> Unit = { pack ->
                    if (!isSelectionMode) {
                        isSelectionMode = true
                        selectedPackIds = setOf(pack.identifier)
                    }
                }

                if (it == PackViewMode.List) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(stickerPacks, key = { pack -> pack.identifier }) { pack ->
                            val isSelected = selectedPackIds.contains(pack.identifier)
                            StickerPackItem(
                                pack = pack,
                                modifier = Modifier.animateItem(),
                                compact = false,
                                isSelected = isSelected,
                                isSelectionMode = isSelectionMode,
                                isDeleting = deletingPackIds.contains(pack.identifier),
                                onClick = { onItemClick(pack, isSelected) },
                                onLongClick = { onItemLongClick(pack) }
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(156.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        gridItems(stickerPacks, key = { pack -> pack.identifier }) { pack ->
                            val isSelected = selectedPackIds.contains(pack.identifier)
                            StickerPackItem(
                                pack = pack,
                                modifier = Modifier.animateItem(),
                                compact = true,
                                isSelected = isSelected,
                                isSelectionMode = isSelectionMode,
                                isDeleting = deletingPackIds.contains(pack.identifier),
                                onClick = { onItemClick(pack, isSelected) },
                                onLongClick = { onItemLongClick(pack) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StickerPackItem(
    pack: StickerPack,
    modifier: Modifier = Modifier,
    compact: Boolean,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    isDeleting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current

    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongClick by rememberUpdatedState(onLongClick)

    // Press animation state
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.965f
            isSelected -> 0.985f
            else -> 1.0f
        },
        animationSpec = SilverMotion.pressSpring(),
        label = "pack_scale"
    )
    val trayScale by animateFloatAsState(
        targetValue = if (isPressed) 1.08f else 1f,
        animationSpec = SilverMotion.pressSpring(),
        label = "pack_tray_scale"
    )
    val elevation by animateFloatAsState(
        targetValue = when {
            isSelected -> 5f
            isPressed -> 8f
            else -> 1f
        },
        animationSpec = SilverMotion.pressSpring(),
        label = "pack_elevation"
    )
    val cornerRadius by animateDpAsState(
        targetValue = when {
            isPressed -> 28.dp
            isSelected -> 32.dp
            else -> 20.dp
        },
        animationSpec = SilverMotion.pressSpring(),
        label = "pack_shape"
    )
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        animationSpec = SilverMotion.standard(220),
        label = "pack_container"
    )

    AnimatedVisibility(
        visible = !isDeleting,
        enter = fadeIn(SilverMotion.standardEnter(SilverMotion.Short4)) +
                scaleIn(initialScale = 0.96f, animationSpec = SilverMotion.spatialSpring()),
        exit = fadeOut(SilverMotion.emphasizedExit(SilverMotion.Short4)) +
                slideOutHorizontally(
                    targetOffsetX = { it / 3 },
                    animationSpec = SilverMotion.emphasizedExit(SilverMotion.Short4)
                ) +
                scaleOut(
                    targetScale = 0.86f,
                    animationSpec = SilverMotion.emphasizedExit(SilverMotion.Short4)
                ) +
                shrinkVertically(animationSpec = SilverMotion.emphasizedExit(SilverMotion.Medium1))
    ) {
        ElevatedCard(
            modifier = modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            tryAwaitRelease()
                            isPressed = false
                        },
                        onTap = { currentOnClick() },
                        onLongPress = { currentOnLongClick() }
                    )
                },
            colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
            shape = RoundedCornerShape(cornerRadius),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = elevation.dp)
        ) {
            val trayFile = pack.getTrayInternalFile(context)
            if (compact) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        StaticStickerImage(
                            file = trayFile,
                            contentDescription = null,
                            modifier = Modifier
                                .size(88.dp)
                                .graphicsLayer {
                                    scaleX = trayScale
                                    scaleY = trayScale
                                }
                                .clip(RoundedCornerShape(24.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentScale = ContentScale.Fit,
                            targetSizePx = 120
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(text = pack.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(text = pack.publisher, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        Text(
                            text = "${pack.stickers.size} stickers",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    PackSelectionBadge(
                        visible = isSelectionMode,
                        selected = isSelected,
                        modifier = Modifier.align(Alignment.TopEnd)
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StaticStickerImage(
                        file = trayFile,
                        contentDescription = null,
                        modifier = Modifier
                            .size(64.dp)
                            .graphicsLayer {
                                scaleX = trayScale
                                scaleY = trayScale
                            }
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Fit,
                        targetSizePx = 96
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = pack.name, style = MaterialTheme.typography.titleLarge)
                        Text(text = pack.publisher, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "${pack.stickers.size} stickers",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    PackSelectionBadge(visible = isSelectionMode, selected = isSelected)
                }
            }
        }
    }
}

@Composable
private fun PackSelectionBadge(
    visible: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(SilverMotion.standard(140)) + scaleIn(
            initialScale = 0.7f,
            animationSpec = SilverMotion.pressSpring()
        ),
        exit = fadeOut(SilverMotion.standard(100)) + scaleOut(
            targetScale = 0.7f,
            animationSpec = SilverMotion.quickSpring()
        ),
        modifier = modifier
    ) {
        Checkbox(
            checked = selected,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(
                checkedColor = MaterialTheme.colorScheme.primary,
                uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }
}
