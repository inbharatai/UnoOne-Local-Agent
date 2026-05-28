package com.unoone.agent.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class ConfirmationLevel {
    CONFIRM,
    STRONG_CONFIRM
}

@Composable
fun ConfirmationDialog(
    message: String,
    level: ConfirmationLevel = ConfirmationLevel.CONFIRM,
    onResult: (Boolean) -> Unit
) {
    var confirmText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { onResult(false) },
        title = {
            Text(
                text = if (level == ConfirmationLevel.STRONG_CONFIRM) "Security Confirmation" else "Confirm Action",
                color = if (level == ConfirmationLevel.STRONG_CONFIRM) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.tertiary
            )
        },
        text = {
            Column {
                Text(text = message)
                if (level == ConfirmationLevel.STRONG_CONFIRM) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Type \"confirm\" to proceed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirmText,
                        onValueChange = { confirmText = it },
                        placeholder = { Text("Type confirm") },
                        singleLine = true,
                        modifier = Modifier.padding(0.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (level == ConfirmationLevel.STRONG_CONFIRM) {
                        onResult(confirmText.equals("confirm", ignoreCase = true))
                    } else {
                        onResult(true)
                    }
                },
                enabled = level != ConfirmationLevel.STRONG_CONFIRM || confirmText.equals("confirm", ignoreCase = true)
            ) {
                Text("Allow")
            }
        },
        dismissButton = {
            TextButton(onClick = { onResult(false) }) {
                Text("Deny")
            }
        }
    )
}