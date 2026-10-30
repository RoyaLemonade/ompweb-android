package io.github.kahme247.ompweb.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kahme247.ompweb.R
import io.github.kahme247.ompweb.data.ChatTemplate
import io.github.kahme247.ompweb.data.TemplateStore
import io.github.kahme247.ompweb.data.templateCommands
import io.github.kahme247.ompweb.ui.theme.LocalOmpColors
import io.github.kahme247.ompweb.ui.theme.OmpAlertDialog
import io.github.kahme247.ompweb.ui.theme.OmpMonoFontFamily

/** The open editor: `id == null` = a new template. */
private data class TemplateEdit(val id: String?, val title: String, val text: String)

/**
 * Settings → General: this device's snippets. Add, edit, delete and reorder
 * them here; the chat composer reads the same [TemplateStore], so a change is
 * visible in the `/` palette and the `+` menu without reopening the chat.
 *
 * Templates are per device (not per server origin) and are not cleared on sign
 * out: they are wording the user wrote, not installation state.
 */
@Composable
fun TemplatesSettingsSection(
    templates: TemplateStore,
    highlighted: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val items by templates.state.collectAsStateWithLifecycle()
    val colors = LocalOmpColors.current
    val commands = remember(items) { templateCommands(items) }
    var editing by remember { mutableStateOf<TemplateEdit?>(null) }
    var deleting by remember { mutableStateOf<ChatTemplate?>(null) }

    SettingsCard(highlighted = highlighted, modifier = modifier.testTag("settings-templates")) {
        SettingsSectionTitle(stringResource(R.string.settings_templates_title))
        Text(
            text = stringResource(R.string.settings_templates_desc),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textMuted,
        )
        if (items.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_templates_empty),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textDim,
            )
        }
        items.forEach { template ->
            val command = commands.firstOrNull { it.template.id == template.id }?.name.orEmpty()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = template.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.text,
                    )
                    if (command.isNotEmpty()) {
                        Text(
                            text = "/$command",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.accent,
                            fontFamily = OmpMonoFontFamily,
                        )
                    }
                    Text(
                        text = template.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = { templates.move(template.id, -1) },
                    enabled = items.firstOrNull()?.id != template.id,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.settings_templates_move_up, template.title),
                        tint = colors.textDim,
                    )
                }
                IconButton(
                    onClick = { templates.move(template.id, 1) },
                    enabled = items.lastOrNull()?.id != template.id,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.settings_templates_move_down, template.title),
                        tint = colors.textDim,
                    )
                }
                IconButton(
                    onClick = { editing = TemplateEdit(template.id, template.title, template.text) },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.settings_templates_edit_row, template.title),
                        tint = colors.textDim,
                    )
                }
                IconButton(
                    onClick = { deleting = template },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.settings_templates_delete_row, template.title),
                        tint = colors.textDim,
                    )
                }
            }
        }
        TextButton(onClick = { editing = TemplateEdit(null, "", "") }) {
            Text(stringResource(R.string.settings_templates_add), fontSize = 12.sp)
        }
    }

    editing?.let { edit ->
        var title by remember(edit) { mutableStateOf(edit.title) }
        var text by remember(edit) { mutableStateOf(edit.text) }
        OmpAlertDialog(
            onDismissRequest = { editing = null },
            title = {
                Text(
                    stringResource(
                        if (edit.id == null) R.string.settings_templates_new else R.string.settings_templates_edit,
                    ),
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text(stringResource(R.string.settings_templates_title_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text(stringResource(R.string.settings_templates_text_label)) },
                        minLines = 4,
                        maxLines = 10,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = title.isNotBlank() && text.isNotBlank(),
                    onClick = {
                        if (edit.id == null) {
                            templates.add(title, text)
                        } else {
                            templates.update(edit.id, title, text)
                        }
                        editing = null
                    },
                ) {
                    Text(stringResource(R.string.settings_templates_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) {
                    Text(stringResource(R.string.composer_cancel))
                }
            },
        )
    }

    deleting?.let { template ->
        OmpAlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.settings_templates_delete)) },
            text = { Text(stringResource(R.string.settings_templates_delete_body, template.title)) },
            confirmButton = {
                TextButton(onClick = {
                    templates.delete(template.id)
                    deleting = null
                }) {
                    Text(stringResource(R.string.settings_templates_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.composer_cancel))
                }
            },
        )
    }
}
