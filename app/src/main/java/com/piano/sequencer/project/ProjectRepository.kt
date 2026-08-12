package com.piano.sequencer.project

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class ProjectRepository(private val context: Context) {
    private val projectsDir = File(context.filesDir, "projects")
    private val workerThread = java.util.concurrent.Executors.newSingleThreadExecutor()

    // SAF project directory URI (optional)
    private var projectUri: Uri? = null

    init {
        if (!projectsDir.exists()) {
            projectsDir.mkdirs()
        }
    }

    // Set SAF project directory URI
    fun setProjectUri(uri: Uri) {
        projectUri = uri
    }

    fun getProjectDirectory(): File {
        return projectUri?.let { uri ->
            // For SAF, fall back to internal storage
            // Full SAF integration would use DocumentFile API
            projectsDir
        } ?: projectsDir
    }

    fun saveProject(project: Project, callback: (Result<Unit>) -> Unit) {
        workerThread.submit {
            try {
                val file = getProjectFile(project.id)
                val json = ProjectSerializer.toJson(project)
                file.writeText(json)
                callback(Result.success(Unit))
            } catch (e: IOException) {
                callback(Result.failure(e))
            }
        }
    }

    fun loadProject(projectId: String, callback: (Result<Project>) -> Unit) {
        workerThread.submit {
            try {
                val file = getProjectFile(projectId)
                if (!file.exists()) {
                    callback(Result.failure(IOException("Project not found: $projectId")))
                    return@submit
                }
                val json = file.readText()
                val project = ProjectSerializer.fromJson(json)
                callback(Result.success(project))
            } catch (e: IOException) {
                callback(Result.failure(e))
            }
        }
    }

    fun listProjects(callback: (List<Project>) -> Unit) {
        workerThread.submit {
            val projects = projectsDir.listFiles { _, name -> name.endsWith(".json") }
                ?.mapNotNull { file ->
                    try {
                        ProjectSerializer.fromJson(file.readText())
                    } catch (e: Exception) {
                        null
                    }
                }?.sortedByDescending { it.updatedAt } ?: emptyList()
            callback(projects)
        }
    }

    fun deleteProject(projectId: String, callback: (Result<Unit>) -> Unit) {
        workerThread.submit {
            try {
                val file = getProjectFile(projectId)
                if (file.exists()) file.delete()
                callback(Result.success(Unit))
            } catch (e: IOException) {
                callback(Result.failure(e))
            }
        }
    }

    private fun getProjectFile(projectId: String): File {
        return File(projectsDir, "$projectId.json")
    }

    fun shutdown() {
        workerThread.shutdown()
    }

    // Import a resource file (MIDI, audio, etc.) into the project directory
    fun importResource(sourceUri: Uri, fileName: String, callback: (Result<String>) -> Unit) {
        workerThread.submit {
            try {
                val projectDir = getProjectDirectory()
                val destPath = File(projectDir, fileName)

                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    FileOutputStream(destPath).use { output ->
                        input.copyTo(output)
                    }
                } ?: throw IOException("Could not open input stream for $sourceUri")

                callback(Result.success(fileName))
            } catch (e: IOException) {
                callback(Result.failure(e))
            }
        }
    }

    // Autosave support
    private var lastAutosaveTime = 0L
    private val autosaveIntervalMs = 30000L // 30 seconds

    fun scheduleAutosave(project: Project, callback: (Result<Unit>) -> Unit) {
        val now = System.currentTimeMillis()
        if (now - lastAutosaveTime >= autosaveIntervalMs) {
            saveProject(project) { result ->
                lastAutosaveTime = System.currentTimeMillis()
                callback(result)
            }
        } else {
            callback(Result.success(Unit))
        }
    }

    fun getLastAutosaveTime(): Long = lastAutosaveTime
}