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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.hexnet.app.core.designsystem.components.*
import net.hexnet.app.core.designsystem.theme.*
import net.hexnet.app.core.model.*

/**
 * Screen: Onboarding & Identity Generation
 * Clean, restrained setup flow with pure AMOLED background and JetBrains Mono typography.
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
            fontFamily = HexnetFontFamily,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = HexTextPrimaryDark,
            letterSpacing = (-0.6).sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Offline Bluetooth LE mesh messenger with forward-secure Noise_XX encryption. No phone number, cloud servers, or cellular connection required.",
            fontFamily = HexnetFontFamily,
            fontSize = 13.sp,
            color = HexTextSecondaryDark,
            lineHeight = 18.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(modifier = Modifier.height(36.dp))

        // Username Field
        OutlinedTextField(
            value = username,
            onValueChange = {
                username = it.trim().lowercase()
                errorMessage = null
            },
            label = { Text("Handle (@username)", fontFamily = HexnetFontFamily, fontSize = 12.sp) },
            placeholder = { Text("e.g. nova", fontFamily = HexnetFontFamily, fontSize = 13.sp, color = HexTextMutedDark) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = HexSurfaceDark,
                unfocusedContainerColor = HexSurfaceDark,
                focusedBorderColor = HexPeriwinkle,
                unfocusedBorderColor = HexBorderDark,
                focusedTextColor = HexTextPrimaryDark,
                unfocusedTextColor = HexTextPrimaryDark,
                focusedLabelColor = HexPeriwinkle,
                unfocusedLabelColor = HexTextSecondaryDark
            ),
            shape = RoundedCornerShape(14.dp),
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
            label = { Text("Display Name", fontFamily = HexnetFontFamily, fontSize = 12.sp) },
            placeholder = { Text("e.g. Nova Tactical", fontFamily = HexnetFontFamily, fontSize = 13.sp, color = HexTextMutedDark) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = HexSurfaceDark,
                unfocusedContainerColor = HexSurfaceDark,
                focusedBorderColor = HexPeriwinkle,
                unfocusedBorderColor = HexBorderDark,
                focusedTextColor = HexTextPrimaryDark,
                unfocusedTextColor = HexTextPrimaryDark,
                focusedLabelColor = HexPeriwinkle,
                unfocusedLabelColor = HexTextSecondaryDark
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        )

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = errorMessage!!,
                fontFamily = HexnetFontFamily,
                color = HexCoral,
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
                containerColor = HexPeriwinkle,
                contentColor = Color(0xFF09090B)
            ),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text(
                text = "Generate Keys & Start Messenger",
                fontFamily = HexnetFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
    }
}

/**
 * Screen: Settings Screen (Destination 4)
 * Clean, modern settings view with user cryptographic identity, BLE controls, and about info.
 */
@Composable
fun SettingsScreen(
    userProfile: UserProfile?,
    meshServiceActive: Boolean = true,
    onToggleMeshService: (Boolean) -> Unit = {},
    onClearIdentity: () -> Unit = {}
) {
    val context = LocalContext.current
    var showCopiedToast by remember { mutableStateOf(false) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }

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
            Text(
                text = "Preferences",
                fontFamily = HexnetFontFamily,
                fontSize = 11.sp,
                color = HexTextSecondaryDark
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Settings",
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
            // User Identity Card
            item {
                Text(
                    text = "My Node Identity",
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
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            HexAvatar(
                                name = userProfile?.displayName ?: "ME",
                                hexAddress = userProfile?.hexAddress ?: "0000",
                                size = 48,
                                isDirect = true
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = userProfile?.displayName ?: "Anonymous Node",
                                    fontFamily = HexnetFontFamily,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = HexTextPrimaryDark
                                )
                                Text(
                                    text = "@${userProfile?.username ?: "unknown"}",
                                    fontFamily = HexnetFontFamily,
                                    fontSize = 12.sp,
                                    color = HexTextSecondaryDark
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(HexAccentSubtle)
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = userProfile?.hexAddress ?: "0x0000",
                                    fontFamily = HexnetFontFamily,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = HexPeriwinkle
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = HexBorderSubtleDark, thickness = 0.5.dp)
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Fingerprint", userProfile?.keyFingerprint ?: ""))
                                    showCopiedToast = true
                                },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "ED25519 FINGERPRINT",
                                    fontFamily = HexnetFontFamily,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = HexTextMutedDark
                                )
                                Text(
                                    text = userProfile?.keyFingerprint?.take(20) ?: "Not initialized",
                                    fontFamily = HexnetFontFamily,
                                    fontSize = 12.sp,
                                    color = HexTextSecondaryDark
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy",
                                tint = HexPeriwinkle,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Mesh Service Toggle
            item {
                Text(
                    text = "Mesh Radio",
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Background Mesh Service",
                                fontFamily = HexnetFontFamily,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = HexTextPrimaryDark
                            )
                            Text(
                                text = "Maintain BLE advertising & packet store-and-forward when app is minimized",
                                fontFamily = HexnetFontFamily,
                                fontSize = 11.sp,
                                color = HexTextSecondaryDark,
                                lineHeight = 16.sp
                            )
                        }
                        Switch(
                            checked = meshServiceActive,
                            onCheckedChange = onToggleMeshService,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF09090B),
                                checkedTrackColor = HexPeriwinkle,
                                uncheckedThumbColor = HexTextMutedDark,
                                uncheckedTrackColor = HexSurfaceDark
                            )
                        )
                    }
                }
            }

            // About / Security specs
            item {
                Text(
                    text = "Security Architecture",
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
                        DetailRow("Handshake Pattern", "Noise_XX (Mutual Authentication)")
                        DetailRow("Key Exchange", "X25519 (RFC 7748)")
                        DetailRow("Payload Cipher", "ChaCha20-Poly1305 AEAD")
                        DetailRow("Hash Function", "BLAKE2s-256")
                        DetailRow("Identity Signatures", "Ed25519 (RFC 8032)")
                    }
                }
            }

            // Clear Identity Action
            item {
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = { showClearConfirmDialog = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = HexCoral
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, HexCoral.copy(alpha = 0.4f), RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = "Reset Cryptographic Identity",
                        fontFamily = HexnetFontFamily,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            containerColor = HexSurfaceSubtleDark,
            title = {
                Text(
                    text = "Reset Keys?",
                    fontFamily = HexnetFontFamily,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = HexTextPrimaryDark
                )
            },
            text = {
                Text(
                    text = "This will delete your local Ed25519 and X25519 keypairs. Your existing conversations and peer sessions will be permanently cleared.",
                    fontFamily = HexnetFontFamily,
                    fontSize = 13.sp,
                    color = HexTextSecondaryDark
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirmDialog = false
                        onClearIdentity()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = HexCoral,
                        contentColor = Color.White
                    )
                ) {
                    Text("Delete Keys", fontFamily = HexnetFontFamily, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("Cancel", fontFamily = HexnetFontFamily, color = HexTextMutedDark)
                }
            }
        )
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
