package com.hoppscotch.sync.settings

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.Configurable
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import com.hoppscotch.sync.hoppscotch.HoppscotchClient
import com.hoppscotch.sync.model.SyncStrategy
import com.hoppscotch.sync.model.LogLevel
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
                row {
                    link(I18n.message("settings.howToTokens")) {
                        BrowserUtil.browse("https://docs.hoppscotch.io/documentation/features/authentication")
    }
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
            group(I18n.message("settings.group.logging")) {
                row(I18n.message("settings.logLevel")) {
                    cell(logLevelCombo)
                }
                row {
                    comment(I18n.message("settings.logging.comment"))
                }
            }
        }.also {
        serverUrlField.text = settings.serverUrl
        accessTokenField.text = settings.accessToken
        languageCombo.selectedItem = if (settings.language == "zh") "中文" else "English"

        // 初始化同步策略
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

        // 初始化日志级别
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
        }
    }

    override fun isModified(): Boolean {
        val settings = AppSettings.getInstance()
        val langChanged = (languageCombo.selectedItem == "中文") != (settings.language == "zh")
        val strategyChanged = (syncStrategyCombo.selectedItem as? SyncStrategy) != settings.getSyncStrategy()
        val logLevelChanged = (logLevelCombo.selectedItem as? LogLevel)?.name != settings.logLevel
        return serverUrlField.text != settings.serverUrl
                || String(accessTokenField.password) != settings.accessToken
                || langChanged
                || strategyChanged
                || logLevelChanged
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
    }

    override fun reset() {
        val settings = AppSettings.getInstance()
        serverUrlField.text = settings.serverUrl
        accessTokenField.text = settings.accessToken
        languageCombo.selectedItem = if (settings.language == "zh") "中文" else "English"
        syncStrategyCombo.selectedItem = settings.getSyncStrategy()
        syncStrategyDescLabel.text = settings.getSyncStrategy().description
        logLevelCombo.selectedItem = LogLevel.fromId(settings.logLevel)
    }

    /** 验证 token 有效性，结果直接显示在按钮下方的状态文字上。 */
    private fun verifyTokens() {
        LOG.info("=== verifyTokens() called ===")
        LogUtil.stdout { "[HOPPSCOTCH] verifyTokens() called" }

        // 检查按钮状态
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
