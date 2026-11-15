package app.loki.vecthere.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.loki.kit.SheetDialog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Prefs dialog category fields: translated from `prefs_dialog.rs` (fn `category_fields`, line 475) by egui2compose.
 *
 * The controls and their order are the desktop dialog's. NOT translated: the starting values (the
 * desktop reads them from its own state), anything it draws by hand, and enabling rules. The
 * parameter names are guessed from the Rust fields each control was bound to: check them against
 * the command's params doc before trusting OK.
 *   command: (not found)
 *   params:  (unknown)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrefsDialogCategoryFieldsDialog(onRun: (command: String, params: JsonObject) -> Unit, onDismiss: () -> Unit) {
    var f0 by remember { mutableStateOf(0.0f) }
    var f1 by remember { mutableStateOf(0.0f) }
    var f2 by remember { mutableStateOf("") }
    var f3 by remember { mutableStateOf("") }
    var f4 by remember { mutableStateOf("") }
    SheetDialog(
        "Prefs dialog category fields", "", onDismiss,
        buttons = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            TextButton(onClick = {
                val params = buildJsonObject {
                put("x", f0)
                put("x", f1)
                if (f2.isNotBlank()) put("rgb", f2)
                if (f3.isNotBlank()) put("value", f3)
                if (f4.isNotBlank()) put("value", f4)
                }
                /* TODO: which command this dialog runs was not found */
                onDismiss()
            }) { Text("OK") }
        },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Applies the next time VectorCraft starts." + ": " + (kotlin.math.round(f0 * 100f) / 100f), style = MaterialTheme.typography.labelLarge)
            Slider(f0, { f0 = it }, valueRange = minOf(0.0f, 1.0f)..maxOf(0.0f, 1.0f))
            Text("X" + ": " + (kotlin.math.round(f1 * 100f) / 100f), style = MaterialTheme.typography.labelLarge)
            Slider(f1, { f1 = it }, valueRange = minOf(0.0f, 1.0f)..maxOf(0.0f, 1.0f))
            OutlinedTextField(f2, { f2 = it }, label = { Text("If a drawing tablet's pen doesn't draw, choose an X11 (XWayland) option.") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(f3, { f3 = it }, label = { Text("Value") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(f4, { f4 = it }, label = { Text("Value") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            // egui button "Choose…"
        }
    }
}
