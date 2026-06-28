package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalBottomSheet(
    onRespond: (choice: String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var responded by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = {
            if (!responded) {
                onRespond("deny")
            }
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = Color(0xFF1E1E1E),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.Gray) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp, top = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = Color(0xFFFFD700), modifier = Modifier.size(28.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text("Требуется подтверждение", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Агент запрашивает разрешение на выполнение потенциально опасного действия.",
                color = Color(0xFFCCCCCC),
                fontSize = 16.sp,
                lineHeight = 22.sp
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { responded = true; onRespond("deny"); onDismiss() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF444444)),
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Запретить", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
                Button(
                    onClick = { responded = true; onRespond("once"); onDismiss() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD700)),
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Разрешить", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClarifyBottomSheet(
    question: String,
    choices: List<String> = emptyList(),
    onRespond: (response: String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var answerText by remember { mutableStateOf("") }
    var responded by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    ModalBottomSheet(
        onDismissRequest = {
            if (!responded) {
                onRespond("Пользователь отказался отвечать")
            }
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = Color(0xFF1E1E1E),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.Gray) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .imePadding()
                .verticalScroll(scrollState)
                .padding(bottom = 32.dp, top = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.HelpOutline, contentDescription = null, tint = Color(0xFF64B5F6), modifier = Modifier.size(28.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text("Уточнение", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(question, color = Color(0xFFCCCCCC), fontSize = 16.sp, lineHeight = 22.sp)
            Spacer(modifier = Modifier.height(20.dp))

            if (choices.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    choices.forEach { choice ->
                        OutlinedButton(
                            onClick = {
                                responded = true
                                onRespond(choice)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            border = BorderStroke(1.dp, Color(0xFF64B5F6).copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(choice, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Или введите свой ответ:",
                    color = Color.Gray,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            OutlinedTextField(
                value = answerText,
                onValueChange = { answerText = it },
                placeholder = { Text("Собственный вариант...") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF64B5F6),
                    unfocusedBorderColor = Color.DarkGray,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    responded = true
                    onRespond(answerText)
                    onDismiss()
                },
                enabled = answerText.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF64B5F6),
                    disabledContainerColor = Color(0xFF444444)
                ),
                modifier = Modifier.align(Alignment.End).height(44.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    "Ответить",
                    color = if (answerText.isNotBlank()) Color.Black else Color.Gray,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionBottomSheet(
    title: String,
    items: List<String>,
    selectedItem: String?,
    onItemSelected: (String?) -> Unit,
    onDismiss: () -> Unit,
    formatItemName: (String) -> String = { it },
    onAddWorkspace: (() -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1E1E1E),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.Gray) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp, top = 8.dp)
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onItemSelected(null); onDismiss() }
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "По умолчанию",
                    color = if (selectedItem == null) Color(0xFFFFD700) else Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f)
                )
                if (selectedItem == null) {
                    Icon(Icons.Outlined.Check, contentDescription = null, tint = Color(0xFFFFD700))
                }
            }

            HorizontalDivider(color = Color(0xFF2C2C2C))

            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                items(items) { item ->
                    val isSelected = item == selectedItem
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onItemSelected(item); onDismiss() }
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatItemName(item),
                            color = if (isSelected) Color(0xFFFFD700) else Color(0xFFCCCCCC),
                            fontSize = 16.sp,
                            modifier = Modifier.weight(1f)
                        )
                        if (isSelected) {
                            Icon(Icons.Outlined.Check, contentDescription = null, tint = Color(0xFFFFD700))
                        }
                    }
                }
            }

            if (onAddWorkspace != null) {
                HorizontalDivider(color = Color(0xFF2C2C2C))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAddWorkspace() }
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, tint = Color(0xFFFFD700), modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Добавить workspace", color = Color(0xFFFFD700), fontSize = 16.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectionSheet(
    models: List<com.example.data.ModelInfo>,
    selectedModel: com.example.data.ModelInfo?,
    onModelSelected: (com.example.data.ModelInfo?) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1E1E1E),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.Gray) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp, top = 8.dp)
        ) {
            Text(
                text = "Выберите модель",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onModelSelected(null); onDismiss() }
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "По умолчанию",
                    color = if (selectedModel == null) Color(0xFFFFD700) else Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f)
                )
                if (selectedModel == null) {
                    Icon(Icons.Outlined.Check, contentDescription = null, tint = Color(0xFFFFD700))
                }
            }

            HorizontalDivider(color = Color(0xFF2C2C2C))

            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                items(models) { model ->
                    val isSelected = model.id == selectedModel?.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onModelSelected(model); onDismiss() }
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = model.label,
                                color = if (isSelected) Color(0xFFFFD700) else Color.White,
                                fontSize = 16.sp
                            )
                            if (model.provider.isNotBlank()) {
                                Text(
                                    text = model.provider,
                                    color = Color(0xFF666666),
                                    fontSize = 13.sp
                                )
                            }
                        }
                        if (isSelected) {
                            Icon(Icons.Outlined.Check, contentDescription = null, tint = Color(0xFFFFD700))
                        }
                    }
                }
            }
        }
    }
}
