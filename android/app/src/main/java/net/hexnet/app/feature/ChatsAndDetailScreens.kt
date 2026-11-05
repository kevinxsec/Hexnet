package net.hexnet.app.feature

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.hexnet.app.core.designsystem.components.*
import net.hexnet.app.core.designsystem.theme.*
import net.hexnet.app.core.model.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Screen: Main Messages Home Screen
 * Clean, premium, spacious Android messenger (Samsung One UI / Pixel / iOS visual polish).
 * Pure AMOLED black, edge-to-edge conversation rows, subtle dividers, zero technical clutter.
 */
@Composable
fun ChatsScreen(
    conversations: List<Conversation>,
    rooms: List<ChatRoom>,
    peers: List<Peer> = emptyList(),
    meshActive: Boolean = true,
    connectedPeersCount: Int = 0,
    onOpenConversation: (String) -> Unit,
    onStartNewChatWithHex: (targetHex: String, title: String) -> Unit = { _, _ -> }
) {
    var searchQuery by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf("direct") } // "direct" | "rooms"
    var showNewChatDialog by remember { mutableStateOf(false) }

    val filteredConversations = remember(conversations, searchQuery) {
        if (searchQuery.isBlank()) conversations
        else conversations.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.lastMessageText.contains(searchQuery, ignoreCase = true) ||
            it.targetHexAddress.contains(searchQuery, ignoreCase = true)
        }
    }

    val filteredRooms = remember(rooms, searchQuery) {
        if (searchQuery.isBlank()) rooms
        else rooms.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.topic.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        containerColor = HexBackgroundDark,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showNewChatDialog = true },
                containerColor = HexPeriwinkle,
                contentColor = Color(0xFF09090B),
                shape = CircleShape,
                modifier = Modifier
                    .padding(bottom = 72.dp) // Avoid overlap with floating bottom nav
                    .size(54.dp),
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp, pressedElevation = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "New Conversation",
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // One UI Expansive Header Area
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                // Connection Indicator + Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (meshActive) HexMint else HexTextMutedDark)
                        )
                        Text(
                            text = if (connectedPeersCount > 0) "$connectedPeersCount peers connected" else "Mesh active",
                            fontFamily = HexnetFontFamily,
                            fontSize = 11.sp,
                            color = HexTextSecondaryDark
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(HexSurfaceSubtleDark)
                            .border(1.dp, HexBorderDark, RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "OFFLINE P2P",
                            fontFamily = HexnetFontFamily,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = HexPeriwinkle
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Large Expressive "Messages" Title
                Text(
                    text = "Messages",
                    fontFamily = HexnetFontFamily,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = HexTextPrimaryDark,
                    letterSpacing = (-0.6).sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Translucent Liquid Glass Search Control
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .border(1.dp, HexGlassBorder, RoundedCornerShape(22.dp)),
                    color = HexSurfaceSubtleDark
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = HexTextMutedDark,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = {
                                Text(
                                    text = "Search conversations or channels...",
                                    fontFamily = HexnetFontFamily,
                                    fontSize = 13.sp,
                                    color = HexTextMutedDark
                                )
                            },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedTextColor = HexTextPrimaryDark,
                                unfocusedTextColor = HexTextPrimaryDark,
                                cursorColor = HexPeriwinkle
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        if (searchQuery.isNotBlank()) {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear",
                                    tint = HexTextSecondaryDark,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Minimal Direct / Channels Selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Direct (${conversations.size})",
                        fontFamily = HexnetFontFamily,
                        fontSize = 13.sp,
                        fontWeight = if (activeTab == "direct") FontWeight.Bold else FontWeight.Normal,
                        color = if (activeTab == "direct") HexPeriwinkle else HexTextMutedDark,
                        modifier = Modifier
                            .clickable { activeTab = "direct" }
                            .padding(vertical = 4.dp)
                    )
                    Text(
                        text = "Channels (${rooms.size})",
                        fontFamily = HexnetFontFamily,
                        fontSize = 13.sp,
                        fontWeight = if (activeTab == "rooms") FontWeight.Bold else FontWeight.Normal,
                        color = if (activeTab == "rooms") HexPeriwinkle else HexTextMutedDark,
                        modifier = Modifier
                            .clickable { activeTab = "rooms" }
                            .padding(vertical = 4.dp)
                    )
                }
            }

            HorizontalDivider(color = HexBorderSubtleDark, thickness = 0.5.dp)

            // Spacious Conversation List (Dominating available screen space)
            if (activeTab == "direct") {
                if (filteredConversations.isEmpty()) {
                    HexEmptyState(
                        icon = Icons.Outlined.ChatBubbleOutline,
                        title = if (searchQuery.isNotBlank()) "No matches found" else "No conversations yet",
                        subtitle = if (searchQuery.isNotBlank()) "Try a different search query" else "Nearby mesh peers will appear automatically. Tap the compose button to start a chat with any peer address.",
                        actionLabel = "Start Chat",
                        onAction = { showNewChatDialog = true }
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 96.dp)
                    ) {
                        items(filteredConversations, key = { it.id }) { conversation ->
                            ConversationListRow(
                                conversation = conversation,
                                onClick = { onOpenConversation(conversation.id) }
                            )
                            HorizontalDivider(
                                color = HexBorderSubtleDark,
                                thickness = 0.5.dp,
                                modifier = Modifier.padding(start = 76.dp)
                            )
                        }
                    }
                }
            } else {
                if (filteredRooms.isEmpty()) {
                    HexEmptyState(
                        icon = Icons.Outlined.Tag,
                        title = "No channels found",
                        subtitle = "Public mesh broadcast channels enable offline group communication with nearby peers."
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 96.dp)
                    ) {
                        items(filteredRooms, key = { it.id }) { room ->
                            RoomListRow(
                                room = room,
                                onClick = { onOpenConversation(room.id) }
                            )
                            HorizontalDivider(
                                color = HexBorderSubtleDark,
                                thickness = 0.5.dp,
                                modifier = Modifier.padding(start = 76.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showNewChatDialog) {
        NewConversationDialog(
            peers = peers,
            onDismiss = { showNewChatDialog = false },
            onStartChat = { hex, title ->
                showNewChatDialog = false
                onStartNewChatWithHex(hex, title)
            }
        )
    }
}

/**
 * Clean edge-to-edge conversation row.
 * Contains only: Avatar, Contact name, One-line message preview, Small timestamp, Subtle unread indicator.
 * Zero RSSI, RTT, or technical telemetry clutter.
 */
@Composable
private fun ConversationListRow(
    conversation: Conversation,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HexAvatar(
            name = conversation.title,
            hexAddress = conversation.targetHexAddress,
            size = 46,
            isDirect = conversation.isDirectNeighbor
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = conversation.title,
                    fontFamily = HexnetFontFamily,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = HexTextPrimaryDark,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false)
                )

                Text(
                    text = formatRelativeTime(conversation.lastMessageTimestamp),
                    fontFamily = HexnetFontFamily,
                    fontSize = 11.sp,
                    color = HexTextMutedDark
                )
            }

            Spacer(modifier = Modifier.height(3.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = conversation.lastMessageText.ifBlank { "No messages yet" },
                    fontFamily = HexnetFontFamily,
                    fontSize = 13.sp,
                    color = if (conversation.unreadCount > 0) HexTextPrimaryDark else HexTextSecondaryDark,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )

                if (conversation.unreadCount > 0) {
                    Box(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(HexPeriwinkle),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = conversation.unreadCount.toString(),
                            color = Color(0xFF09090B),
                            fontFamily = HexnetFontFamily,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RoomListRow(
    room: ChatRoom,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(HexSurfaceSubtleDark)
                .border(1.dp, HexBorderDark, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Tag,
                contentDescription = null,
                tint = HexLavender,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = room.name,
                    fontFamily = HexnetFontFamily,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = HexTextPrimaryDark
                )

                Text(
                    text = "${room.participantCount} peers",
                    fontFamily = HexnetFontFamily,
                    fontSize = 11.sp,
                    color = HexTextMutedDark
                )
            }

            Spacer(modifier = Modifier.height(3.dp))

            Text(
                text = room.topic.ifBlank { "Public mesh broadcast channel" },
                fontFamily = HexnetFontFamily,
                fontSize = 13.sp,
                color = HexTextSecondaryDark,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Screen: Direct Conversation View
 * Premium Android messaging experience.
 * Minimal contact header, clean message bubbles with proper grouping and asymmetric corners,
 * readable timestamps, small encryption indicator, and floating translucent composer.
 */
@Composable
fun ConversationScreen(
    conversationId: String,
    title: String,
    targetHex: String = "",
    messages: List<Message>,
    isDirectLink: Boolean = true,
    isIdentityVerified: Boolean = false,
    onSendMessage: (text: String) -> Unit,
    onRetryMessage: (messageId: String) -> Unit = {},
    onDeleteMessage: (messageId: String) -> Unit = {},
    onBack: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    var showInfoSheet by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Auto-scroll on new messages
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        containerColor = HexBackgroundDark,
        topBar = {
            Surface(
                color = HexBackgroundDark,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back",
                                tint = HexTextPrimaryDark,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        HexAvatar(
                            name = title,
                            hexAddress = targetHex,
                            size = 38,
                            isDirect = isDirectLink,
                            isVerified = isIdentityVerified
                        )

                        Spacer(modifier = Modifier.width(10.dp))

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { showInfoSheet = true }
                        ) {
                            Text(
                                text = title,
                                fontFamily = HexnetFontFamily,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = HexTextPrimaryDark,
                                maxLines = 1
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Encrypted",
                                    tint = HexViolet,
                                    modifier = Modifier.size(11.dp)
                                )
                                Text(
                                    text = if (isDirectLink) "Direct P2P · Noise E2EE" else "Mesh Relay · Noise E2EE",
                                    fontFamily = HexnetFontFamily,
                                    fontSize = 11.sp,
                                    color = HexTextSecondaryDark
                                )
                            }
                        }

                        IconButton(onClick = { showInfoSheet = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = "Details",
                                tint = HexTextSecondaryDark,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    HorizontalDivider(color = HexBorderSubtleDark, thickness = 0.5.dp)
                }
            }
        },
        bottomBar = {
            // Elegant Floating Translucent Composer
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(26.dp))
                        .border(1.dp, HexGlassBorder, RoundedCornerShape(26.dp)),
                    color = Color(0xF2101014),
                    tonalElevation = 0.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            placeholder = {
                                Text(
                                    text = "Message $title...",
                                    fontFamily = HexnetFontFamily,
                                    fontSize = 14.sp,
                                    color = HexTextMutedDark
                                )
                            },
                            maxLines = 4,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedTextColor = HexTextPrimaryDark,
                                unfocusedTextColor = HexTextPrimaryDark,
                                cursorColor = HexPeriwinkle
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    if (inputText.isNotBlank()) {
                                        onSendMessage(inputText.trim())
                                        inputText = ""
                                    }
                                }
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 40.dp, max = 110.dp)
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        IconButton(
                            onClick = {
                                if (inputText.isNotBlank()) {
                                    onSendMessage(inputText.trim())
                                    inputText = ""
                                }
                            },
                            enabled = inputText.isNotBlank(),
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(if (inputText.isNotBlank()) HexPeriwinkle else HexSurfaceElevatedDark)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowUpward,
                                contentDescription = "Send",
                                tint = if (inputText.isNotBlank()) Color(0xFF09090B) else HexTextMutedDark,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                // Tasteful 1-Line Minimal Encryption Notice
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = HexViolet,
                        modifier = Modifier.size(11.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Forward-secure Noise_XX encryption active",
                        fontFamily = HexnetFontFamily,
                        fontSize = 11.sp,
                        color = HexTextMutedDark
                    )
                }
            }

            items(messages, key = { it.id }) { message ->
                MessageBubble(
                    message = message,
                    onRetry = { onRetryMessage(message.id) },
                    onDelete = { onDeleteMessage(message.id) }
                )
            }
        }
    }

    if (showInfoSheet) {
        ConversationDiagnosticsDialog(
            title = title,
            targetHex = targetHex,
            isDirectLink = isDirectLink,
            isIdentityVerified = isIdentityVerified,
            onDismiss = { showInfoSheet = false }
        )
    }
}

