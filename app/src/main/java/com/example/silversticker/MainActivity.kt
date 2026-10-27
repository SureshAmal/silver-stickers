package com.example.silversticker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.silversticker.ui.StickerViewModel
import com.example.silversticker.ui.screens.CreateStickerPackScreen
import com.example.silversticker.ui.screens.StickerDetailsScreen
import com.example.silversticker.ui.screens.StickerPackListScreen
import com.example.silversticker.ui.theme.SilverMotion
import com.example.silversticker.ui.theme.SilverStickerTheme
import com.example.silversticker.utils.StickerStorage
import com.example.silversticker.utils.WhatsAppUtil

import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import android.os.Build
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var pendingImportUris by mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)
        
        // Configure Coil globally to support decoding animated GIFs and WebPs
        val imageLoader = ImageLoader.Builder(applicationContext)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
        coil.Coil.setImageLoader(imageLoader)

        StickerStorage.init(applicationContext)
        enableEdgeToEdge()
        setContent {
            SilverStickerTheme {
                val navController = rememberNavController()
                val viewModel: StickerViewModel = viewModel()
                val stickerPacks by viewModel.stickerPacks.collectAsState()
                val targetPackage by viewModel.targetPackage.collectAsState()
                val context = LocalContext.current
                val coroutineScope = rememberCoroutineScope()
                var isAddingToWhatsApp by remember { mutableStateOf(false) }
                var lastWhatsAppLaunchAt by remember { mutableLongStateOf(0L) }
                var isImportingPacks by remember { mutableStateOf(false) }
                var importStatusText by remember { mutableStateOf("") }

                // Automatically import pending files when opened from external app
                LaunchedEffect(pendingImportUris) {
                    val uris = pendingImportUris
                    if (uris.isNotEmpty()) {
                        isImportingPacks = true
                        importStatusText = "Importing ${uris.size} sticker pack${if (uris.size > 1) "s" else ""}..."
                        coroutineScope.launch(Dispatchers.IO) {
                            val imported = viewModel.importMultipleStickerPacks(applicationContext, uris) { current, total, name ->
                                importStatusText = "Importing $current of $total${if (!name.isNullOrBlank()) ": $name" else ""}..."
                            }
                            withContext(Dispatchers.Main) {
                                isImportingPacks = false
                                if (imported.isNotEmpty()) {
                                    val msg = if (imported.size == 1) {
                                        "Imported '${imported[0].name}' (${imported[0].stickers.size} stickers)!"
                                    } else {
                                        "Imported ${imported.size} of ${uris.size} packs successfully!"
                                    }
                                    android.widget.Toast.makeText(applicationContext, msg, android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    android.widget.Toast.makeText(
                                        applicationContext,
                                        "Failed to import sticker packs. Please verify files are valid .wastickers or ZIP.",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                        pendingImportUris = emptyList()
                    }
                }

                val addToWhatsAppLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    android.util.Log.d("MainActivity", "WhatsApp result: ${result.resultCode}")
                    isAddingToWhatsApp = false
                }

                NavHost(
                    navController = navController,
                    startDestination = "list",
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    composable(
                        "list",
                        enterTransition = {
                            slideIntoContainer(
                                AnimatedContentTransitionScope.SlideDirection.Right,
                                animationSpec = SilverMotion.emphasizedEnter(SilverMotion.Long1)
                            ) + fadeIn(animationSpec = SilverMotion.standardEnter(SilverMotion.Medium1))
                        },
                        exitTransition = {
                            slideOutOfContainer(
                                AnimatedContentTransitionScope.SlideDirection.Left,
                                animationSpec = SilverMotion.emphasizedExit(SilverMotion.Short4)
                            ) + fadeOut(animationSpec = SilverMotion.emphasizedExit(SilverMotion.Short4))
                        },
                        popEnterTransition = {
                            slideIntoContainer(
                                AnimatedContentTransitionScope.SlideDirection.Right,
                                animationSpec = SilverMotion.emphasizedEnter(SilverMotion.Long1)
                            ) + fadeIn(animationSpec = SilverMotion.standardEnter(SilverMotion.Medium1))
                        },
                        popExitTransition = {
                            slideOutOfContainer(
                                AnimatedContentTransitionScope.SlideDirection.Left,
                                animationSpec = SilverMotion.emphasizedExit(SilverMotion.Short4)
                            ) + fadeOut(animationSpec = SilverMotion.emphasizedExit(SilverMotion.Short4))
                        }
                    ) {
                        StickerPackListScreen(
                            stickerPacks = stickerPacks,
                            targetPackage = targetPackage,
                            onTargetPackageChange = { viewModel.setTargetPackage(it) },
                            onCreatePackClick = { navController.navigate("create") },
                            onPackClick = { pack -> 
                                navController.navigate("details/${pack.identifier}") 
                            },
                            onRefresh = { viewModel.refreshPacks() },
                            onImportPacksSelected = { uris ->
                                if (uris.isNotEmpty()) {
                                    isImportingPacks = true
                                    importStatusText = "Importing ${uris.size} sticker pack${if (uris.size > 1) "s" else ""}..."
                                    coroutineScope.launch(Dispatchers.IO) {
                                        val imported = viewModel.importMultipleStickerPacks(context, uris) { current, total, name ->
                                            importStatusText = "Importing $current of $total${if (!name.isNullOrBlank()) ": $name" else ""}..."
                                        }
                                        withContext(Dispatchers.Main) {
                                            isImportingPacks = false
                                            if (imported.isNotEmpty()) {
                                                val msg = if (imported.size == 1) {
                                                    "Imported '${imported[0].name}' (${imported[0].stickers.size} stickers)!"
                                                } else {
                                                    "Imported ${imported.size} of ${uris.size} packs successfully!"
                                                }
                                                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                            } else {
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "Could not import selected files. Ensure they are valid .wastickers or ZIP.",
                                                    android.widget.Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }
                                    }
                                }
                            },
                            isImporting = isImportingPacks,
                            importStatus = importStatusText,
                            onDeletePacks = { ids ->
                                viewModel.removeStickerPacks(ids)
                            },
                            onSharePacks = { ids ->
                                val uris = ArrayList<android.net.Uri>()
                                ids.forEach { id ->
                                    val pack = com.example.silversticker.utils.StickerStorage.getPack(id)
                                    if (pack != null) {
                                        val uri = viewModel.exportStickerPack(context, pack)
                                        if (uri != null) {
                                            uris.add(uri)
                                        }
                                    }
                                }
                                if (uris.isNotEmpty()) {
                                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                                        type = "application/zip"
                                        putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(android.content.Intent.createChooser(shareIntent, "Share Sticker Packs"))
                                } else {
                                    android.widget.Toast.makeText(context, "Failed to export packs for sharing", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                    composable(
                        "create",
                        enterTransition = {
                            slideIntoContainer(
                                AnimatedContentTransitionScope.SlideDirection.Up,
                                animationSpec = SilverMotion.emphasizedEnter(SilverMotion.Long2)
                            ) + fadeIn(animationSpec = SilverMotion.standardEnter(SilverMotion.Medium2))
                        },
                        exitTransition = {
                            slideOutOfContainer(
                                AnimatedContentTransitionScope.SlideDirection.Down,
                                animationSpec = SilverMotion.emphasizedExit(SilverMotion.Short4)
                            ) + fadeOut(animationSpec = SilverMotion.emphasizedExit(SilverMotion.Short4))
                        },
                        popEnterTransition = {
                            slideIntoContainer(
                                AnimatedContentTransitionScope.SlideDirection.Up,
                                animationSpec = SilverMotion.emphasizedEnter(SilverMotion.Long2)
                            ) + fadeIn(animationSpec = SilverMotion.standardEnter(SilverMotion.Medium2))
                        },
                        popExitTransition = {
                            slideOutOfContainer(
                                AnimatedContentTransitionScope.SlideDirection.Down,
                                animationSpec = SilverMotion.emphasizedExit(SilverMotion.Short4)
                            ) + fadeOut(animationSpec = SilverMotion.emphasizedExit(SilverMotion.Short4))
                        }
                    ) {
                        CreateStickerPackScreen(
                            onBackClick = { navController.popBackStack() },
                            onSaveClick = { pack ->
                                viewModel.addStickerPack(pack)
                                navController.popBackStack()
                            }
                        )
                    }
                    composable(
                        "details/{packId}",
                        enterTransition = {
                            slideIntoContainer(
                                AnimatedContentTransitionScope.SlideDirection.Left,
                                animationSpec = SilverMotion.emphasizedEnter(SilverMotion.Long1)
                            ) + fadeIn(animationSpec = SilverMotion.standardEnter(SilverMotion.Medium1))
                        },
                        exitTransition = {
                            slideOutOfContainer(
                                AnimatedContentTransitionScope.SlideDirection.Right,
                                animationSpec = SilverMotion.emphasizedExit(SilverMotion.Short4)
                            ) + fadeOut(animationSpec = SilverMotion.emphasizedExit(SilverMotion.Short4))
                        },
                        popEnterTransition = {
                            slideIntoContainer(
                                AnimatedContentTransitionScope.SlideDirection.Left,
                                animationSpec = SilverMotion.emphasizedEnter(SilverMotion.Long1)
                            ) + fadeIn(animationSpec = SilverMotion.standardEnter(SilverMotion.Medium1))
                        },
                        popExitTransition = {
                            slideOutOfContainer(
                                AnimatedContentTransitionScope.SlideDirection.Right,
                                animationSpec = SilverMotion.emphasizedExit(SilverMotion.Short4)
                            ) + fadeOut(animationSpec = SilverMotion.emphasizedExit(SilverMotion.Short4))
                        }
                    ) { backStackEntry ->
                        val packId = backStackEntry.arguments?.getString("packId")
                        val pack = stickerPacks.find { it.identifier == packId }
                        if (pack != null) {
                            StickerDetailsScreen(
                                pack = pack,
                                isAddingToWhatsApp = isAddingToWhatsApp,
                                onBackClick = { navController.popBackStack() },
                                onAddToWhatsAppClick = {
                                    val now = android.os.SystemClock.elapsedRealtime()
                                    if (!isAddingToWhatsApp && now - lastWhatsAppLaunchAt >= 1500L) {
                                        isAddingToWhatsApp = true
                                        lastWhatsAppLaunchAt = now
                                        val intent = WhatsAppUtil.createAddStickerPackIntent(context, pack, targetPackage)
                                        if (intent != null) {
                                            try {
                                                addToWhatsAppLauncher.launch(intent)
                                            } catch (e: Exception) {
                                                isAddingToWhatsApp = false
                                                android.widget.Toast.makeText(context, "Failed to open WhatsApp", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            isAddingToWhatsApp = false
                                            val isInstalled = WhatsAppUtil.isWhatsAppInstalled(context, targetPackage)
                                            if (!isInstalled) {
                                                val targetName = if (targetPackage.contains("w4b")) "WhatsApp Business" else "WhatsApp"
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "$targetName is not installed on this device. You can validate the pack or share/export as ZIP!",
                                                    android.widget.Toast.LENGTH_LONG
                                                ).show()
                                            } else {
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "Unable to prepare sticker pack for WhatsApp.",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    }
                                },
                                onDeletePackClick = {
                                    viewModel.removeStickerPack(pack.identifier)
                                    navController.popBackStack()
                                },
                                onAddStickers = { newStickers ->
                                    viewModel.addStickersToPack(pack.identifier, newStickers)
                                },
                                onRemoveStickers = { stickers ->
                                    viewModel.removeStickersFromPack(pack.identifier, stickers.map { it.imageFileName })
                                },
                                onUpdatePackDetails = { name, publisher, newTrayUri ->
                                    viewModel.updatePackDetails(pack.identifier, name, publisher, newTrayUri)
                                },
                                onUpdateStickerEmojis = { sticker, emojis, a11y ->
                                    viewModel.updateStickerEmojis(pack.identifier, sticker.imageFileName, emojis, a11y)
                                },
                                onUpdateBatchStickers = { updates ->
                                    viewModel.updateAllStickerMetadata(pack.identifier, updates)
                                },
                                onApplyBulkMetadata = { emojis, a11yPattern ->
                                    viewModel.applyBulkMetadataToAllStickers(pack.identifier, emojis, a11yPattern)
                                },
                                onSharePackClick = {
                                    val zipUri = viewModel.exportStickerPack(context, pack)
                                    if (zipUri != null) {
                                        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "application/zip"
                                            putExtra(android.content.Intent.EXTRA_STREAM, zipUri)
                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(android.content.Intent.createChooser(shareIntent, "Share Sticker Pack"))
                                    } else {
                                        android.widget.Toast.makeText(context, "Failed to export sticker pack", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val uris = mutableListOf<Uri>()

        intent.data?.let { uris.add(it) }

        val singleStream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        }
        singleStream?.let { if (!uris.contains(it)) uris.add(it) }

        val multipleStreams = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        }
        multipleStreams?.forEach { if (!uris.contains(it)) uris.add(it) }

        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { if (!uris.contains(it)) uris.add(it) }
            }
        }

        if (uris.isNotEmpty()) {
            android.util.Log.d("MainActivity", "Received ${uris.size} incoming file intent URIs")
            pendingImportUris = uris
        }
    }
}
