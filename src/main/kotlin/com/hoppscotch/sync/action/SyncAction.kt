package com.hoppscotch.sync.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.hoppscotch.sync.hoppscotch.HoppscotchClient
import com.hoppscotch.sync.model.ControllerGroup
import com.hoppscotch.sync.model.SyncResult
import com.hoppscotch.sync.psi.SpringControllerParser
import com.hoppscotch.sync.service.SyncService
import com.hoppscotch.sync.settings.AppSettings
import com.hoppscotch.sync.util.I18n
import javax.swing.SwingUtilities

class SyncAction : AnAction() {

    private val log = Logger.getInstance(SyncAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return

        // 1. Check settings
        val settings = AppSettings.getInstance()
        if (settings.serverUrl.isBlank() || settings.accessToken.isBlank()) {
            Messages.showWarningDialog(
                project,
                I18n.message("dialog.notConfigured.message"),
                "Hoppscotch Sync"
            )
            return
        }

        // 2. Parse controllers in background (with ReadAction)
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            I18n.message("progress.scanning.title"),
            false
        ) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = I18n.message("progress.indicator.scanning")

                val groups: List<ControllerGroup> = try {
                    var result: List<ControllerGroup> = emptyList()
                    ApplicationManager.getApplication().runReadAction {
                        result = SpringControllerParser(project).parseAllControllers()
                    }
                    result
                } catch (ex: Exception) {
                    log.warn("Controller scan failed", ex)
                    SwingUtilities.invokeLater {
                        Messages.showErrorDialog(
                            project,
                            I18n.message("dialog.scanError.message", ex.message ?: ""),
                            I18n.message("dialog.scanError.title")
                        )
                    }
                    return
                }

                val totalEndpoints = groups.sumOf { it.endpoints.size }

                SwingUtilities.invokeLater {
                    // 3. Confirm with user
                    if (groups.isEmpty()) {
                        Messages.showInfoMessage(
                            project,
                            "No Spring Boot controllers found in the project.",
                            I18n.message("dialog.scanError.title")
                        )
                        return@invokeLater
                    }

                    val result = Messages.showYesNoDialog(
                        project,
                        "Found ${groups.size} controllers with $totalEndpoints endpoints.\n\nProceed to sync to Hoppscotch?",
                        I18n.message("dialog.confirmSync.title"),
                        Messages.getQuestionIcon()
                    )

                    if (result != Messages.YES) return@invokeLater

                    // 4. Sync in background
                    ProgressManager.getInstance().run(object : Task.Backgroundable(
                        project,
                        I18n.message("progress.syncing.title"),
                        false
                    ) {
                        override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                            val client = HoppscotchClient(
                                serverUrl = settings.serverUrl,
                                accessToken = settings.accessToken,
                                refreshToken = settings.refreshToken,
                                onTokenRefreshed = { newAccess, newRefresh ->
                                    settings.accessToken = newAccess
                                    if (newRefresh != null) settings.refreshToken = newRefresh
                                }
                            )
                            // 如果有 refreshToken，优先刷新获取最新 access_token（防止已过期）
                            if (settings.refreshToken.isNotBlank()) {
                                client.tryRefreshSession()
                            }
                            val syncParser = SpringControllerParser(project)
                            val service = SyncService(project, client, syncParser, indicator)
                            // 如果有 target 集合，同步到 target 子集合下；否则同步到根层级
                            val targetId = settings.targetCollectionId.ifBlank { null }
                            val allGroups = syncParser.parseAllControllers()
                            val syncResult = service.syncGroups(
                                groups = allGroups,
                                targetParentCollectionId = targetId,
                                strategy = settings.getSyncStrategy(),
                                createSubDirectories = settings.createSubDirectory
                            )

                            SwingUtilities.invokeLater {
                                showSyncResult(project, syncResult)
                            }
                        }
                    })
                }
            }
        })
    }

    override fun update(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT)
        e.presentation.isEnabled = project != null
        e.presentation.isVisible = project != null
    }

    private fun showSyncResult(project: com.intellij.openapi.project.Project, result: SyncResult) {
        val message = buildString {
            appendLine(I18n.message("result.total", result.total))
            appendLine(I18n.message("result.success", result.success))
            appendLine(I18n.message("result.failed", result.failed))
            appendLine(I18n.message("result.collections", result.collectionsCreated))
            appendLine(I18n.message("result.requests", result.requestsCreated))
            if (result.errors.isNotEmpty()) {
                appendLine()
                appendLine(I18n.message("result.errors"))
                result.errors.forEach { appendLine("  - $it") }
            }
        }
        val title = I18n.message("dialog.syncResult.title")
        if (result.isSuccess) {
            Messages.showInfoMessage(project, message, title)
        } else {
            Messages.showErrorDialog(project, message, title)
        }
    }
}