/**
 * Beautiful asymmetric message bubbles.
 * Outgoing: Electric Periwinkle with white text.
 * Incoming: Obsidian Tier 2 with crisp light text.
 */
@Composable
private fun MessageBubble(
    message: Message,
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    val isOutgoing = message.isOutgoing
    val isFailed = message.state == MessageDeliveryState.FAILED

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 290.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (isOutgoing) 18.dp else 4.dp,
                        bottomEnd = if (isOutgoing) 4.dp else 18.dp
                    )
                )
                .background(
                    when {
                        isFailed -> HexCoral.copy(alpha = 0.15f)
                        isOutgoing -> Color(0x338795FF) // Restrained Periwinkle tint
                        else -> HexSurfaceSubtleDark
                    }
                )
                .border(
                    width = 1.dp,
                    color = when {
                        isFailed -> HexCoral
                        isOutgoing -> Color(0x668795FF)
                        else -> HexBorderDark
                    },
                    shape = RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (isOutgoing) 18.dp else 4.dp,
                        bottomEnd = if (isOutgoing) 4.dp else 18.dp
                    )
                )
                .padding(horizontal = 14.dp, vertical = 9.dp)
        ) {
            Column {
                if (!isOutgoing && message.senderUsername.isNotBlank()) {
                    Text(
                        text = message.senderUsername,
                        fontFamily = HexnetFontFamily,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = HexLavender,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }

                Text(
                    text = message.content,
                    fontFamily = HexnetFontFamily,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = HexTextPrimaryDark
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestampEpochMs)),
                        fontFamily = HexnetFontFamily,
                        fontSize = 10.sp,
                        color = HexTextMutedDark
                    )

                    if (isOutgoing) {
                        DeliveryStatusIcon(state = message.state)
                    }
                }
            }
        }

        if (isFailed) {
            Text(
                text = "Failed to deliver · Tap to retry",
                fontFamily = HexnetFontFamily,
                fontSize = 11.sp,
                color = HexCoral,
                modifier = Modifier
                    .clickable(onClick = onRetry)
                    .padding(top = 2.dp, end = 4.dp)
            )
        }
    }
}

