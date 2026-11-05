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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.hexnet.app.core.designsystem.components.*
import net.hexnet.app.core.designsystem.theme.*
import net.hexnet.app.core.model.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Screen 2: Main Messaging Interface — THE HERO SCREEN
 * Premium, minimalist conversation list (Signal/Linear-inspired).
 * Clean hairline dividers, zero excessive cards, dominant conversation list.
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
    var isSearchActive by remember { mutableStateOf(false) }
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
                containerColor = HexAccentPrimary,
                contentColor = Color.White,
                shape = CircleShape,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 3.dp, pressedElevation = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "New Conversation",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Elegant Top App Bar
            Surface(
                color = HexBackgroundDark,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Messages",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = HexTextPrimaryDark,
                                letterSpacing = (-0.4).sp
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                modifier = Modifier.padding(top = 1.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (meshActive) HexSuccessDirect else HexTextMutedDark)
                                )
                                Text(
                                    text = if (connectedPeersCount > 0) "$connectedPeersCount peers in range" else "BLE mesh active",
                                    fontSize = 11.sp,
                                    color = HexTextSecondaryDark
                                )
                            }
                        }

                        IconButton(
                            onClick = { isSearchActive = !isSearchActive }
                        ) {
                            Icon(
                                imageVector = if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = "Search",
                                tint = HexTextSecondaryDark,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Expandable Hairline Search Bar
                    AnimatedVisibility(
                        visible = isSearchActive,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search messages or hex...", fontSize = 13.sp, color = HexTextMutedDark) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = HexSurfaceDark,
                                unfocusedContainerColor = HexSurfaceDark,
                                focusedBorderColor = HexAccentPrimary,
                                unfocusedBorderColor = HexBorderSubtleDark,
                                focusedTextColor = HexTextPrimaryDark,
                                unfocusedTextColor = HexTextPrimaryDark
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp)
                                .height(46.dp)
                        )
                    }
                }
            }

            // Clean Quiet Tab Selector (Unboxed Segment)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Direct (${conversations.size})",
                    fontSize = 13.sp,
                    fontWeight = if (activeTab == "direct") FontWeight.SemiBold else FontWeight.Normal,
                    color = if (activeTab == "direct") HexTextPrimaryDark else HexTextMutedDark,
                    modifier = Modifier
                        .clickable { activeTab = "direct" }
                        .padding(vertical = 6.dp)
                )
                Text(
                    text = "Channels (${rooms.size})",
                    fontSize = 13.sp,
                    fontWeight = if (activeTab == "rooms") FontWeight.SemiBold else FontWeight.Normal,
                    color = if (activeTab == "rooms") HexTextPrimaryDark else HexTextMutedDark,
                    modifier = Modifier
                        .clickable { activeTab = "rooms" }
                        .padding(vertical = 6.dp)
                )
            }

            HorizontalDivider(color = HexBorderSubtleDark, thickness = 0.5.dp)

            // Conversation List (Dominating the Viewport)
            if (activeTab == "direct") {
                if (filteredConversations.isEmpty()) {
                    HexEmptyState(
                        icon = Icons.Outlined.ChatBubbleOutline,
                        title = if (searchQuery.isNotBlank()) "No matches found" else "No conversations yet",
                        subtitle = if (searchQuery.isNotBlank()) "Try a different search term" else "Discovered nearby Bluetooth mesh peers will appear automatically. You can also start a chat with any peer's hex address.",
                        actionLabel = "Start New Chat",
                        onAction = { showNewChatDialog = true }
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 88.dp)
                    ) {
                        items(filteredConversations, key = { it.id }) { conversation ->
                            ConversationListRow(
                                conversation = conversation,
                                onClick = { onOpenConversation(conversation.id) }
                            )
                            HorizontalDivider(
                                color = HexBorderSubtleDark,
                                thickness = 0.5.dp,
                                modifier = Modifier.padding(start = 72.dp)
                            )
                        }
                    }
                }
            } else {
                if (filteredRooms.isEmpty()) {
                    HexEmptyState(
                        icon = Icons.Outlined.Tag,
                        title = "No channels found",
                        subtitle = "Public mesh broadcast channels allow offline group communication across the mesh."
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 88.dp)
                    ) {
                        items(filteredRooms, key = { it.id }) { room ->
                            RoomListRow(
                                room = room,
                                onClick = { onOpenConversation(room.id) }
                            )
                            HorizontalDivider(
                                color = HexBorderSubtleDark,
                                thickness = 0.5.dp,
                                modifier = Modifier.padding(start = 72.dp)
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

@Composable
private fun ConversationListRow(
    conversation: Conversation,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HexAvatar(
            name = conversation.title,
            hexAddress = conversation.targetHexAddress,
            size = 44,
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
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = HexTextPrimaryDark,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false)
                )

                Text(
                    text = formatRelativeTime(conversation.lastMessageTimestamp),
                    fontSize = 11.sp,
                    color = HexTextMutedDark,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = conversation.lastMessageText,
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
                            .background(HexAccentPrimary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = conversation.unreadCount.toString(),
                            color = Color.White,
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
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(HexSurfaceSubtleDark)
                .border(0.5.dp, HexBorderDark, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Tag,
                contentDescription = null,
                tint = HexTextSecondaryDark,
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
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = HexTextPrimaryDark
                )

                Text(
                    text = "${room.participantCount} peers",
                    fontSize = 11.sp,
                    color = HexTextMutedDark
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = room.topic,
                fontSize = 13.sp,
                color = HexTextSecondaryDark,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Screen 3: Conversation Detail (Actual Messaging View)
 * Signal/Linear-style chat with clean bubbles, incoming/outgoing distinction,
 * micro-timestamps, and minimal security lock indicator.
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

    // Scroll to latest message
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
                            size = 36,
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
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
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
                                    tint = HexTextMutedDark,
                                    modifier = Modifier.size(10.dp)
                                )
                                Text(
                                    text = if (isDirectLink) "Direct BLE · Noise E2EE" else "Relayed Mesh · Noise E2EE",
                                    fontSize = 11.sp,
                                    color = HexTextSecondaryDark
                                )
                            }
                        }

                        IconButton(onClick = { showInfoSheet = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = "Info",
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
            // Elegant Minimalist Message Composer
            Surface(
                color = HexBackgroundDark,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                Column {
                    HorizontalDivider(color = HexBorderSubtleDark, thickness = 0.5.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            placeholder = { Text("Message...", fontSize = 14.sp, color = HexTextMutedDark) },
                            maxLines = 4,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = HexSurfaceDark,
                                unfocusedContainerColor = HexSurfaceDark,
                                focusedBorderColor = HexBorderDark,
                                unfocusedBorderColor = HexBorderSubtleDark,
                                focusedTextColor = HexTextPrimaryDark,
                                unfocusedTextColor = HexTextPrimaryDark
                            ),
                            shape = RoundedCornerShape(20.dp),
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
                                .heightIn(min = 42.dp, max = 100.dp)
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        IconButton(
                            onClick = {
                                if (inputText.isNotBlank()) {
                                    onSendMessage(inputText.trim())
                                    inputText = ""
                                }
                            },
                            enabled = inputText.isNotBlank(),
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (inputText.isNotBlank()) HexAccentPrimary else HexSurfaceSubtleDark)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowUpward,
                                contentDescription = "Send",
                                tint = if (inputText.isNotBlank()) Color.White else HexTextMutedDark,
                                modifier = Modifier.size(20.dp)
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
                // Tasteful 1-Line Security Notice
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = HexTextMutedDark,
                        modifier = Modifier.size(11.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "End-to-end encrypted with Noise_XX_25519_ChaChaPoly_BLAKE2s",
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
                .widthIn(max = 280.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isOutgoing) 16.dp else 2.dp,
                        bottomEnd = if (isOutgoing) 2.dp else 16.dp
                    )
                )
                .background(
                    when {
                        isFailed -> HexErrorAlert.copy(alpha = 0.15f)
                        isOutgoing -> HexAccentPrimary
                        else -> HexSurfaceSubtleDark
                    }
                )
                .then(
                    if (isFailed) Modifier.border(0.5.dp, HexErrorAlert, RoundedCornerShape(16.dp))
                    else if (!isOutgoing) Modifier.border(0.5.dp, HexBorderDark, RoundedCornerShape(16.dp))
                    else Modifier
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column {
                if (!isOutgoing && message.senderUsername.isNotBlank()) {
                    Text(
                        text = message.senderUsername,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = HexTextSecondaryDark,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }

                Text(
                    text = message.content,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    color = if (isOutgoing) Color.White else HexTextPrimaryDark
                )

                Spacer(modifier = Modifier.height(3.dp))

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestampEpochMs)),
                        fontSize = 10.sp,
                        color = if (isOutgoing) Color.White.copy(alpha = 0.7f) else HexTextMutedDark,
                        fontFamily = FontFamily.Monospace
                    )

                    if (isOutgoing) {
                        DeliveryStatusIcon(state = message.state)
                    }
                }
            }
        }

        if (isFailed) {
            Text(
                text = "Failed · Tap to retry",
                fontSize = 11.sp,
                color = HexErrorAlert,
                modifier = Modifier
                    .clickable(onClick = onRetry)
                    .padding(top = 2.dp, end = 4.dp)
            )
        }
    }
}

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
        containerColor = HexSurfaceDark,
        titleContentColor = HexTextPrimaryDark,
        textContentColor = HexTextSecondaryDark,
        title = {
            Text("Connection Details", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DetailRow("Recipient", title)
                DetailRow("Hex Address", targetHex.ifEmpty { "N/A" })
                DetailRow("Route", if (isDirectLink) "Direct 1-Hop BLE" else "Multi-Hop Mesh")
                DetailRow("Protocol", "Noise_XX_25519_ChaChaPoly")
                DetailRow("Verification", if (isIdentityVerified) "Cryptographically Verified" else "Unverified")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = HexAccentPrimary)
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 12.sp, color = HexTextMutedDark)
        Text(value, fontSize = 12.sp, color = HexTextPrimaryDark, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun NewConversationDialog(
    peers: List<Peer>,
    onDismiss: () -> Unit,
    onStartChat: (hex: String, title: String) -> Unit
) {
    var hexInput by remember { mutableStateOf("") }
    var nameInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = HexSurfaceDark,
        titleContentColor = HexTextPrimaryDark,
        title = { Text("New Conversation", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (peers.isNotEmpty()) {
                    Text("Discovered Peers", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = HexTextSecondaryDark)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        peers.take(3).forEach { peer ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onStartChat(peer.hexAddress, peer.displayName) }
                                    .background(HexSurfaceSubtleDark)
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    HexAvatar(name = peer.displayName, hexAddress = peer.hexAddress, size = 28)
                                    Column {
                                        Text(peer.displayName, fontSize = 13.sp, color = HexTextPrimaryDark, fontWeight = FontWeight.Medium)
                                        Text(peer.hexAddress, fontSize = 10.sp, color = HexTextMutedDark, fontFamily = FontFamily.Monospace)
                                    }
                                }
                                Text("Chat", fontSize = 12.sp, color = HexAccentPrimary, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    HorizontalDivider(color = HexBorderSubtleDark, thickness = 0.5.dp)
                }

                Text("Or Enter Hex Address", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = HexTextSecondaryDark)
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { hexInput = it },
                    placeholder = { Text("0x1A2B3C4D5E6F7A8B", fontSize = 12.sp, color = HexTextMutedDark) },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    placeholder = { Text("Contact Name (optional)", fontSize = 12.sp, color = HexTextMutedDark) },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (hexInput.isNotBlank()) {
                        onStartChat(hexInput.trim(), nameInput.trim().ifEmpty { "Peer" })
                    }
                },
                enabled = hexInput.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = HexAccentPrimary)
            ) {
                Text("Start Chat", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = HexTextMutedDark)
            }
        }
    )
}

private fun formatRelativeTime(epochMs: Long): String {
    val diffSec = (System.currentTimeMillis() - epochMs) / 1000
    return when {
        diffSec < 60 -> "now"
        diffSec < 3600 -> "${diffSec / 60}m"
        diffSec < 86400 -> "${diffSec / 3600}h"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(epochMs))
    }
}
