package com.example.silversticker.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.silversticker.models.Sticker
import com.example.silversticker.models.StickerPack
import com.example.silversticker.ui.components.StaticStickerImage
import com.example.silversticker.ui.theme.SilverMotion
import com.example.silversticker.utils.ImageUtils
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.silversticker.utils.StickerStorage
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import android.util.Log
import java.io.File
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateStickerPackScreen(
    onBackClick: () -> Unit,
    onSaveClick: (StickerPack) -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var publisher by remember { mutableStateOf("") }
    var trayImageFileName by remember { mutableStateOf<String?>(null) }
    var selectedStickers by remember { mutableStateOf<List<Sticker>>(emptyList()) }
    var isSaving by remember { mutableStateOf(false) }

    // Loader states
    var isProcessingStickers by remember { mutableStateOf(false) }
    var processingMessage by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    val permissionToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(permissionToRequest)
    }

    // Tray icon change animation trigger
    var trayAnimTrigger by remember { mutableStateOf(0) }

    val trayPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> 
        uri?.let {
            val baseName = "tray_${System.currentTimeMillis()}"
            val file = ImageUtils.convertToWebPFile(context, it, 96, baseName)
            if (file != null) {
                trayImageFileName = file.name
                trayAnimTrigger++
            }
        }
    }

    val stickerPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val limit = 30 - selectedStickers.size
            if (limit <= 0) {
                android.widget.Toast.makeText(context, "Maximum 30 stickers allowed per pack", android.widget.Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }
            
            val urisToProcess = if (uris.size > limit) {
                android.widget.Toast.makeText(context, "Only ${limit} more stickers can be added. Skipping the rest.", android.widget.Toast.LENGTH_LONG).show()
                uris.take(limit)
            } else {
                uris
            }

            isProcessingStickers = true
            processingMessage = "Preparing stickers..."
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val total = urisToProcess.size
                    val webpStickers = urisToProcess.mapIndexedNotNull { index, uri ->
                        withContext(Dispatchers.Main) {
                            processingMessage = "Converting sticker ${index + 1} of $total..."
                        }
                        val baseName = "sticker_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
                        ImageUtils.convertToWebP(context, uri, 512, baseName)?.let { result ->
                            Sticker(result.file.name, isAnimated = result.isAnimated)
                        }
                    }
                    if (webpStickers.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            selectedStickers = selectedStickers + webpStickers
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CreateStickerPackScreen", "Error processing stickers: ${e.message}", e)
                } finally {
                    withContext(Dispatchers.Main) {
                        isProcessingStickers = false
                        processingMessage = ""
                    }
                }
            }
        }
    }

    // Material 3 Loader overlay for sticker generation/conversion
    if (isProcessingStickers) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = {}
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .width(320.dp)
                    .wrapContentHeight()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(
                        strokeWidth = 4.dp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = "Generating Stickers",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = processingMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create Sticker Pack") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val isValid = name.isNotBlank() && 
                                publisher.isNotBlank() && 
                                trayImageFileName != null && 
                                selectedStickers.size >= 3 && 
                                selectedStickers.size <= 30 &&
                                !isSaving
                    
                    IconButton(
                        onClick = {
                            if (isValid) {
                                isSaving = true
                                val cleanId = UUID.randomUUID().toString().replace("-", "").lowercase()
                                onSaveClick(
                                    StickerPack(
                                        identifier = cleanId,
                                        name = name,
                                        publisher = publisher,
                                        trayImageFileName = trayImageFileName!!,
                                        stickers = selectedStickers
                                    )
                                )
                            }
                        },
                        enabled = isValid
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Check, contentDescription = "Save")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Animated text fields entry
            var fieldsVisible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { fieldsVisible = true }

            AnimatedVisibility(
                visible = fieldsVisible,
                enter = fadeIn(SilverMotion.standard(260)) + slideInVertically(
                    initialOffsetY = { -it / 5 },
                    animationSpec = SilverMotion.expressiveSpring()
                )
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Pack Name") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSaving,
                        shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = publisher,
                        onValueChange = { publisher = it },
                        label = { Text("Publisher") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSaving,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            Text("Tray Icon (96×96)", style = MaterialTheme.typography.titleMedium)
            
            // Visual Tray Icon selection box with clean, bright preview and floating edit badge
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .align(Alignment.CenterHorizontally)
                    .clickable(enabled = !isSaving) { trayPicker.launch("image/*") }
            ) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.size(80.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (trayImageFileName != null) {
                            val trayScale = remember { Animatable(0.5f) }
                            LaunchedEffect(trayAnimTrigger) {
                                trayScale.snapTo(0.5f)
                                trayScale.animateTo(
                                    1f,
                                    animationSpec = SilverMotion.expressiveSpring()
                                )
                            }
                            AsyncImage(
                                model = File(StickerStorage.getStickersDir(context), trayImageFileName!!),
                                contentDescription = "Tray Icon",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp)
                                    .scale(trayScale.value),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            Text("Select", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // Floating Pencil Edit Badge at corner
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Change Tray Icon",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Stickers (${selectedStickers.size})", style = MaterialTheme.typography.titleMedium)
                    if (selectedStickers.size < 3) {
                        Text("Min 3 required", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    } else if (selectedStickers.size > 30) {
                        Text("Max 30 allowed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                Button(
                    onClick = { stickerPicker.launch("image/*") },
                    enabled = !isSaving
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add Images")
                }
            }

            // Sticker grid with staggered cascade animation
            LazyVerticalGrid(
                columns = GridCells.Adaptive(80.dp),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(
                    selectedStickers,
                    key = { _, sticker -> sticker.imageFileName }
                ) { index, sticker ->
                    CreateStickerGridItem(
                        sticker = sticker,
                        onUpdate = { updatedSticker ->
                            val newList = selectedStickers.toMutableList()
                            val i = newList.indexOf(sticker)
                            if (i != -1) {
                                newList[i] = updatedSticker
                                selectedStickers = newList
                            }
                        },
                        onRemove = {
                            selectedStickers = selectedStickers - sticker
                        }
                    )
                }
            }
        }
    }
}

/**
 * Individual sticker item in the create screen grid.
 */
@Composable
private fun CreateStickerGridItem(
    sticker: Sticker,
    onUpdate: (Sticker) -> Unit,
    onRemove: () -> Unit
) {
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .aspectRatio(1f)
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxSize()
        ) {
            StaticStickerImage(
                file = sticker.getInternalFile(context),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp),
                targetSizePx = 160
            )
        }
        
        // Display Emojis
        if (sticker.emojis.isNotEmpty()) {
            val emojiText = sticker.emojis.joinToString("")
            if (emojiText.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = emojiText,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }
        }
        
        // Remove Button
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error)
                .clickable { onRemove() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove Sticker",
                tint = MaterialTheme.colorScheme.onError,
                modifier = Modifier.size(16.dp)
            )
        }
            
            // Emoji Edit Button
            var showEmojiDialog by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .clickable { showEmojiDialog = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Face,
                    contentDescription = "Add Emojis",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(16.dp)
                )
            }
            
            if (showEmojiDialog) {
                var emojiInput by remember { mutableStateOf(sticker.emojis.joinToString("")) }
                Dialog(
                    onDismissRequest = { showEmojiDialog = false },
                    properties = DialogProperties(decorFitsSystemWindows = false)
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .imePadding(),
                        shape = RoundedCornerShape(28.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Text("Search Emojis", style = MaterialTheme.typography.titleLarge)
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedTextField(
                                value = emojiInput,
                                onValueChange = { emojiInput = ImageUtils.filterEmojiInput(it) },
                                label = { Text("Up to 3 Emojis") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { showEmojiDialog = false }) { Text("Cancel") }
                                Spacer(modifier = Modifier.width(8.dp))
                                TextButton(onClick = {
                                    val newEmojis = ImageUtils.splitIntoEmojis(emojiInput)
                                    onUpdate(sticker.copy(emojis = newEmojis))
                                    showEmojiDialog = false
                                }) { Text("Save") }
                            }
                        }
                    }
            }
        }
    }
}
