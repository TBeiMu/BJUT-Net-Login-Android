package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.tbeimu.gongxiaozhu.BuildConfig
import io.github.tbeimu.gongxiaozhu.update.UpdatePrompt
import java.util.Locale

/** Compose visual adaptation of FlyingKxz 1.4.10 lib/pages/app_upgrade.dart; see docs/界面设计.md. */
@Composable
internal fun UpdateVersionDialog(prompt: UpdatePrompt, onCancel: () -> Unit,
    onIgnore: () -> Unit, onUpdate: () -> Unit) {
    val palette = gxz
    val fontScale = LocalDensity.current.fontScale
    val stackedActions = fontScale >= 1.5f
    val muted = palette.inkSoft
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val limit = (maxHeight - 48.dp).coerceAtLeast(0.dp)
            val notesHeight = minOf(maxHeight * .5f, 360.dp)
            val compact = maxHeight < 480.dp || (fontScale >= 1.5f && maxHeight < 640.dp)
            Surface(Modifier.fillMaxWidth(.8f).widthIn(max = 420.dp).heightIn(max = limit).testTag("update_prompt"),
                shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = palette.ink) {
                Column(Modifier.padding(top = 26.dp, bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (compact) {
                        // Scroll the header with the notes when fixed header text would squeeze out the body.
                        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
                            .testTag("update_prompt_content"), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            UpdatePromptHeader(prompt, muted)
                            UpdatePromptNotes(prompt, muted)
                        }
                    } else {
                        UpdatePromptHeader(prompt, muted)
                        Column(Modifier.fillMaxWidth().weight(1f, fill = false).height(notesHeight)
                            .verticalScroll(rememberScrollState()).testTag("update_prompt_content")) {
                            UpdatePromptNotes(prompt, muted)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onCancel, Modifier.weight(1f).testTag("cancel_update_prompt"),
                            contentPadding = PaddingValues(2.dp)) {
                            Text(if (stackedActions) "取消\n更新" else "取消更新", color = muted,
                                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        }
                        TextButton(onClick = onIgnore, Modifier.weight(1.4f).testTag("ignore_update_prompt"),
                            contentPadding = PaddingValues(2.dp)) {
                            Text(if (stackedActions) "忽略本次\n更新" else "忽略本次更新", color = muted,
                                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        }
                        Button(onClick = onUpdate, Modifier.weight(1.15f).testTag("accept_update_prompt"),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = palette.ink, contentColor = palette.onInk)) {
                            Text(if (stackedActions) "立即\n更新" else "立即更新",
                                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdatePromptHeader(prompt: UpdatePrompt, muted: Color) {
    Text("发现最新版本！", Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    Text("${BuildConfig.VERSION_NAME} → ${prompt.manifest.versionName} · ${String.format(Locale.CHINA, "%.1f", prompt.manifest.apkSize / 1048576.0)} MB",
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        color = muted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
}

@Composable
private fun UpdatePromptNotes(prompt: UpdatePrompt, muted: Color) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(prompt.manifest.releaseNotes.ifBlank { "暂无更新说明" }, modifier = Modifier.testTag("update_prompt_notes"),
            color = muted, style = MaterialTheme.typography.bodyMedium)
        prompt.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
