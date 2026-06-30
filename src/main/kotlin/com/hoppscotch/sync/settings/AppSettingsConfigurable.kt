package com.hoppscotch.sync.settings

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.Configurable
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import com.hoppscotch.sync.hoppscotch.HoppscotchClient
import com.hoppscotch.sync.hoppscotch.HoppscotchVersionChecker
import com.hoppscotch.sync.model.LogLevel
import com.hoppscotch.sync.model.SyncStrategy
import com.hoppscotch.sync.util.I18n
import com.hoppscotch.sync.util.LogUtil
import java.awt.Color
import java.awt.Component
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.SwingUtilities

class AppSettingsConfigurable : Configurable {

    companion object {
        private val LOG = Logger.getInstance(AppSettingsConfigurable::class.java)
    }

    private var serverUrlField = JBTextField().apply { columns = 50 }
    private var accessTokenField = JBPasswordField().apply { columns = 50 }
    private var languageCombo = JComboBox(arrayOf("English", "中文"))
    private var showTokensCheckbox = JBCheckBox(I18n.message("settings.showTokens")).apply { isSelected = false }
    private var verifyStatusLabel = JBLabel().apply { isVisible = false }
    private var syncStrategyCombo = JComboBox<SyncStrategy>()
    private var syncStrategyDescLabel = JBLabel()
    private var logLevelCombo = JComboBox<LogLevel>()
    private lateinit var verifyButton: JButton

    // 版本信息组件
    private var versionStatusLabel = JBLabel().apply { isVisible = false }
    private var schemaVersionLabel = JBLabel()
    private var pluginSchemaVersionLabel = JBLabel()
    private var serverReachableLabel = JBLabel()
    private var supportedSchemasLabel = JBLabel()
    private lateinit var checkVersionButton: JButton

    // 校验开关
    private var validationEnabledCheckbox = JBCheckBox(
        I18n.message("settings.validation.enabled")
    )

    init {
        showTokensCheckbox.addActionListener {
            val echoChar = if (showTokensCheckbox.isSelected) '\u0000' else '•'
            accessTokenField.echoChar = echoChar
        }
    }

    override fun getDisplayName(): String = "Hoppscotch Sync"