/**
 * Dedicated diagnostics bottom sheet (technical details live here, not in the chat).
 */
@Composable
private fun ConversationDiagnosticsDialog(
    title: String,
    targetHex: String,
    isDirectLink: Boolean,
    isIdentityVerified: Boolean,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = HexSurfaceSubtleDark,
        titleContentColor = HexTextPrimaryDark,
        textContentColor = HexTextSecondaryDark,
        title = {
            Text(
                text = "Peer Connection Info",
                fontFamily = HexnetFontFamily,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DetailRow("Peer Name", title)
                DetailRow("Node Hex", targetHex.ifEmpty { "Broadcast / Local" })
                DetailRow("Transport", if (isDirectLink) "Direct 1-Hop Bluetooth LE" else "Multi-Hop Mesh Relay")
                DetailRow("Cryptography", "Noise_XX_25519_ChaChaPoly_BLAKE2s")
                DetailRow("Key Status", if (isIdentityVerified) "Ed25519 Verified" else "Unverified")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "Done",
                    fontFamily = HexnetFontFamily,
                    color = HexPeriwinkle,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontFamily = HexnetFontFamily,
            fontSize = 12.sp,
            color = HexTextMutedDark
        )
        Text(
            text = value,
            fontFamily = HexnetFontFamily,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = HexTextPrimaryDark
        )
    }
}

