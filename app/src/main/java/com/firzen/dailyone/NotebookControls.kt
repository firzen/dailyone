package com.firzen.dailyone

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable internal fun NotebookPicker(books: List<Notebook>, selected: String?, all: Boolean = false, choose: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text(books.firstOrNull { it.id == selected }?.name ?: if (all) "全部日记 ▾" else "选择日记本 ▾") }
        DropdownMenu(expanded, { expanded = false }) {
            if (all) DropdownMenuItem(text = { Text("全部日记") }, onClick = { choose(null); expanded = false })
            books.forEach { book -> DropdownMenuItem(text = { Text(book.name) }, onClick = { choose(book.id); expanded = false }) }
        }
    }
}
@Composable internal fun NotebookMenu(books: List<Notebook>, selected: String?, enabled: Boolean, choose: (String?) -> Unit, save: (String, String?) -> Unit) {
    var dialog by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled,
            colors = ButtonDefaults.textButtonColors(contentColor = Color.White, disabledContentColor = Color.White.copy(alpha = .5f))) {
            Icon(Icons.Outlined.MenuBook, null, Modifier.size(22.dp))
            Text(books.firstOrNull { it.id == selected }?.name ?: "全部日记",
                Modifier.padding(start = 10.dp).weight(1f, fill = false), fontSize = 20.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Outlined.ArrowDropDown, "切换或管理日记本", Modifier.size(22.dp))
        }
        DropdownMenu(expanded && enabled, { expanded = false }) {
            DropdownMenuItem(text = { Text("全部日记") }, onClick = { expanded = false; choose(null) })
            books.forEach { book ->
                DropdownMenuItem(text = { Text(book.name) }, onClick = { expanded = false; choose(book.id) })
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("新建日记本") }, onClick = { expanded = false; rename = false; name = ""; dialog = true })
            if (selected != null) DropdownMenuItem(text = { Text("重命名日记本") }, onClick = {
                expanded = false; rename = true; name = books.firstOrNull { it.id == selected }?.name.orEmpty(); dialog = true
            })
        }
    }
    if (dialog) AlertDialog(onDismissRequest = { dialog = false }, title = { Text(if (rename) "重命名日记本" else "新建日记本") },
        text = { OutlinedTextField(name, { name = it.take(100) }, label = { Text("日记本名称") }, singleLine = true) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { save(name, if (rename) selected else null); dialog = false }) { Text("保存") } },
        dismissButton = { TextButton(onClick = { dialog = false }) { Text("取消") } })
}