    override fun createComponent(): JComponent {
        val settings = AppSettings.getInstance()
        return panel {
            group(I18n.message("settings.group.server")) {
                row(I18n.message("settings.serverUrl")) {
                    cell(serverUrlField)
                        .focused()
                }
                row(I18n.message("settings.accessToken")) {
                    cell(accessTokenField)
                }
                row("") {
                    cell(showTokensCheckbox)
                        .comment(I18n.message("settings.showTokens.comment"))
                    button(I18n.message("settings.verify")) {
                        verifyTokens()
                    }.also { verifyButton = it.component }
                    cell(verifyStatusLabel)
                }
            }
            group(I18n.message("settings.group.language")) {
                row(I18n.message("settings.language")) {
                    cell(languageCombo)
                }
                row {
                    comment(I18n.message("settings.language.comment"))
                }
            }
            group(I18n.message("settings.group.strategy")) {
                row(I18n.message("settings.strategy")) {
                    cell(syncStrategyCombo)
                }
                row {
                    cell(syncStrategyDescLabel)
                }
            }
            group(I18n.message("settings.versionInfo.title")) {
                row(I18n.message("settings.versionInfo.pluginSchemaVersion")) {
                    cell(pluginSchemaVersionLabel)
                }
                row(I18n.message("settings.versionInfo.schemaVersion")) {
                    cell(schemaVersionLabel)
                }
                row(I18n.message("settings.versionInfo.serverReachable")) {
                    cell(serverReachableLabel)
                }
                row(I18n.message("settings.versionInfo.supportedSchemas")) {
                    cell(supportedSchemasLabel)
                }
                row("") {
                    button(I18n.message("settings.versionInfo.checkNow")) {
                        checkServerVersion()
                    }.also { checkVersionButton = it.component }
                    cell(versionStatusLabel)
                }
            }
            group(I18n.message("settings.group.logging")) {
                row(I18n.message("settings.logLevel")) {
                    cell(logLevelCombo)
                }
                row {
                    comment(I18n.message("settings.logging.comment"))
                }
                row {
                    cell(validationEnabledCheckbox)
                        .comment(I18n.message("settings.validation.enabled.comment"))
                }
            }
        }.also {
            // ---- 初始化各字段 ----
            serverUrlField.text = settings.serverUrl
            accessTokenField.text = settings.accessToken
            languageCombo.selectedItem = if (settings.language == "zh") "中文" else "English"

            // 同步策略
            syncStrategyCombo.model = DefaultComboBoxModel(SyncStrategy.entries.toTypedArray())
            syncStrategyCombo.renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
                    return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).apply {
                        val strategy = value as? SyncStrategy
                        text = if (AppSettings.getInstance().language == "zh") strategy?.labelZh ?: ""
                               else strategy?.label ?: ""
                    }
                }
            }
            syncStrategyCombo.selectedItem = settings.getSyncStrategy()
            syncStrategyCombo.addActionListener {
                val selected = syncStrategyCombo.selectedItem as SyncStrategy
                syncStrategyDescLabel.text = if (settings.language == "zh") selected.descriptionZh else selected.description
            }
            syncStrategyDescLabel.text = if (settings.language == "zh") settings.getSyncStrategy().descriptionZh
                                         else settings.getSyncStrategy().description

            // 日志级别
            logLevelCombo.model = DefaultComboBoxModel(LogLevel.entries.toTypedArray())
            logLevelCombo.renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
                    return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).apply {
                        val level = value as? LogLevel
                        text = if (AppSettings.getInstance().language == "zh") level?.labelZh ?: "" else level?.label ?: ""
                    }
                }
            }
            logLevelCombo.selectedItem = LogLevel.fromId(settings.logLevel)

            // 版本信息
            updateVersionDisplay()

            // 校验开关
            validationEnabledCheckbox.isSelected = settings.requestValidationEnabled
        }
    }

    override fun isModified(): Boolean {
        val settings = AppSettings.getInstance()
        val langChanged = (languageCombo.selectedItem == "中文") != (settings.language == "zh")
        val strategyChanged = (syncStrategyCombo.selectedItem as? SyncStrategy) != settings.getSyncStrategy()
        val logLevelChanged = (logLevelCombo.selectedItem as? LogLevel)?.name != settings.logLevel
        val validationChanged = validationEnabledCheckbox.isSelected != settings.requestValidationEnabled
        return serverUrlField.text != settings.serverUrl
                || String(accessTokenField.password) != settings.accessToken
                || langChanged
                || strategyChanged
                || logLevelChanged
                || validationChanged
    }

    override fun apply() {
        val settings = AppSettings.getInstance()
        settings.serverUrl = serverUrlField.text
        settings.accessToken = String(accessTokenField.password)
        settings.language = if (languageCombo.selectedItem == "中文") "zh" else "en"
        val selectedStrategy = syncStrategyCombo.selectedItem as? SyncStrategy
        if (selectedStrategy != null) settings.setSyncStrategy(selectedStrategy)
        val selectedLogLevel = logLevelCombo.selectedItem as? LogLevel
        if (selectedLogLevel != null) settings.logLevel = selectedLogLevel.name
        settings.requestValidationEnabled = validationEnabledCheckbox.isSelected
    }

    override fun reset() {
        val settings = AppSettings.getInstance()
        serverUrlField.text = settings.serverUrl
        accessTokenField.text = settings.accessToken
        languageCombo.selectedItem = if (settings.language == "zh") "中文" else "English"
        syncStrategyCombo.selectedItem = settings.getSyncStrategy()
        syncStrategyDescLabel.text = settings.getSyncStrategy().description
        logLevelCombo.selectedItem = LogLevel.fromId(settings.logLevel)
        validationEnabledCheckbox.isSelected = settings.requestValidationEnabled
        updateVersionDisplay()
    }

    // ====================================================================
    //  版本检测
    // ====================================================================

    /**
     * 检测服务端版本并更新显示。
     */
    private fun checkServerVersion() {
        val url = serverUrlField.text.trim()
        if (url.isBlank()) {
            showVersionResult(I18n.message("settings.verify.enterUrl"))
            return
        }

        checkVersionButton.isEnabled = false
        showVersionResult(I18n.message("settings.verify.verifying"))

        Thread(Runnable {
            try {
                val health = HoppscotchVersionChecker.checkServerHealth(url)
                val schemaVersion = HoppscotchVersionChecker.resolveSchemaVersion(health)
                val supported = HoppscotchVersionChecker.isSchemaSupported(schemaVersion)

                // 持久化检测结果
                SwingUtilities.invokeLater {
                    val settings = AppSettings.getInstance()
                    settings.serverSchemaVersion = schemaVersion
                    settings.serverVersionCheckedAt = System.currentTimeMillis()

                    updateVersionDisplay()
                    checkVersionButton.isEnabled = true

                    val msg = if (health.reachable)
                        "✅ ${I18n.message("settings.verify.success")} (${health.elapsedMs}ms)"
                    else
                        "❌ ${I18n.message("settings.verify.enterUrl")}"
                    showVersionResult(msg)
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    checkVersionButton.isEnabled = true
                    showVersionResult("❌ ${e.message}")
                }
            }
        }, "HS-check-version").apply { isDaemon = true; start() }
    }

    /**
     * 更新版本信息显示区域。
     */
    private fun updateVersionDisplay() {
        val settings = AppSettings.getInstance()
        val isZh = settings.language == "zh"

        pluginSchemaVersionLabel.text = I18n.message("settings.versionInfo.pluginSchemaVersion.value",
            HoppscotchVersionChecker.DEFAULT_SCHEMA_VERSION)

        val detectedVersion = settings.serverSchemaVersion.ifBlank {
            if (isZh) "未检测" else "Not checked"
        }
        schemaVersionLabel.text = if (settings.serverSchemaVersion.isNotBlank()) "v${settings.serverSchemaVersion}"
                                  else if (isZh) "未检测" else "Not checked"

        val serverStatus = if (settings.serverVersionCheckedAt > 0) {
            // 用 serverSchemaVersion 非空推断可达
            if (settings.serverSchemaVersion.isNotBlank())
                "✅ ${I18n.message("settings.versionInfo.serverReachable.yes")}"
            else
                "❌ ${I18n.message("settings.versionInfo.serverReachable.no")}"
        } else {
            if (isZh) "未检测" else "Not checked"
        }
        serverReachableLabel.text = serverStatus

        supportedSchemasLabel.text = HoppscotchVersionChecker.getSupportedSchemaVersions()

        if (settings.serverVersionCheckedAt > 0) {
            versionStatusLabel.text = I18n.message("settings.versionInfo.lastChecked") + ": " +
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date(settings.serverVersionCheckedAt))
            versionStatusLabel.isVisible = true
        } else {
            versionStatusLabel.isVisible = false
        }
    }

    private fun showVersionResult(msg: String) {
        versionStatusLabel.text = msg
        versionStatusLabel.isVisible = true
    }

    // ====================================================================
    //  Token 验证（原有）
    // ====================================================================

    /** 验证 token 有效性，结果直接显示在按钮下方的状态文字上。 */
    private fun verifyTokens() {
        LOG.info("=== verifyTokens() called ===")
        LogUtil.stdout { "[HOPPSCOTCH] verifyTokens() called" }

        val btnReady = ::verifyButton.isInitialized
        LOG.info("verifyButton initialized=$btnReady, enabled=${if (btnReady) verifyButton.isEnabled else "N/A"}")
        LogUtil.stdout { "[HOPPSCOTCH] verifyButton initialized=$btnReady" }

        if (!btnReady || !verifyButton.isEnabled) {
            LOG.warn("verifyTokens: button not ready or already disabled, returning")
            return
        }

        val url = serverUrlField.text.trim()
        val at = String(accessTokenField.password).trim()
        LOG.info("url='$url' at.length=${at.length}")

        if (url.isBlank()) {
            showVerifyResult(false, I18n.message("settings.verify.enterUrl"))
            return
        }
        if (at.isBlank()) {
            showVerifyResult(false, I18n.message("settings.verify.enterToken"))
            return
        }

        verifyButton.isEnabled = false
        showVerifyResult(null, I18n.message("settings.verify.verifying"))
        LOG.info("Starting HS-verify-tokens thread...")

        val verifyThread = Thread(Runnable {
            LOG.info("HS-verify-tokens: thread started")
            LogUtil.stdout { "[HOPPSCOTCH] thread started" }
            try {
                LOG.info("HS-verify-tokens: calling HoppscotchClient.verifyTokens()...")
                val (valid, msg) = HoppscotchClient.verifyTokens(url, at, null)
                LOG.info("HS-verify-tokens: verifyTokens returned valid=$valid msg='$msg'")
                LogUtil.stdout { "[HOPPSCOTCH] verifyTokens returned: valid=$valid msg=$msg" }
                SwingUtilities.invokeLater {
                    LOG.info("HS-verify-tokens: invokeLater (success) running")
                    LogUtil.stdout { "[HOPPSCOTCH] invokeLater (success) running" }
                    verifyButton.isEnabled = true
                    showVerifyResult(valid, msg)
                }
            } catch (e: Throwable) {
                LOG.error("HS-verify-tokens: caught Throwable", e)
                LogUtil.stdout { "[HOPPSCOTCH] caught: ${e.javaClass.name}: ${e.message}" }
                LogUtil.stackTrace(e)
                SwingUtilities.invokeLater {
                    LOG.info("HS-verify-tokens: invokeLater (error) running")
                    LogUtil.stdout { "[HOPPSCOTCH] invokeLater (error) running" }
                    verifyButton.isEnabled = true
                    showVerifyResult(false, I18n.message("settings.verify.exception", e.message ?: e.javaClass.simpleName))
                }
            }
        }, "HS-verify-tokens")
        verifyThread.isDaemon = true
        verifyThread.start()
        LOG.info("HS-verify-tokens thread started, tid=${verifyThread.threadId()}")
        LogUtil.stdout { "[HOPPSCOTCH] thread started, tid=${verifyThread.threadId()}" }
    }

    /** 显示验证结果。valid=null 表示进行中。 */
    private fun showVerifyResult(valid: Boolean?, msg: String) {
        verifyStatusLabel.text = msg
        verifyStatusLabel.isVisible = true
        verifyStatusLabel.foreground = when (valid) {
            null -> null  // default (Verifying...)
            true -> Color(0x00, 0x80, 0x00)    // green
            false -> JBColor(Color(0xCC, 0x00, 0x00), Color(0xFF, 0x66, 0x66))  // red
        }
    }
}
