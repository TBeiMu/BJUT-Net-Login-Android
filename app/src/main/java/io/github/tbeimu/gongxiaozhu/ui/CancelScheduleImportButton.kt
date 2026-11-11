package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.tbeimu.gongxiaozhu.schedule.ScheduleUiState

@Composable
internal fun CancelScheduleImportButton(state: ScheduleUiState, onConfirm: () -> Unit,
    confirming: Boolean, onConfirmingChange: (Boolean) -> Unit) {
    if (!state.canCancelImport) return
    TextButton(onClick = { onConfirmingChange(true) }, modifier = Modifier.testTag("cancel_schedule_import")) { Text("取消本次导入") }
    if (confirming) AlertDialog(onDismissRequest = { onConfirmingChange(false) },
        title = { Text("取消本次课表导入？") },
        text = { Text("本次尚未完成的导入进度将被清除。已保存的完整课表、课表绑定和校园网账号会保留。") },
        confirmButton = { TextButton(onClick = { onConfirmingChange(false); onConfirm() }, modifier = Modifier.testTag("confirm_cancel_schedule_import")) { Text("确认取消导入") } },
        dismissButton = { TextButton(onClick = { onConfirmingChange(false) }) { Text("保留进度") } })
}
