package com.firzen.dailyone

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable internal fun S3ConfigForm(value: S3Config, change: (S3Config) -> Unit, enabled: Boolean) {
    OutlinedTextField(value.endpoint, { change(value.copy(endpoint = it)) }, label = { Text("Endpoint（HTTPS）") },
        placeholder = { Text("https://s3.example.com") }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value.region, { change(value.copy(region = it)) }, label = { Text("Region") },
        placeholder = { Text("us-east-1") }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value.bucket, { change(value.copy(bucket = it)) }, label = { Text("Bucket") }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value.accessKey, { change(value.copy(accessKey = it)) }, label = { Text("Access Key ID") }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value.secretKey, { change(value.copy(secretKey = it)) }, label = { Text("Secret Access Key") },
        visualTransformation = PasswordVisualTransformation(), enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value.prefix, { change(value.copy(prefix = it)) }, label = { Text("存储前缀") },
        supportingText = { Text("默认 DailyOne/，留空使用 Bucket 根目录") }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
    var advanced by remember { mutableStateOf(false) }
    TextButton(onClick = { advanced = !advanced }, enabled = enabled) { Text(if (advanced) "收起高级设置" else "高级设置") }
    if (advanced) Row(Modifier.fillMaxWidth().toggleable(value.pathStyle, enabled = enabled, role = Role.Checkbox,
        onValueChange = { change(value.copy(pathStyle = it)) }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(value.pathStyle, null, enabled = enabled)
        Text("路径式访问（适配 MinIO 等服务）", Modifier.padding(start = 8.dp))
    }
}
