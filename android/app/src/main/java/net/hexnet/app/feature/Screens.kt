package net.hexnet.app.feature

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.hexnet.app.core.designsystem.components.*
import net.hexnet.app.core.designsystem.theme.*
import net.hexnet.app.core.model.*

/**
 * Screen 1: Onboarding & Identity Generation
 * Minimalist, restrained setup flow (Signal/Linear-inspired).
 */
@Composable
fun OnboardingScreen(
    onComplete: (username: String, displayName: String) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HexBackgroundDark)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // App Wordmark
        Text(
            text = "Hexnet",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = HexTextPrimaryDark,
            letterSpacing = (-0.6).sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Offline Bluetooth LE mesh messenger with forward-secure Noise_XX encryption. No phone number, servers, or internet connection required.",
            fontSize = 13.sp,
            color = HexTextSecondaryDark,
            lineHeight = 18.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp)
        )

        Spacer(modifier = Modifier.height(36.dp))

        // Username Field
        OutlinedTextField(
            value = username,
            onValueChange = {
                username = it.trim().lowercase()
                errorMessage = null
            },
            label = { Text("Handle (@username)", fontSize = 12.sp) },
            placeholder = { Text("e.g. solaris", fontSize = 13.sp, color = HexTextMutedDark) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = HexSurfaceDark,
                unfocusedContainerColor = HexSurfaceDark,
                focusedBorderColor = HexAccentPrimary,
                unfocusedBorderColor = HexBorderSubtleDark,
                focusedTextColor = HexTextPrimaryDark,
                unfocusedTextColor = HexTextPrimaryDark,
                focusedLabelColor = HexAccentPrimary,
                unfocusedLabelColor = HexTextSecondaryDark
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Display Name Field
        OutlinedTextField(
            value = displayName,
            onValueChange = {
                displayName = it
                errorMessage = null
            },
            label = { Text("Display Name", fontSize = 12.sp) },
            placeholder = { Text("e.g. Solaris Node", fontSize = 13.sp, color = HexTextMutedDark) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = HexSurfaceDark,
                unfocusedContainerColor = HexSurfaceDark,
                focusedBorderColor = HexAccentPrimary,
                unfocusedBorderColor = HexBorderSubtleDark,
                focusedTextColor = HexTextPrimaryDark,
                unfocusedTextColor = HexTextPrimaryDark,
                focusedLabelColor = HexAccentPrimary,
                unfocusedLabelColor = HexTextSecondaryDark
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        )

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = errorMessage!!,
                color = HexErrorAlert,
                fontSize = 12.sp
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        Button(
            onClick = {
                val cleanUser = username.trim().removePrefix("@")
                if (cleanUser.length < 3) {
                    errorMessage = "Handle must be at least 3 characters"
                    return@Button
                }
                val cleanDisplay = displayName.trim().ifEmpty { cleanUser.replaceFirstChar { it.uppercase() } }
                onComplete(cleanUser, cleanDisplay)
            },
            enabled = username.trim().isNotBlank(),
            colors = ButtonDefaults.buttonColors(
                containerColor = HexAccentPrimary,
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
        ) {
            Text(
                text = "Generate Keys & Start Messenger",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = HexTextMutedDark,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = "Ed25519 & X25519 keypairs generated locally on-device",
                color = HexTextMutedDark,
                fontSize = 11.sp
            )
        }
    }
}

/**
 * Screen 6: Settings and Security
 * Structured native settings list with hairline dividers (Signal/iOS-style).
 */
@Composable
fun SettingsScreen(
    userProfile: UserProfile?,
    meshServiceActive: Boolean = true,
    onToggleMeshService: (Boolean) -> Unit = {},
    onClearIdentity: () -> Unit
) {
    val context = LocalContext.current
    var showResetDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(HexBackgroundDark),
        contentPadding = PaddingValues(bottom = 88.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "Settings",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = HexTextPrimaryDark,
                    letterSpacing = (-0.4).sp
                )
                Text(
                    text = "Cryptographic identity, BLE radio, and storage",
                    fontSize = 12.sp,
                    color = HexTextSecondaryDark,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            HorizontalDivider(color = HexBorderSubtleDark, thickness = 0.5.dp)
        }

        // Section 1: Cryptographic Identity
        item {
            HexSectionHeader(title = "Cryptographic Identity")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(userProfile?.displayName ?: "Anonymous", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = HexTextPrimaryDark)
                        Text("@${userProfile?.username ?: "anon"}", fontSize = 12.sp, color = HexTextSecondaryDark)
                    }

                    Text(
                        text = "Copy Hex",
                        fontSize = 12.sp,
                        color = HexAccentPrimary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable {
                                userProfile?.let {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("Hex Address", it.hexAddress))
                                }
                            }
                            .padding(4.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Local Hex Address", fontSize = 12.sp, color = HexTextMutedDark)
                    Text(userProfile?.hexAddress ?: "N/A", fontSize = 12.sp, color = HexTextPrimaryDark, fontFamily = FontFamily.Monospace)
                }

                // 64-char key fingerprint
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Safety Fingerprint", fontSize = 12.sp, color = HexTextMutedDark)
                        Text(
                            text = "Copy",
                            fontSize = 11.sp,
                            color = HexAccentPrimary,
                            modifier = Modifier.clickable {
                                userProfile?.let {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("Fingerprint", it.keyFingerprint))
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(HexSurfaceSubtleDark)
                            .border(0.5.dp, HexBorderDark, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = formatFingerprint(userProfile?.keyFingerprint ?: "0000000000000000000000000000000000000000000000000000000000000000"),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = HexTextPrimaryDark,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }

        // Section 2: Bluetooth & Background Operation
        item {
            HexSectionHeader(title = "Bluetooth & Radio")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Foreground Mesh Service", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = HexTextPrimaryDark)
                        Text("Keeps BLE scanning active in background", fontSize = 12.sp, color = HexTextSecondaryDark)
                    }
                    Switch(
                        checked = meshServiceActive,
                        onCheckedChange = onToggleMeshService,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = HexAccentPrimary)
                    )
                }

                HorizontalDivider(color = HexBorderSubtleDark, thickness = 0.5.dp)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Store-and-Forward Relay", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = HexTextPrimaryDark)
                        Text("Relays encrypted packets for distant nodes", fontSize = 12.sp, color = HexTextSecondaryDark)
                    }
                    Switch(
                        checked = true,
                        onCheckedChange = {},
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = HexAccentPrimary)
                    )
                }
            }
        }

        // Section 3: Storage & Database
        item {
            HexSectionHeader(title = "Local Storage")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Database Engine", fontSize = 13.sp, color = HexTextSecondaryDark)
                    Text("Android Room (SQLite WAL)", fontSize = 12.sp, color = HexTextPrimaryDark, fontFamily = FontFamily.Monospace)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Outbound Buffer Limit", fontSize = 13.sp, color = HexTextSecondaryDark)
                    Text("512 packets", fontSize = 12.sp, color = HexTextPrimaryDark, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // Section 4: Danger Zone
        item {
            HexSectionHeader(title = "Danger Zone")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            ) {
                OutlinedButton(
                    onClick = { showResetDialog = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = HexErrorAlert),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, HexErrorAlert.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Wipe Cryptographic Identity & Keys", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            containerColor = HexSurfaceDark,
            titleContentColor = HexErrorAlert,
            title = { Text("Wipe Local Identity?", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "This permanently deletes your Ed25519 identity key, active Noise sessions, and conversation history from local encrypted storage. This cannot be undone.",
                    fontSize = 13.sp,
                    color = HexTextSecondaryDark
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResetDialog = false
                        onClearIdentity()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = HexErrorAlert)
                ) {
                    Text("Wipe All Data", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel", color = HexTextMutedDark)
                }
            }
        )
    }
}

/**
 * 4-Tab Native Bottom Navigation Bar
 * Messages (Primary), Peers, Mesh, Settings.
 */
@Composable
fun HexnetBottomBar(
    currentRoute: String,
    onNavigate: (String) -> Unit
) {
    NavigationBar(
        containerColor = HexBackgroundDark,
        tonalElevation = 0.dp
    ) {
        NavigationBarItem(
            selected = currentRoute == "chats",
            onClick = { onNavigate("chats") },
            icon = { Icon(Icons.Default.ChatBubbleOutline, contentDescription = "Messages") },
            label = { Text("Messages", fontSize = 11.sp, fontWeight = if (currentRoute == "chats") FontWeight.SemiBold else FontWeight.Normal) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = HexAccentPrimary,
                selectedTextColor = HexAccentPrimary,
                unselectedIconColor = HexTextMutedDark,
                unselectedTextColor = HexTextMutedDark,
                indicatorColor = HexAccentPrimary.copy(alpha = 0.12f)
            )
        )

        NavigationBarItem(
            selected = currentRoute == "peers",
            onClick = { onNavigate("peers") },
            icon = { Icon(Icons.Default.PeopleOutline, contentDescription = "Peers") },
            label = { Text("Peers", fontSize = 11.sp, fontWeight = if (currentRoute == "peers") FontWeight.SemiBold else FontWeight.Normal) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = HexAccentPrimary,
                selectedTextColor = HexAccentPrimary,
                unselectedIconColor = HexTextMutedDark,
                unselectedTextColor = HexTextMutedDark,
                indicatorColor = HexAccentPrimary.copy(alpha = 0.12f)
            )
        )

        NavigationBarItem(
            selected = currentRoute == "mesh",
            onClick = { onNavigate("mesh") },
            icon = { Icon(Icons.Default.Hub, contentDescription = "Mesh") },
            label = { Text("Mesh", fontSize = 11.sp, fontWeight = if (currentRoute == "mesh") FontWeight.SemiBold else FontWeight.Normal) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = HexAccentPrimary,
                selectedTextColor = HexAccentPrimary,
                unselectedIconColor = HexTextMutedDark,
                unselectedTextColor = HexTextMutedDark,
                indicatorColor = HexAccentPrimary.copy(alpha = 0.12f)
            )
        )

        NavigationBarItem(
            selected = currentRoute == "settings",
            onClick = { onNavigate("settings") },
            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
            label = { Text("Settings", fontSize = 11.sp, fontWeight = if (currentRoute == "settings") FontWeight.SemiBold else FontWeight.Normal) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = HexAccentPrimary,
                selectedTextColor = HexAccentPrimary,
                unselectedIconColor = HexTextMutedDark,
                unselectedTextColor = HexTextMutedDark,
                indicatorColor = HexAccentPrimary.copy(alpha = 0.12f)
            )
        )
    }
}

fun formatFingerprint(fp: String): String {
    return fp.chunked(4).chunked(4).joinToString("\n") { row -> row.joinToString(" ") }
}
