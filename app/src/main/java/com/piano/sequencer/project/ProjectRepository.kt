package com.piano.sequencer.project

import android.content.Context
import java.io.File
import java.io.IOException

class ProjectRepository(private val context: Context) {
    private val projectsDir = File(context.filesDir, "projects")
    private val workerThread = java.util.concurrent.Executors.newSingleThreadExecutor()

    init {
        if (!projectsDir.exists()) {
            projectsDir.mkdirs()
        }
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
}