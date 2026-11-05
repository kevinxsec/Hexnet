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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.hexnet.app.core.designsystem.theme.*
import net.hexnet.app.core.model.MessageDeliveryState

/**
 * Minimalist, premium avatar with clean circular geometry and subtle status dot.
 */
@Composable
fun HexAvatar(
    name: String,
    hexAddress: String,
    modifier: Modifier = Modifier,
    size: Int = 46,
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
            .border(1.dp, HexBorderDark, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            color = HexTextPrimaryDark,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size * 0.36).sp,
            fontFamily = HexnetFontFamily
        )

        // Minimal subtle indicator
        if (isVerified) {
            Box(
                modifier = Modifier
                    .size((size * 0.28).dp)
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(HexBackgroundDark)
                    .padding(1.5.dp)
                    .clip(CircleShape)
                    .background(HexViolet),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Verified",
                    tint = Color.White,
                    modifier = Modifier.size((size * 0.18).dp)
                )
            }
        } else if (isDirect) {
            Box(
                modifier = Modifier
                    .size((size * 0.22).dp)
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(HexBackgroundDark)
                    .padding(1.5.dp)
                    .clip(CircleShape)
                    .background(HexMint)
            )
        }
    }
}

/**
 * Unobtrusive, elegant delivery status indicators.
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
                contentDescription = "Queued",
                tint = HexTextMutedDark,
                modifier = modifier.size(12.dp)
            )
        }
        MessageDeliveryState.SENDING -> {
            Icon(
                imageVector = Icons.Default.ArrowUpward,
                contentDescription = "Transmitting",
                tint = HexPeriwinkle,
                modifier = modifier.size(12.dp)
            )
        }
        MessageDeliveryState.RELAYED -> {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Sent",
                tint = HexTextSecondaryDark,
                modifier = modifier.size(12.dp)
            )
        }
        MessageDeliveryState.DELIVERED -> {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = "Delivered",
                tint = HexPeriwinkle,
                modifier = modifier.size(13.dp)
            )
        }
        MessageDeliveryState.FAILED -> {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = "Failed",
                tint = HexCoral,
                modifier = modifier.size(12.dp)
            )
        }
    }
}

/**
 * Clean Empty State View.
 */
@Composable
fun HexEmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(HexSurfaceSubtleDark)
                .border(1.dp, HexBorderDark, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = HexPeriwinkle,
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = title,
            fontFamily = HexnetFontFamily,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = HexTextPrimaryDark,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = subtitle,
            fontFamily = HexnetFontFamily,
            fontSize = 13.sp,
            color = HexTextSecondaryDark,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 18.sp
        )

        if (actionLabel != null) {
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = HexPeriwinkle,
                    contentColor = Color(0xFF09090B)
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.height(42.dp)
            ) {
                Text(
                    text = actionLabel,
                    fontFamily = HexnetFontFamily,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
