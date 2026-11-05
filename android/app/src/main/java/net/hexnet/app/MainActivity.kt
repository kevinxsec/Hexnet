package net.hexnet.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.*
import kotlinx.coroutines.launch
import net.hexnet.app.core.designsystem.theme.HexBackgroundDark
import net.hexnet.app.core.designsystem.theme.HexnetTheme
import net.hexnet.app.core.model.UserProfile
import net.hexnet.app.data.repository.*
import net.hexnet.app.domain.GenerateHexIdentityUseCase
import net.hexnet.app.feature.*
import net.hexnet.app.navigation.*

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        android.util.Log.d("MainActivity", "BLE permissions granted: $allGranted")
        if (allGranted) {
            val app = application as? HexnetApplication
            val identity = app?.identityManager?.getIdentity()
            if (identity != null) {
                app.startMeshService(identity.hexAddress)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request BLE runtime permissions
        val missing = net.hexnet.app.core.transport.ble.BlePermissionManager.getMissingPermissions(this)
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }

        val app = application as HexnetApplication
        val identityRepo = IdentityRepository(app.preferencesDataStore)
        val messagingRepo = app.messagingRepository
        val contactsRepo = app.contactsRepository
        val meshRepo = app.meshNetworkRepository

        setContent {
            HexnetTheme {
                HexnetAppMain(
                    identityRepository = identityRepo,
                    messagingRepository = messagingRepo,
                    contactsRepository = contactsRepo,
                    meshNetworkRepository = meshRepo,
                    onStartMesh = { hex -> app.startMeshService(hex) },
                    onStopMesh = { app.stopMeshService() }
                )
            }
        }
    }
}

@Composable
fun HexnetAppMain(
    identityRepository: IdentityRepository,
    messagingRepository: MessagingRepository,
    contactsRepository: ContactsRepository,
    meshNetworkRepository: MeshNetworkRepository,
    onStartMesh: (String) -> Unit = {},
    onStopMesh: () -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val userProfile by identityRepository.userProfile.collectAsState(initial = null)
    val peers by contactsRepository.peers.collectAsState(initial = emptyList())
    val conversations by messagingRepository.conversations.collectAsState(initial = emptyList())
    val rooms by meshNetworkRepository.rooms.collectAsState(initial = emptyList())
    val events by meshNetworkRepository.events.collectAsState(initial = emptyList())

    // Automatically trigger mesh foreground service when identity is active
    LaunchedEffect(userProfile?.hexAddress) {
        val hex = userProfile?.hexAddress
        if (hex != null) {
            onStartMesh(hex)
        }
    }

    var currentScreen by remember { mutableStateOf("chats") }
    var activeConversationId by remember { mutableStateOf<String?>(null) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = HexBackgroundDark
    ) {
        if (userProfile == null) {
            OnboardingScreen(
                onComplete = { username, displayName ->
                    val (hexAddr, fingerprint) = GenerateHexIdentityUseCase().execute(username)
                    coroutineScope.launch {
                        identityRepository.saveProfile(
                            UserProfile(
                                username = username,
                                displayName = displayName,
                                hexAddress = hexAddr,
                                keyFingerprint = fingerprint
                            )
                        )
                        onStartMesh(hexAddr)
                    }
                }
            )
        } else if (activeConversationId != null) {
            val convId = activeConversationId!!
            val messages by messagingRepository.getMessages(convId).collectAsState(initial = emptyList())
            val conv = conversations.find { it.id == convId }
            val targetHex = conv?.targetHexAddress ?: ""
            val peer = peers.find { it.hexAddress.equals(targetHex, ignoreCase = true) }
            val convTitle = conv?.title
                ?: rooms.find { it.id == convId }?.name
                ?: "Conversation"

            ConversationScreen(
                conversationId = convId,
                title = convTitle,
                targetHex = targetHex,
                messages = messages,
                isDirectLink = peer?.isDirectNeighbor ?: true,
                isIdentityVerified = peer?.isIdentityVerified ?: false,
                onSendMessage = { text ->
                    messagingRepository.sendMessage(
                        conversationId = convId,
                        senderHex = userProfile!!.hexAddress,
                        senderUsername = userProfile!!.username,
                        content = text
                    )
                },
                onRetryMessage = { msgId ->
                    messagingRepository.retryMessage(msgId)
                },
                onDeleteMessage = { msgId ->
                    messagingRepository.deleteMessage(msgId)
                },
                onBack = { activeConversationId = null }
            )
        } else {
            Scaffold(
                bottomBar = {
                    HexnetBottomBar(
                        currentRoute = currentScreen,
                        onNavigate = { route -> currentScreen = route }
                    )
                }
            ) { paddingValues ->
                Box(modifier = Modifier.padding(paddingValues)) {
                    when (currentScreen) {
                        "chats" -> ChatsScreen(
                            conversations = conversations,
                            rooms = rooms,
                            peers = peers,
                            meshActive = true,
                            connectedPeersCount = peers.count { it.isDirectNeighbor },
                            onOpenConversation = { id -> activeConversationId = id },
                            onStartNewChatWithHex = { hex, title ->
                                val convId = messagingRepository.getOrCreateDirectConversation(hex, title)
                                activeConversationId = convId
                            }
                        )
                        "peers" -> PeersScreen(
                            peers = peers,
                            isScanning = true,
                            onToggleContact = { peerId -> contactsRepository.toggleContact(peerId) },
                            onToggleBlock = { peerId -> contactsRepository.toggleBlock(peerId) },
                            onVerifyIdentity = { peerId -> contactsRepository.verifyIdentity(peerId) },
                            onStartChat = { peer ->
                                val convId = messagingRepository.getOrCreateDirectConversation(peer.hexAddress, peer.username)
                                activeConversationId = convId
                            }
                        )
                        "mesh" -> MeshScreen(
                            peers = peers,
                            events = events,
                            outboundQueueCount = 0,
                            relayQueueCount = 0
                        )
                        "settings" -> SettingsScreen(
                            userProfile = userProfile,
                            meshServiceActive = true,
                            onToggleMeshService = { active ->
                                if (active && userProfile != null) {
                                    onStartMesh(userProfile!!.hexAddress)
                                } else {
                                    onStopMesh()
                                }
                            },
                            onClearIdentity = {
                                coroutineScope.launch {
                                    identityRepository.clearIdentity()
                                    onStopMesh()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
