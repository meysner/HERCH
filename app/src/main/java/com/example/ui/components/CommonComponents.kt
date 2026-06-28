package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DrawerMenuItem(
    icon: ImageVector,
    text: String,
    showChevronRight: Boolean = false,
    showChevronDown: Boolean = false,
    isExpanded: Boolean = false,
    trailingIcon: ImageVector? = null,
    onClick: () -> Unit = {}
) {
    val rotation by animateFloatAsState(
        targetValue = if (showChevronDown && isExpanded) 180f else 0f,
        label = "chevronRotation"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
        Spacer(modifier = Modifier.width(20.dp))
        Text(
            text = text,
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )

        if (showChevronRight) {
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = Color(0xFF888888))
        }
        if (showChevronDown) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = Color(0xFF888888),
                modifier = Modifier.graphicsLayer { rotationZ = rotation }
            )
        }
        if (trailingIcon != null) {
            Spacer(modifier = Modifier.width(16.dp))
            Icon(trailingIcon, contentDescription = null, tint = Color(0xFF888888), modifier = Modifier.size(20.dp))
        }
    }
}

enum class ReasoningLevel { NONE, LOW, MEDIUM, HIGH, EXTRA_HIGH }

@Composable
fun ReasoningBars(level: ReasoningLevel) {
    val isExtraHigh = level == ReasoningLevel.EXTRA_HIGH
    val activeColor = if (isExtraHigh) Color(0xFFAB7FF5) else Color(0xFFCCCCCC)
    val inactiveColor = Color(0xFF444444)

    val bar1 = if (level != ReasoningLevel.NONE) activeColor else inactiveColor
    val bar2 = if (level == ReasoningLevel.MEDIUM || level == ReasoningLevel.HIGH || isExtraHigh) activeColor else inactiveColor
    val bar3 = if (level == ReasoningLevel.HIGH || isExtraHigh) activeColor else inactiveColor

    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(modifier = Modifier.width(3.dp).height(8.dp).clip(RoundedCornerShape(1.5.dp)).background(bar1))
        Box(modifier = Modifier.width(3.dp).height(12.dp).clip(RoundedCornerShape(1.5.dp)).background(bar2))
        Box(modifier = Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(1.5.dp)).background(bar3))
    }
}

@Composable
fun ReasoningButton(level: ReasoningLevel, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            val iconTint = if (level == ReasoningLevel.EXTRA_HIGH) Color(0xFFAB7FF5) else Color(0xFF888888)
            Icon(
                Icons.Outlined.Psychology,
                contentDescription = "Reasoning",
                tint = iconTint,
                modifier = Modifier.size(18.dp)
            )
            ReasoningBars(level)
        }
    }
}
