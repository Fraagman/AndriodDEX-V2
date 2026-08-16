package com.example.androidhost.ui.apps

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.androidhost.ui.components.WindowChrome
import com.example.androidhost.vm.WindowState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FilesApp(
    windowState: WindowState,
    onClose: () -> Unit,
    onMinimize: () -> Unit,
    onMaximize: () -> Unit
) {
    val context = LocalContext.current
    val internalRoot = context.filesDir
    val externalRoot = context.getExternalFilesDir(null) ?: internalRoot

    var currentDir by remember { mutableStateOf(internalRoot) }
    var viewingFile by remember { mutableStateOf<File?>(null) }
    
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showRenameDialogFor by remember { mutableStateOf<File?>(null) }
    var showDeleteConfirmFor by remember { mutableStateOf<File?>(null) }

    // Rerender trigger for directory changes
    var refreshTrigger by remember { mutableIntStateOf(0) }
    
    val files = remember(currentDir, refreshTrigger) {
        currentDir.listFiles()?.toList()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
    }

    WindowChrome(
        windowState = windowState,
        onClose = onClose,
        onMinimize = onMinimize,
        onMaximize = onMaximize
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1E1E1E))
        ) {
            if (viewingFile != null) {
                FileViewer(
                    file = viewingFile!!,
                    onBack = { viewingFile = null }
                )
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Toolbar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF2A2A2A))
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(onClick = { currentDir = internalRoot }, modifier = Modifier.padding(end = 8.dp)) {
                            Text("Internal")
                        }
                        Button(onClick = { currentDir = externalRoot }, modifier = Modifier.padding(end = 8.dp)) {
                            Text("External")
                        }
                        
                        val isRoot = currentDir == internalRoot || currentDir == externalRoot
                        if (!isRoot) {
                            Button(onClick = { currentDir = currentDir.parentFile ?: internalRoot }, modifier = Modifier.padding(end = 8.dp)) {
                                Text("Up")
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))
                        
                        Button(onClick = { showCreateFolderDialog = true }) {
                            Text("New Folder")
                        }
                    }
                    
                    // Path
                    Text(
                        text = currentDir.absolutePath,
                        color = Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(8.dp)
                    )

                    // File List
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(files) { file ->
                            FileRow(
                                file = file,
                                onClick = {
                                    if (file.isDirectory) {
                                        currentDir = file
                                    } else {
                                        viewingFile = file
                                    }
                                },
                                onRename = { showRenameDialogFor = file },
                                onDelete = { showDeleteConfirmFor = file }
                            )
                        }
                        if (files.isEmpty()) {
                            item {
                                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                    Text("Folder is empty", color = Color.Gray)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("Create Folder") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (folderName.isNotBlank()) {
                        File(currentDir, folderName).mkdirs()
                        refreshTrigger++
                    }
                    showCreateFolderDialog = false
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showRenameDialogFor != null) {
        val file = showRenameDialogFor!!
        var newName by remember { mutableStateOf(file.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialogFor = null },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newName.isNotBlank() && newName != file.name) {
                        file.renameTo(File(file.parentFile, newName))
                        refreshTrigger++
                    }
                    showRenameDialogFor = null
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialogFor = null }) { Text("Cancel") }
            }
        )
    }

    if (showDeleteConfirmFor != null) {
        val file = showDeleteConfirmFor!!
        AlertDialog(
            onDismissRequest = { showDeleteConfirmFor = null },
            title = { Text("Delete ${file.name}?") },
            text = { Text("This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        if (file.isDirectory) file.deleteRecursively() else file.delete()
                        refreshTrigger++
                        showDeleteConfirmFor = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmFor = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun FileRow(file: File, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    val lastModified = sdf.format(Date(file.lastModified()))
    val sizeText = if (file.isDirectory) "" else formatSize(file.length())

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
            contentDescription = null,
            tint = if (file.isDirectory) Color(0xFFFFD54F) else Color.LightGray,
            modifier = Modifier.size(32.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name, color = Color.White, fontSize = 16.sp)
            Row {
                Text(lastModified, color = Color.Gray, fontSize = 12.sp)
                if (sizeText.isNotEmpty()) {
                    Text(" • $sizeText", color = Color.Gray, fontSize = 12.sp)
                }
            }
        }
        
        IconButton(onClick = onRename) {
            Icon(Icons.Default.Edit, contentDescription = "Rename", tint = Color.Gray)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Gray)
        }
    }
}

@Composable
private fun FileViewer(file: File, onBack: () -> Unit) {
    val textExtensions = setOf("txt", "md", "csv", "json", "xml", "log", "kt", "java", "rs")
    val canRead = file.extension.lowercase() in textExtensions
    
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF2A2A2A))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onBack) {
                Text("Back")
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(file.name, color = Color.White, fontWeight = FontWeight.Bold)
        }
        
        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            if (canRead) {
                val content = try {
                    file.readText()
                } catch (e: Exception) {
                    "Failed to read file: ${e.message}"
                }
                Text(
                    text = content,
                    color = Color.LightGray,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            } else {
                Text(
                    text = "Cannot preview this file type.",
                    color = Color.Gray,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.1f MB", mb)
}
