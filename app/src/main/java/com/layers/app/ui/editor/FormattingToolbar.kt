package com.layers.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.FormatItalic
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.FormatStrikethrough
import androidx.compose.material.icons.rounded.FormatUnderlined
import androidx.compose.material.icons.rounded.Highlight
import androidx.compose.material.icons.rounded.KeyboardHide
import androidx.compose.material.icons.rounded.Redo
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.layers.app.ui.theme.Paper
import com.mohamedrejeb.richeditor.model.RichTextState

/** Shared span styles, so toggling a style on and off matches exactly. */
object RichStyles {
    val bold = SpanStyle(fontWeight = FontWeight.Bold)
    val italic = SpanStyle(fontStyle = FontStyle.Italic)
    val underline = SpanStyle(textDecoration = TextDecoration.Underline)
    val strike = SpanStyle(textDecoration = TextDecoration.LineThrough)
    val highlight = SpanStyle(background = Color(0x80FFD54F))
    val h1 = SpanStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold)
    val h2 = SpanStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
}

/** Floats above the keyboard while writing. */
@Composable
fun FormattingToolbar(
    state: RichTextState?,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onInsert: () -> Unit,
    onChecklist: () -> Unit,
    onHideKeyboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = Paper.colors.sheet,
        shadowElevation = 10.dp,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton(Icons.Rounded.AddCircle, "Insert", tint = MaterialTheme.colorScheme.primary, onClick = onInsert)
            Divider()
            val style = state?.currentSpanStyle
            val enabled = state != null
            ToolButton(Icons.Rounded.FormatBold, "Bold", active = style?.fontWeight == FontWeight.Bold, enabled = enabled) {
                state?.toggleSpanStyle(RichStyles.bold)
            }
            ToolButton(Icons.Rounded.FormatItalic, "Italic", active = style?.fontStyle == FontStyle.Italic, enabled = enabled) {
                state?.toggleSpanStyle(RichStyles.italic)
            }
            ToolButton(
                Icons.Rounded.FormatUnderlined, "Underline",
                active = style?.textDecoration?.contains(TextDecoration.Underline) == true, enabled = enabled,
            ) { state?.toggleSpanStyle(RichStyles.underline) }
            ToolButton(
                Icons.Rounded.FormatStrikethrough, "Strikethrough",
                active = style?.textDecoration?.contains(TextDecoration.LineThrough) == true, enabled = enabled,
            ) { state?.toggleSpanStyle(RichStyles.strike) }
            ToolButton(
                Icons.Rounded.Highlight, "Highlight",
                active = style != null && style.background == RichStyles.highlight.background, enabled = enabled,
            ) { state?.toggleSpanStyle(RichStyles.highlight) }
            TextTool("H1", "Heading 1", active = style?.fontSize == RichStyles.h1.fontSize, enabled = enabled) {
                state?.toggleSpanStyle(RichStyles.h1)
            }
            TextTool("H2", "Heading 2", active = style?.fontSize == RichStyles.h2.fontSize, enabled = enabled) {
                state?.toggleSpanStyle(RichStyles.h2)
            }
            ToolButton(Icons.Rounded.FormatListBulleted, "Bulleted list", active = state?.isUnorderedList == true, enabled = enabled) {
                state?.toggleUnorderedList()
            }
            ToolButton(Icons.Rounded.FormatListNumbered, "Numbered list", active = state?.isOrderedList == true, enabled = enabled) {
                state?.toggleOrderedList()
            }
            ToolButton(Icons.Rounded.CheckBox, "Add checklist item", onClick = onChecklist)
            Divider()
            ToolButton(Icons.Rounded.Undo, "Undo", enabled = canUndo, onClick = onUndo)
            ToolButton(Icons.Rounded.Redo, "Redo", enabled = canRedo, onClick = onRedo)
            ToolButton(Icons.Rounded.KeyboardHide, "Hide keyboard", onClick = onHideKeyboard)
        }
    }
}

@Composable
private fun Divider() {
    Spacer(Modifier.width(4.dp))
    VerticalDivider(Modifier.height(24.dp), color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.width(4.dp))
}

@Composable
private fun ToolButton(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                selected = active
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = when {
                !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                active -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> tint
            },
        )
    }
}

@Composable
private fun TextTool(text: String, label: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                selected = active
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                active -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
