package net.hexnet.app.feature

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.hexnet.app.core.designsystem.components.*
import net.hexnet.app.core.designsystem.theme.*
import net.hexnet.app.core.model.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Screen 4: Nearby Peers Discovery & Contact Management
 * Minimalist, restrained peer discovery (Signal/Linear-inspired).
 * Hairline-separated peer rows, compact RSSI, verification flow.
 */
@Composable
fun PeersScreen(
    peers: List<Peer>,
    isScanning: Boolean = true,
    onToggleContact: (String) -> Unit = {},
    onToggleBlock: (String) -> Unit = {},
    onVerifyIdentity: (String) -> Unit = {},
    onStartChat: (Peer) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("all") } // "all" | "direct" | "contacts"
    var peerToVerify by remember { mutableStateOf<Peer?>(null) }

    val filteredPeers = remember(peers, searchQuery, selectedFilter) {
        peers.filter { peer ->
            val matchesSearch = searchQuery.isBlank() ||
                peer.displayName.contains(searchQuery, ignoreCase = true) ||
                peer.username.contains(searchQuery, ignoreCase = true) ||
                peer.hexAddress.contains(searchQuery, ignoreCase = true)

            val matchesFilter = when (selectedFilter) {
                "direct" -> peer.isDirectNeighbor
                "contacts" -> peer.isContact
                else -> true
            }

            matchesSearch && matchesFilter
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HexBackgroundDark)
    ) {
        // Top App Bar
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
                            text = "Nearby Peers",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = HexTextPrimaryDark,
                            letterSpacing = (-0.4).sp
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 1.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (isScanning) HexSuccessDirect else HexTextMutedDark)
                            )
                            Text(
                                text = if (isScanning) "Scanning BLE advertising channels..." else "Scanner idle",
                                fontSize = 11.sp,
                                color = HexTextSecondaryDark
                            )
                        }
                    }

                    Text(
                        text = "${peers.size} found",
                        fontSize = 12.sp,
                        color = HexTextMutedDark,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Filter Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "All (${peers.size})",
                        fontSize = 13.sp,
                        fontWeight = if (selectedFilter == "all") FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selectedFilter == "all") HexTextPrimaryDark else HexTextMutedDark,
                        modifier = Modifier
                            .clickable { selectedFilter = "all" }
                            .padding(vertical = 4.dp)
                    )
                    Text(
                        text = "1-Hop Direct (${peers.count { it.isDirectNeighbor }})",
                        fontSize = 13.sp,
                        fontWeight = if (selectedFilter == "direct") FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selectedFilter == "direct") HexTextPrimaryDark else HexTextMutedDark,
                        modifier = Modifier
                            .clickable { selectedFilter = "direct" }
                            .padding(vertical = 4.dp)
                    )
                    Text(
                        text = "Contacts (${peers.count { it.isContact }})",
                        fontSize = 13.sp,
                        fontWeight = if (selectedFilter == "contacts") FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selectedFilter == "contacts") HexTextPrimaryDark else HexTextMutedDark,
                        modifier = Modifier
                            .clickable { selectedFilter = "contacts" }
                            .padding(vertical = 4.dp)
                    )
                }
            }
        }

        HorizontalDivider(color = HexBorderSubtleDark, thickness = 0.5.dp)

        // Peer List
        if (filteredPeers.isEmpty()) {
            HexEmptyState(
                icon = Icons.Outlined.BluetoothSearching,
                title = "Listening for nearby nodes",
                subtitle = "Devices running Hexnet within ~30 meters will be discovered via Bluetooth LE advertisements."
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 88.dp)
            ) {
                items(filteredPeers, key = { it.peerId }) { peer ->
                    PeerListRow(
                        peer = peer,
                        onVerify = { peerToVerify = peer },
                        onToggleContact = { onToggleContact(peer.peerId) },
                        onStartChat = { onStartChat(peer) }
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

    // Verification Dialog
    peerToVerify?.let { peer ->
        PeerVerificationDialog(
            peer = peer,
            onDismiss = { peerToVerify = null },
            onConfirmVerify = {
                onVerifyIdentity(peer.peerId)
                peerToVerify = null
            }
        )
    }
}

@Composable
private fun PeerListRow(
    peer: Peer,
    onVerify: () -> Unit,
    onToggleContact: () -> Unit,
    onStartChat: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onStartChat)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HexAvatar(
            name = peer.displayName,
            hexAddress = peer.hexAddress,
            size = 44,
            isDirect = peer.isDirectNeighbor,
            isVerified = peer.isIdentityVerified
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = peer.displayName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = HexTextPrimaryDark
                    )
                    if (peer.isContact) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Contact",
                            tint = HexWarningRelay,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SignalMeter(rssiDbm = peer.rssiDbm, isDirect = peer.isDirectNeighbor)
                    Text(
                        text = "${peer.rssiDbm} dBm",
                        fontSize = 10.sp,
                        color = HexTextMutedDark,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${peer.hexAddress} · ${if (peer.isDirectNeighbor) "1-Hop BLE" else "${peer.hopDistance} hops"}",
                    fontSize = 12.sp,
                    color = HexTextSecondaryDark,
                    fontFamily = FontFamily.Monospace
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!peer.isIdentityVerified) {
                        Text(
                            text = "Verify",
                            fontSize = 12.sp,
                            color = HexTextMutedDark,
                            modifier = Modifier
                                .clickable(onClick = onVerify)
                                .padding(vertical = 2.dp)
                        )
                    } else {
                        Text(
                            text = "Verified",
                            fontSize = 11.sp,
                            color = HexSuccessDirect,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Text(
                        text = "Message",
                        fontSize = 12.sp,
                        color = HexAccentPrimary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable(onClick = onStartChat)
                            .padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PeerVerificationDialog(
    peer: Peer,
    onDismiss: () -> Unit,
    onConfirmVerify: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = HexSurfaceDark,
        titleContentColor = HexTextPrimaryDark,
        title = { Text("Verify Identity", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Compare this cryptographic key fingerprint out-of-band with ${peer.displayName} to verify their identity.",
                    fontSize = 13.sp,
                    color = HexTextSecondaryDark
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(HexSurfaceSubtleDark)
                        .border(0.5.dp, HexBorderDark, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = formatFingerprint(peer.keyFingerprint),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = HexTextPrimaryDark,
                        lineHeight = 16.sp
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Hex Address", fontSize = 12.sp, color = HexTextMutedDark)
                    Text(peer.hexAddress, fontSize = 12.sp, color = HexTextPrimaryDark, fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmVerify,
                colors = ButtonDefaults.buttonColors(containerColor = HexSuccessDirect)
            ) {
                Text("Mark as Verified", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = HexTextMutedDark)
            }
        }
    )
}

/**
 * Screen 5: Mesh Diagnostics & Routing Table
 * Clean, structured technical diagnostics (Linear-inspired).
 * Restrained tables, queue monitors, real audit log.
 */
@Composable
fun MeshScreen(
    peers: List<Peer> = emptyList(),
    events: List<NetworkEvent> = emptyList(),
    outboundQueueCount: Int = 0,
    relayQueueCount: Int = 0,
    onRetryQueues: () -> Unit = {}
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(HexBackgroundDark),
        contentPadding = PaddingValues(bottom = 88.dp)
    ) {
        item {
            // Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "Mesh Diagnostics",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = HexTextPrimaryDark,
                    letterSpacing = (-0.4).sp
                )
                Text(
                    text = "Real-time routing tables and store-and-forward queues",
                    fontSize = 12.sp,
                    color = HexTextSecondaryDark,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            HorizontalDivider(color = HexBorderSubtleDark, thickness = 0.5.dp)
        }

        // Section 1: Mesh Node Status
        item {
            HexSectionHeader(title = "Node Status")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DiagnosticsRow("Radio Interface", "Bluetooth 5.0 LE (Coded PHY / 1M)")
                DiagnosticsRow("Mesh Protocol", "Hexnet Ad-hoc Flooding / Reverse-Path")
                DiagnosticsRow("Connected 1-Hop Links", "${peers.count { it.isDirectNeighbor }} active")
                DiagnosticsRow("Multi-Hop Known Peers", "${peers.count { !it.isDirectNeighbor }} discovered")
            }
        }

        // Section 2: Store-and-Forward Queues
        item {
            HexSectionHeader(title = "Store-and-Forward Buffers")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DiagnosticsRow("Outbound Pending Queue", "$outboundQueueCount packets")
                DiagnosticsRow("Mesh Relay Queue", "$relayQueueCount packets")
                DiagnosticsRow("Buffer Cap", "512 packets (LRU auto-eviction)")
            }
        }

        // Section 3: Active Routing Table
        item {
            HexSectionHeader(title = "Active Routing Table (${peers.size} entries)")
        }

        if (peers.isEmpty()) {
            item {
                Text(
                    text = "No routes established yet. Discovering nearby BLE advertisements...",
                    fontSize = 12.sp,
                    color = HexTextMutedDark,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                )
            }
        } else {
            items(peers, key = { it.peerId }) { peer ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = peer.displayName,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = HexTextPrimaryDark
                        )
                        Text(
                            text = peer.hexAddress,
                            fontSize = 11.sp,
                            color = HexTextMutedDark,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = if (peer.isDirectNeighbor) "Direct 1-Hop" else "${peer.hopDistance} Hops via Relay",
                            fontSize = 12.sp,
                            color = if (peer.isDirectNeighbor) HexSuccessDirect else HexWarningRelay
                        )
                        Text(
                            text = "${peer.rssiDbm} dBm",
                            fontSize = 11.sp,
                            color = HexTextMutedDark,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                HorizontalDivider(color = HexBorderSubtleDark, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 18.dp))
            }
        }

        // Section 4: Live Event Stream
        item {
            HexSectionHeader(title = "Network Event Log (${events.size})")
        }

        if (events.isEmpty()) {
            item {
                Text(
                    text = "No network events logged in current session.",
                    fontSize = 12.sp,
                    color = HexTextMutedDark,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                )
            }
        } else {
            items(events.take(20), key = { it.id }) { event ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = event.type.name,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = when (event.type) {
                                NetworkEventType.PEER_DISCOVERED -> HexAccentPrimary
                                NetworkEventType.PACKET_RELAYED -> HexWarningRelay
                                NetworkEventType.DELIVERY_ACK_RECEIVED -> HexSuccessDirect
                                else -> HexTextSecondaryDark
                            },
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(event.timestampEpochMs)),
                            fontSize = 10.sp,
                            color = HexTextMutedDark,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Text(
                        text = event.summary,
                        fontSize = 13.sp,
                        color = HexTextPrimaryDark,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                    if (event.details.isNotBlank()) {
                        Text(
                            text = event.details,
                            fontSize = 11.sp,
                            color = HexTextSecondaryDark,
                            modifier = Modifier.padding(top = 1.dp)
                        )
                    }
                }
                HorizontalDivider(color = HexBorderSubtleDark, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 18.dp))
            }
        }
    }
}

@Composable
private fun DiagnosticsRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = HexTextSecondaryDark)
        Text(value, fontSize = 12.sp, color = HexTextPrimaryDark, fontFamily = FontFamily.Monospace)
    }
}