@Composable
fun NewConversationDialog(
    peers: List<Peer>,
    onDismiss: () -> Unit,
    onStartChat: (hex: String, title: String) -> Unit
) {
    var hexInput by remember { mutableStateOf("") }
    var titleInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = HexSurfaceSubtleDark,
        title = {
            Text(
                text = "New Conversation",
                fontFamily = HexnetFontFamily,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = HexTextPrimaryDark
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Enter a peer's 4-character node hex or select from discovered nearby peers:",
                    fontFamily = HexnetFontFamily,
                    fontSize = 12.sp,
                    color = HexTextSecondaryDark
                )

                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { hexInput = it.trim().uppercase() },
                    label = { Text("Peer Hex (e.g. 8B2F)", fontFamily = HexnetFontFamily, fontSize = 11.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = HexSurfaceDark,
                        unfocusedContainerColor = HexSurfaceDark,
                        focusedBorderColor = HexPeriwinkle,
                        unfocusedBorderColor = HexBorderDark,
                        focusedTextColor = HexTextPrimaryDark,
                        unfocusedTextColor = HexTextPrimaryDark
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = titleInput,
                    onValueChange = { titleInput = it },
                    label = { Text("Display Name (optional)", fontFamily = HexnetFontFamily, fontSize = 11.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = HexSurfaceDark,
                        unfocusedContainerColor = HexSurfaceDark,
                        focusedBorderColor = HexPeriwinkle,
                        unfocusedBorderColor = HexBorderDark,
                        focusedTextColor = HexTextPrimaryDark,
                        unfocusedTextColor = HexTextPrimaryDark
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                if (peers.isNotEmpty()) {
                    Text(
                        text = "Discovered Nearby:",
                        fontFamily = HexnetFontFamily,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = HexTextSecondaryDark
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        peers.take(4).forEach { peer ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(HexSurfaceDark)
                                    .clickable {
                                        onStartChat(peer.hexAddress, peer.username)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = peer.username,
                                    fontFamily = HexnetFontFamily,
                                    fontSize = 12.sp,
                                    color = HexTextPrimaryDark
                                )
                                Text(
                                    text = peer.hexAddress,
                                    fontFamily = HexnetFontFamily,
                                    fontSize = 11.sp,
                                    color = HexPeriwinkle
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (hexInput.isNotBlank()) {
                        val finalTitle = titleInput.ifBlank { "Node $hexInput" }
                        onStartChat(hexInput, finalTitle)
                    }
                },
                enabled = hexInput.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = HexPeriwinkle,
                    contentColor = Color(0xFF09090B)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = "Open Chat",
                    fontFamily = HexnetFontFamily,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "Cancel",
                    fontFamily = HexnetFontFamily,
                    color = HexTextMutedDark
                )
            }
        }
    )
}

fun formatRelativeTime(timestampEpochMs: Long): String {
    if (timestampEpochMs <= 0L) return ""
    val now = System.currentTimeMillis()
    val diff = now - timestampEpochMs

    return when {
        diff < 60_000L -> "now"
        diff < 3600_000L -> "${diff / 60_000L}m"
        diff < 86400_000L -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampEpochMs))
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestampEpochMs))
    }
}
