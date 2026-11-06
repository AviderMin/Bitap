package com.bitap.keyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitap.keyboard.ui.theme.BitapTheme
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.ui.GlasmioButtonSize
import io.github.neoruaa.glasmio.ui.GlasmioCard
import io.github.neoruaa.glasmio.ui.GlasmioSwitch
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch

/**
 * Thin adapter over the two design systems. Layout and interaction live in Bitap;
 * only the surface/control material is swapped: Miuix "清透" vs Glasmio "流光".
 */
@Composable
fun BitapCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    if (BitapTheme.isGlass) {
        GlasmioCard(modifier = modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(contentPadding),
                content = content,
            )
        }
    } else {
        MiuixCard(
            modifier = modifier.fillMaxWidth(),
            cornerRadius = 24.dp,
            insideMargin = contentPadding,
        ) {
            content()
        }
    }
}

@Composable
fun BitapButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val palette = BitapTheme.palette
    if (BitapTheme.isGlass) {
        GlasmioButton(
            onClick = onClick,
            modifier = modifier.heightIn(min = 48.dp),
            enabled = enabled,
            size = if (compact) GlasmioButtonSize.Small else GlasmioButtonSize.Medium,
        ) {
            Text(
                text = text,
                color = if (enabled) palette.textPrimary else palette.textSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    } else {
        MiuixButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            cornerRadius = 14.dp,
            minHeight = 48.dp,
            colors = MiuixButtonDefaults.buttonColors(
                color = palette.accent,
                disabledColor = palette.functionKey,
                contentColor = palette.onAccent,
                disabledContentColor = palette.textSecondary,
            ),
        ) {
            Text(
                text = text,
                color = if (enabled) palette.onAccent else palette.textSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Quiet text action that works identically on both themes. */
@Composable
fun BitapTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val palette = BitapTheme.palette
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = when {
                !enabled -> palette.textSecondary
                danger -> palette.danger
                else -> palette.accent
            },
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun BitapSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (BitapTheme.isGlass) {
        GlasmioSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
        )
    } else {
        MiuixSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
        )
    }
}

@Composable
fun BitapSectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val palette = BitapTheme.palette
    Column(modifier = modifier.padding(top = 26.dp, bottom = 10.dp)) {
        Text(
            text = title,
            color = palette.textPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                color = palette.textSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
fun BitapSettingRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val palette = BitapTheme.palette
    val rowModifier = modifier
        .fillMaxWidth()
        .heightIn(min = 56.dp)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(vertical = 10.dp)
    Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = palette.textPrimary,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.Medium,
            )
            if (description != null) {
                Text(
                    text = description,
                    color = palette.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(12.dp))
            trailing()
        }
    }
}

/** Section separator that respects each theme's hairline token. */
@Composable
fun BitapDivider(modifier: Modifier = Modifier) {
    val palette = BitapTheme.palette
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .heightIn(min = 1.dp)
            .background(palette.hairline),
    )
}
