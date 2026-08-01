package com.zeroclaw.android.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.model.OnDeviceTool

@Composable
fun OnDeviceToolRow(
    tools: List<OnDeviceTool>,
    onToolSelected: (OnDeviceTool) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(tools, key = { it.name }) { tool ->
            AssistChip(
                onClick = { onToolSelected(tool) },
                label = {
                    Text(
                        text = tool.label,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
            )
        }
    }
}
