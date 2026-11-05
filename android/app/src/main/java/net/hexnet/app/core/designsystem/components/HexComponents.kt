package net.hexnet.app.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.hexnet.app.core.designsystem.theme.*
import net.hexnet.app.core.model.MessageDeliveryState

/**
 * Minimalist, restrained avatar with clean circular geometry and subtle verification marker.
 */
@Composable
fun HexAvatar(
    name: String,
    hexAddress: String,
    modifier: Modifier = Modifier,
    size: Int = 44,
    isDirect: Boolean = false,
    isVerified: Boolean = false
) {
    val initials = name.trim().split(" ").let { parts ->
        if (parts.size >= 2) "${parts[0].take(1)}${parts[1].take(1)}".uppercase()
        else name.take(2).uppercase().ifEmpty { "HX" }
    }

    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(HexSurfaceSubtleDark)
            .border(0.5.dp, HexBorderDark, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            color = HexTextPrimaryDark,
            fontWeight = FontWeight.Medium,
            fontSize = (size * 0.36).sp,
            fontFamily = FontFamily.SansSerif
        )

        // Restrained status indicator
        if (isVerified) {
            Box(
                modifier = Modifier
                    .size((size * 0.3).dp)
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(HexBackgroundDark)
                    .padding(1.dp)
                    .clip(CircleShape)
                    .background(HexSuccessDirect),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Verified",
                    tint = Color.White,
                    modifier = Modifier.size((size * 0.2).dp)
                )
            }
        } else if (isDirect) {
            Box(
                modifier = Modifier
                    .size((size * 0.24).dp)
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(HexBackgroundDark)
                    .padding(1.dp)
                    .clip(CircleShape)
                    .background(HexAccentPrimary)
            )
        }
    }
}

/**
 * Minimalist, unobtrusive delivery status indicators (Signal/Linear-inspired).
 */
@Composable
fun DeliveryStatusIcon(
    state: MessageDeliveryState,
    modifier: Modifier = Modifier
) {
    when (state) {
        MessageDeliveryState.PENDING, MessageDeliveryState.QUEUED -> {
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = "Queued in outbound buffer",
                tint = HexTextMutedDark,
                modifier = modifier.size(12.dp)
            )
        }
        MessageDeliveryState.SENDING -> {
            Icon(
                imageVector = Icons.Default.ArrowUpward,
                contentDescription = "Transmitting to mesh",
                tint = HexAccentPrimary,
                modifier = modifier.size(12.dp)
            )
        }
        MessageDeliveryState.RELAYED, MessageDeliveryState.SENT -> {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Sent & relayed by mesh node",
                tint = HexTextSecondaryDark,
                modifier = modifier.size(12.dp)
            )
        }
        MessageDeliveryState.DELIVERED -> {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = "Delivered & verified by recipient",
                tint = HexAccentPrimary,
                modifier = modifier.size(13.dp)
            )
        }
        MessageDeliveryState.FAILED -> {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = "Delivery failed · Tap to retry",
                tint = HexErrorAlert,
                modifier = modifier.size(12.dp)
            )
        }
    }
}

/**
 * Restrained signal strength indicator with compact dBm reading.
 */
@Composable
fun SignalMeter(
    rssiDbm: Int,
    isDirect: Boolean = true,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        val bars = when {
            rssiDbm >= -65 -> 4
            rssiDbm >= -75 -> 3
            rssiDbm >= -85 -> 2
            else -> 1
        }
        val activeColor = if (isDirect) HexSuccessDirect else HexWarningRelay

        for (i in 1..4) {
            val height = (i * 2.5 + 3).dp
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height(height)
                    .clip(RoundedCornerShape(0.5.dp))
                    .background(if (i <= bars) activeColor else HexBorderDark)
            )
        }
    }
}

/**
 * Minimalist, elegant empty state with understated copy and direct action.
 */
@Composable
fun HexEmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(HexSurfaceSubtleDark),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = HexTextSecondaryDark,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = title,
            color = HexTextPrimaryDark,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = subtitle,
            color = HexTextSecondaryDark,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = HexAccentPrimary,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)
            ) {
                Text(
                    text = actionLabel,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp
                )
            }
        }
    }
}

/**
 * Clean hairline section header with quiet unboxed typography.
 */
@Composable
fun HexSectionHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = HexTextSecondaryDark,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
        HorizontalDivider(color = HexBorderSubtleDark, thickness = 0.5.dp)
    }
}

