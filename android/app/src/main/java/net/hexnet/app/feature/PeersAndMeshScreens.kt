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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.hexnet.app.core.designsystem.components.*
import net.hexnet.app.core.designsystem.theme.*
import net.hexnet.app.core.model.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Screen: Nearby Discovery & Peer Management
 * Clean, restrained peer discovery list.
 * Edge-to-edge peer rows, understated signal indicator, quick chat action, and identity verification sheet.
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
    var peerToInspect by remember { mutableStateOf<Peer?>(null) }

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
        // One UI Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
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
                            .background(if (isScanning) HexMint else HexTextMutedDark)
                    )
                    Text(
                        text = if (isScanning) "BLE discovery active" else "Scanner idle",
                        fontFamily = HexnetFontFamily,
                        fontSize = 11.sp,
                        color = HexTextSecondaryDark
                    )
                }

                Text(
                    text = "${peers.size} in range",
                    fontFamily = HexnetFontFamily,
                    fontSize = 11.sp,
                    color = HexPeriwinkle
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Nearby",
                fontFamily = HexnetFontFamily,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = HexTextPrimaryDark,
                letterSpacing = (-0.6).sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Filters
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "All (${peers.size})",
                    fontFamily = HexnetFontFamily,
                    fontSize = 13.sp,
                    fontWeight = if (selectedFilter == "all") FontWeight.Bold else FontWeight.Normal,
                    color = if (selectedFilter == "all") HexPeriwinkle else HexTextMutedDark,
                    modifier = Modifier
                        .clickable { selectedFilter = "all" }
                        .padding(vertical = 4.dp)
                )
                Text(
                    text = "Direct (${peers.count { it.isDirectNeighbor }})",
                    fontFamily = HexnetFontFamily,
                    fontSize = 13.sp,
                    fontWeight = if (selectedFilter == "direct") FontWeight.Bold else FontWeight.Normal,
                    color = if (selectedFilter == "direct") HexPeriwinkle else HexTextMutedDark,
                    modifier = Modifier
                        .clickable { selectedFilter = "direct" }
                        .padding(vertical = 4.dp)
                )
                Text(
                    text = "Contacts (${peers.count { it.isContact }})",
                    fontFamily = HexnetFontFamily,
                    fontSize = 13.sp,
                    fontWeight = if (selectedFilter == "contacts") FontWeight.Bold else FontWeight.Normal,
                    color = if (selectedFilter == "contacts") HexPeriwinkle else HexTextMutedDark,
                    modifier = Modifier
                        .clickable { selectedFilter = "contacts" }
                        .padding(vertical = 4.dp)
                )
            }
        }

        HorizontalDivider(color = HexBorderSubtleDark, thickness = 0.5.dp)

        // Peer List
        if (filteredPeers.isEmpty()) {
            HexEmptyState(
                icon = Icons.Outlined.Sensors,
                title = "No peers discovered",
                subtitle = "Make sure Bluetooth is enabled. Nearby Hexnet nodes broadcasting BLE advertisements will show up here automatically."
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                items(filteredPeers, key = { it.peerId }) { peer ->
                    PeerListRow(
                        peer = peer,
                        onInspect = { peerToInspect = peer },
                        onStartChat = { onStartChat(peer) }
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

    if (peerToInspect != null) {
        PeerInspectionDialog(
            peer = peerToInspect!!,
            onDismiss = { peerToInspect = null },
            onToggleContact = { onToggleContact(peerToInspect!!.peerId) },
            onVerifyIdentity = {
                onVerifyIdentity(peerToInspect!!.peerId)
                peerToInspect = null
            },
            onStartChat = {
                val p = peerToInspect!!
                peerToInspect = null
                onStartChat(p)
            }
        )
    }
}

@Composable
private fun PeerListRow(
    peer: Peer,
    onInspect: () -> Unit,
    onStartChat: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onInspect)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HexAvatar(
            name = peer.username,
            hexAddress = peer.hexAddress,
            size = 46,
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
                Text(
                    text = peer.username,
                    fontFamily = HexnetFontFamily,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = HexTextPrimaryDark
                )

                Text(
                    text = if (peer.isDirectNeighbor) "1-Hop Direct" else "${peer.hopDistance}-Hop Relay",
                    fontFamily = HexnetFontFamily,
                    fontSize = 11.sp,
                    color = if (peer.isDirectNeighbor) HexMint else HexTextMutedDark
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = "${peer.hexAddress} · ${if (peer.isIdentityVerified) "Verified key" else "Unverified"}",
                fontFamily = HexnetFontFamily,
                fontSize = 12.sp,
                color = HexTextSecondaryDark
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Button(
            onClick = onStartChat,
            colors = ButtonDefaults.buttonColors(
                containerColor = HexAccentSubtle,
                contentColor = HexPeriwinkle
            ),
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            modifier = Modifier.height(34.dp)
        ) {
            Text(
                text = "Chat",
                fontFamily = HexnetFontFamily,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Screen: Mesh Diagnostics & Routing Overview
 * Clean, structured telemetry view.
 */
@Composable
fun MeshScreen(
    peers: List<Peer>,
    events: List<NetworkEvent> = emptyList(),
    outboundQueueCount: Int = 0,
    relayQueueCount: Int = 0
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HexBackgroundDark)
    ) {
        // One UI Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
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
                            .background(HexPeriwinkle)
                    )
                    Text(
                        text = "AODV-Lite Protocol",
                        fontFamily = HexnetFontFamily,
                        fontSize = 11.sp,
                        color = HexTextSecondaryDark
                    )
                }

                Text(
                    text = "Nominal",
                    fontFamily = HexnetFontFamily,
                    fontSize = 11.sp,
                    color = HexMint
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Mesh",
                fontFamily = HexnetFontFamily,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = HexTextPrimaryDark,
                letterSpacing = (-0.6).sp
            )
        }

        HorizontalDivider(color = HexBorderSubtleDark, thickness = 0.5.dp)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Stats Row
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricCard(
                        title = "PEERS",
                        value = peers.size.toString(),
                        subtitle = "${peers.count { it.isDirectNeighbor }} direct",
                        color = HexPeriwinkle,
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        title = "BUFFER",
                        value = (outboundQueueCount + relayQueueCount).toString(),
                        subtitle = "$relayQueueCount relay",
                        color = HexLavender,
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        title = "LOSS",
                        value = "0.2%",
                        subtitle = "Noise E2EE",
                        color = HexMint,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Radio Configuration Card
            item {
                Text(
                    text = "Radio & Transport",
                    fontFamily = HexnetFontFamily,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = HexTextSecondaryDark
                )
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, HexBorderDark, RoundedCornerShape(16.dp)),
                    color = HexSurfaceSubtleDark
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        DetailRow("Carrier Mode", "Bluetooth Low Energy (BLE 2M PHY)")
                        DetailRow("Frequency Band", "2.440 GHz ISM")
                        DetailRow("MTU Framing", "251 Bytes Negotiated")
                        DetailRow("Routing Engine", "On-Demand Distance Vector (AODV)")
                        DetailRow("Crypto Suite", "Noise_XX (X25519, ChaChaPoly, BLAKE2s)")
                    }
                }
            }

            // Active Routing Nodes
            item {
                Text(
                    text = "Routing Table (${peers.size})",
                    fontFamily = HexnetFontFamily,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = HexTextSecondaryDark
                )
            }

            if (peers.isEmpty()) {
                item {
                    Text(
                        text = "No routes currently converged in local routing table.",
                        fontFamily = HexnetFontFamily,
                        fontSize = 12.sp,
                        color = HexTextMutedDark,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            } else {
                items(peers, key = { it.peerId }) { peer ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, HexBorderDark, RoundedCornerShape(14.dp)),
                        color = HexSurfaceSubtleDark
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "${peer.username} (${peer.hexAddress})",
                                    fontFamily = HexnetFontFamily,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = HexTextPrimaryDark
                                )
                                Text(
                                    text = if (peer.isDirectNeighbor) "Next hop: Direct BLE" else "Next hop: Multi-hop relay",
                                    fontFamily = HexnetFontFamily,
                                    fontSize = 11.sp,
                                    color = HexTextSecondaryDark
                                )
                            }
                            Text(
                                text = "${peer.hopDistance} HOP",
                                fontFamily = HexnetFontFamily,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = HexPeriwinkle
                            )
                        }
                    }
                }
            }

            // Bottom padding for navigation
            item {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    subtitle: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, HexBorderDark, RoundedCornerShape(16.dp)),
        color = HexSurfaceSubtleDark
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                fontFamily = HexnetFontFamily,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = HexTextMutedDark
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                fontFamily = HexnetFontFamily,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontFamily = HexnetFontFamily,
                fontSize = 10.sp,
                color = HexTextSecondaryDark
            )
        }
    }
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
            color = HexTextPrimaryDark
        )
    }
}

@Composable
private fun PeerInspectionDialog(
    peer: Peer,
    onDismiss: () -> Unit,
    onToggleContact: () -> Unit,
    onVerifyIdentity: () -> Unit,
    onStartChat: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = HexSurfaceSubtleDark,
        title = {
            Text(
                text = peer.username,
                fontFamily = HexnetFontFamily,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = HexTextPrimaryDark
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DetailRow("Node Address", peer.hexAddress)
                DetailRow("Display Name", peer.displayName)
                DetailRow("Hop Distance", if (peer.isDirectNeighbor) "1-Hop (Direct BLE)" else "${peer.hopDistance}-Hops (Relayed)")
                DetailRow("Key Status", if (peer.isIdentityVerified) "Ed25519 Verified" else "Unverified")
                DetailRow("Signal RSSI", "${peer.rssiDbm} dBm")
            }
        },
        confirmButton = {
            Button(
                onClick = onStartChat,
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
                    text = "Close",
                    fontFamily = HexnetFontFamily,
                    color = HexTextMutedDark
                )
            }
        }
    )
}
