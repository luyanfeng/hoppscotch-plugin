package com.hoppscotch.sync.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.hoppscotch.sync.hoppscotch.HoppscotchClient
import com.hoppscotch.sync.hoppscotch.HoppscotchDataConverter
import com.hoppscotch.sync.model.ControllerGroup
import com.hoppscotch.sync.model.EndpointParameter
import com.hoppscotch.sync.model.ParamSource
import com.hoppscotch.sync.model.SpringEndpoint
import com.hoppscotch.sync.model.RequestInfo
import com.hoppscotch.sync.model.SyncPersistData
import com.hoppscotch.sync.model.SyncResult
import com.hoppscotch.sync.model.SyncStatus
import com.hoppscotch.sync.model.computeEndpointHash
import com.hoppscotch.sync.model.computeEndpointKey
import com.hoppscotch.sync.psi.SpringControllerParser
import com.hoppscotch.sync.service.SyncService
import com.hoppscotch.sync.settings.AppSettings
import com.hoppscotch.sync.util.I18n
import com.hoppscotch.sync.util.LogUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Point
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer
import javax.swing.table.TableModel
import javax.swing.table.TableRowSorter

class HoppscotchSyncPanel(private val project: Project) {

    private val log = Logger.getInstance(HoppscotchSyncPanel::class.java)

    val content: JComponent
    private var table: JBTable
    private lateinit var frozenTable: JBTable
    private val tableModel: DefaultTableModel
    private val statusLabel: JLabel
    private val timeLabel: JLabel
    private lateinit var bottomPanel: JPanel
    private val searchField: JBTextField
    private val projectButton: JButton
    private val columnsButton: JButton
    private val rowSorter: TableRowSorter<TableModel>

    private var scannedGroups: List<ControllerGroup> = emptyList()
    private var rowEndpoints = mutableListOf<SpringEndpoint>()
    private var rowGroups = mutableListOf<ControllerGroup>()
    private var rowSyncStatus = mutableListOf<SyncStatus>()

    /** 自动发现的全部模块/项目名 */
    private val allProjects = mutableListOf<String>()

    /** 显式选中的项目集合。
     *  大小等于 [allProjects] 时 = All（全部选中，默认）。
     *  为空时跳过所有项目。
     */
    private var selectedProjects: Set<String> = emptySet()

    // ── Column visibility ──
    private data class ColumnWidth(val min: Int, val pref: Int, val max: Int = Int.MAX_VALUE)

    /** 列索引 → (列名 i18n key, 默认宽度) */
    private val columnDefaults = mapOf(
        2 to ("table.column.title" to ColumnWidth(0, 200)),
        3 to ("table.column.path" to ColumnWidth(0, 300)),
        4 to ("table.column.controller" to ColumnWidth(0, 180)),
        5 to ("table.column.method" to ColumnWidth(110, 150, 150)),  // 固定宽度，需容纳中英文表头和 GET/POST/DELETE
        6 to ("table.column.parameters" to ColumnWidth(0, 200)),
        7 to ("table.column.project" to ColumnWidth(0, 120))
    )

    /** 复制当前选中单元格的文本内容到系统剪贴板 */
    private fun copySelectedCell(table: JTable) {
        val row = table.selectedRow
        val col = table.selectedColumn
        if (row >= 0 && col >= 0) {
            val modelRow = table.convertRowIndexToModel(row)
            val modelCol = table.convertColumnIndexToModel(col)
            val value = table.model.getValueAt(modelRow, modelCol)
            val text = value?.toString() ?: ""
            if (text.isNotBlank()) {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                clipboard.setContents(StringSelection(text), null)
            }
        }
    }

    init {
        // ── 自动发现所有模块名（用于 Projects 下拉）──
        ApplicationManager.getApplication().runReadAction {
            val modules = ModuleManager.getInstance(project).modules
            allProjects.clear()
            allProjects.addAll(modules.map { it.name }.filter { it.isNotBlank() }.sorted())
        }
        // 从持久化缓存恢复上次选中的项目，过滤掉当前不存在的模块
        val cachedProjects = AppSettings.getInstance().getSelectedProjects()
        selectedProjects = if (cachedProjects.isEmpty()) allProjects.toSet()
            else cachedProjects.filter { it in allProjects }.toSet().ifEmpty { allProjects.toSet() }

        // ── Table columns: # | ☑ | Title | Path | Controller | Method | Parameters | Project ──
        val columns = arrayOf(
            I18n.message("table.column.index"),
            I18n.message("table.column.checkbox"),
            I18n.message("table.column.title"),
            I18n.message("table.column.path"),
            I18n.message("table.column.controller"),
            I18n.message("table.column.method"),
            I18n.message("table.column.parameters"),
            I18n.message("table.column.project")
        )
        tableModel = object : DefaultTableModel(columns, 0) {
            override fun isCellEditable(row: Int, column: Int) = column == 1
            override fun getColumnClass(column: Int): Class<*> {
                return when (column) {
                    0 -> Int::class.java
                    1 -> Boolean::class.java
                    else -> String::class.java
                }
            }
        }

        rowSorter = TableRowSorter(tableModel)

        table = JBTable(tableModel).apply {
            rowSorter = this@HoppscotchSyncPanel.rowSorter
            autoResizeMode = JTable.AUTO_RESIZE_OFF
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)

            // Checkbox column (index 1)
            val checkboxColumn = getColumnModel().getColumn(1)
            checkboxColumn.minWidth = 90
            checkboxColumn.preferredWidth = 150
            checkboxColumn.maxWidth = 180
            // 显式 JCheckBox 渲染器，避免 JBTable/LAF 默认 Boolean 渲染失效
            checkboxColumn.cellRenderer = object : JCheckBox(), TableCellRenderer {
                init {
                    isBorderPainted = false
                    isOpaque = true
                    horizontalAlignment = SwingConstants.CENTER
                }
                override fun getTableCellRendererComponent(
                    table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
                ): Component {
                    this.isSelected = value as? Boolean ?: false
                    background = if (isSelected) table?.selectionBackground ?: background
                    else table?.background ?: background
                    return this
                }
            }
            // 显式 JCheckBox 编辑器，点击自动切换选中状态
            checkboxColumn.cellEditor = object : AbstractCellEditor(), TableCellEditor {
                private val checkBox = JCheckBox().apply {
                    isBorderPainted = false
                    horizontalAlignment = SwingConstants.CENTER
                    addActionListener { stopCellEditing() }
                }
                override fun getTableCellEditorComponent(
                    table: JTable?, value: Any?, isSelected: Boolean, row: Int, column: Int
                ): Component {
                    checkBox.isSelected = value as? Boolean ?: false
                    return checkBox
                }
                override fun getCellEditorValue(): Any = checkBox.isSelected
            }

            // Sync status background renderer（非复选框列）
            val syncRenderer = SyncStatusCellRenderer()
            setDefaultRenderer(Int::class.java, syncRenderer)
            setDefaultRenderer(String::class.java, syncRenderer)

            // Other column widths
            try { getColumn(I18n.message("table.column.index"))?.apply {
                minWidth = 50; preferredWidth = 80; maxWidth = 130
            } } catch (_: Exception) {}
            for (colModel in listOf(
                I18n.message("table.column.title") to 200,
                I18n.message("table.column.path") to 300,
                I18n.message("table.column.controller") to 180,
                I18n.message("table.column.method") to 80,
                I18n.message("table.column.parameters") to 200,
                I18n.message("table.column.project") to 120
            )) {
                try { getColumn(colModel.first)?.preferredWidth = colModel.second } catch (_: Exception) {}
            }
            try { getColumn(I18n.message("table.column.syncStatus"))?.apply {
                minWidth = 0; preferredWidth = 0; maxWidth = 0; resizable = false
            } } catch (_: Exception) {}

            // 选中单元格 Ctrl+C 复制
            val mainTable = this@apply
            getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_C, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx), "copyCell"
            )
            getActionMap().put("copyCell", object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    copySelectedCell(mainTable)
                }
            })
        }

        // ── Frozen columns: 固定前两列（# 和复选框），不随左右滑动 ──
        frozenTable = object : JBTable(tableModel) {
            // 强制行高始终与主表一致，不考虑内部 setRowHeight 影响
            override fun getRowHeight(): Int = table.rowHeight
            override fun getRowHeight(row: Int): Int = table.rowHeight
            // 冻结表（索引列、勾选列）不需要 tooltip
            override fun getToolTipText(event: java.awt.event.MouseEvent?): String? = null
        }.apply {
            // 与主表共享同一个 rowSorter，排序过滤完全同步
            this.rowSorter = this@HoppscotchSyncPanel.rowSorter
            // 只保留前两列
            while (columnModel.columnCount > 2) {
                columnModel.removeColumn(columnModel.getColumn(columnModel.columnCount - 1))
            }
            autoResizeMode = JTable.AUTO_RESIZE_OFF
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            tableHeader.reorderingAllowed = false

            // 索引列宽度
            try { getColumn(I18n.message("table.column.index"))?.apply {
                minWidth = 50; preferredWidth = 80; maxWidth = 130
                // 渲染为 view 序数（过滤/排序后始终从 1 开始）
                cellRenderer = object : DefaultTableCellRenderer() {
                    override fun getTableCellRendererComponent(
                        table: JTable?, value: Any?, isSelected: Boolean,
                        hasFocus: Boolean, row: Int, column: Int
                    ): Component {
                        val comp = super.getTableCellRendererComponent(
                            table, row + 1, isSelected, hasFocus, row, column
                        )
                        horizontalAlignment = CENTER
                        return comp
                    }
                }
            } } catch (_: Exception) {}

            // 复选框列：显式 JCheckBox 渲染器/编辑器
            val frozenCheckbox = getColumnModel().getColumn(1)
            frozenCheckbox.minWidth = 90
            frozenCheckbox.preferredWidth = 150
            frozenCheckbox.maxWidth = 180
            frozenCheckbox.cellRenderer = object : JCheckBox(), TableCellRenderer {
                init { isBorderPainted = false; isOpaque = true; horizontalAlignment = SwingConstants.CENTER }
                override fun getTableCellRendererComponent(
                    table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
                ): Component {
                    this.isSelected = value as? Boolean ?: false
                    background = if (isSelected) table?.selectionBackground ?: background
                    else table?.background ?: background
                    return this
                }
            }
            frozenCheckbox.cellEditor = object : AbstractCellEditor(), TableCellEditor {
                private val checkBox = JCheckBox().apply {
                    isBorderPainted = false
                    horizontalAlignment = SwingConstants.CENTER
                    addActionListener { stopCellEditing() }
                }
                override fun getTableCellEditorComponent(
                    table: JTable?, value: Any?, isSelected: Boolean, row: Int, column: Int
                ): Component {
                    checkBox.isSelected = value as? Boolean ?: false
                    return checkBox
                }
                override fun getCellEditorValue(): Any = checkBox.isSelected
            }
            // 同步背景色渲染
            setDefaultRenderer(Int::class.java, SyncStatusCellRenderer())

            // 选中单元格 Ctrl+C 复制
            val frozenTableSelf = this@apply
            getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_C, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx), "copyCell"
            )
            getActionMap().put("copyCell", object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    copySelectedCell(frozenTableSelf)
                }
            })
        }

        // 移除 frozenTable 自带的 header（否则在 row header viewport 中会使数据行下移偏移）
        frozenTable.setTableHeader(null)

        // 用 frozenTable 的 columnModel 创建一个独立的列头用于 corner 显示
        val cornerHeader = javax.swing.table.JTableHeader(frozenTable.columnModel).apply {
            reorderingAllowed = false
            resizingAllowed = false
        }

        // 隐藏主表前两列（由 frozenTable 替代显示）
        table.getColumnModel().getColumn(0).apply {
            minWidth = 0; maxWidth = 0; preferredWidth = 0; width = 0; resizable = false
        }
        table.getColumnModel().getColumn(1).apply {
            minWidth = 0; maxWidth = 0; preferredWidth = 0; width = 0; resizable = false
        }

        // 选中行同步（主表用 view 索引，frozenTable 无 rowSorter 直接用 model 索引）
        var syncingSelection = false
        table.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting && !syncingSelection) {
                syncingSelection = true
                try {
                    val viewLead = table.selectionModel.leadSelectionIndex
                    if (viewLead >= 0) {
                        val modelLead = table.convertRowIndexToModel(viewLead)
                        val frozenViewLead = frozenTable.convertRowIndexToView(modelLead)
                        if (frozenViewLead >= 0) {
                            frozenTable.selectionModel.setSelectionInterval(frozenViewLead, frozenViewLead)
                        }
                    }
                } finally { syncingSelection = false }
            }
        }
        frozenTable.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting && !syncingSelection) {
                syncingSelection = true
                try {
                    val frozenViewLead = frozenTable.selectionModel.leadSelectionIndex
                    if (frozenViewLead >= 0) {
                        val modelLead = frozenTable.convertRowIndexToModel(frozenViewLead)
                        val viewLead = table.convertRowIndexToView(modelLead)
                        if (viewLead >= 0) {
                            table.selectionModel.setSelectionInterval(viewLead, viewLead)
                        }
                    }
                } finally { syncingSelection = false }
            }
        }

        // 创建带固定列的滚动面板
        val scrollPane = JBScrollPane(table).apply {
            setRowHeaderView(frozenTable)
            setCorner(JScrollPane.UPPER_LEFT_CORNER, cornerHeader)
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        }
        // 确保行头视口视图位置与主视口一致（同步滚动偏移）
        scrollPane.rowHeader.viewPosition = Point(0, scrollPane.verticalScrollBar.value)
        scrollPane.verticalScrollBar.addAdjustmentListener {
            scrollPane.rowHeader.viewPosition = Point(0, it.value)
        }

        // 恢复持久化的列可见性
        applyColumnVisibility()

        // 检测用户拖拽列宽并持久化
        table.getTableHeader().addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) {
                    saveColumnWidths()
                }
            }
        })

        // ── Status label ──
        statusLabel = JLabel(I18n.message("status.ready"))
        statusLabel.border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
        timeLabel = JLabel("耗时: --").apply {
            // 使用默认 LAF 字体，自动适配缩放，与 statusLabel 样式一致
            minimumSize = Dimension(130, preferredSize.height)
        }

        // ── Search field ──
        searchField = JBTextField().apply {
            emptyText.text = I18n.message("filter.placeholder")
            toolTipText = I18n.message("filter.tooltip")
            preferredSize = Dimension(220, preferredSize.height)
            document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = applyFilter()
                override fun removeUpdate(e: DocumentEvent) = applyFilter()
                override fun changedUpdate(e: DocumentEvent) = applyFilter()
            })
        }

        // ── Project multi-select button ──
        projectButton = JButton(I18n.message("project.buttonAll")).apply {
            addActionListener { showProjectPopup() }
        }

        // ── Columns toggle button ──
        columnsButton = JButton(I18n.message("button.columns")).apply {
            addActionListener { showColumnPopup() }
        }

        // ── Filter toolbar ──
        val filterToolbar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(0, 8, 8, 8)
            add(searchField)
            add(Box.createHorizontalStrut(4))
            add(columnsButton)
            add(Box.createHorizontalGlue())
        }

        // ── Action buttons ──
        val syncButton = JButton(I18n.message("button.syncSelected")).apply {
            addActionListener { onSyncSelected() }
        }
        val refreshButton = JButton(I18n.message("button.refresh")).apply {
            addActionListener { onRefresh() }
        }
        val checkSyncButton = JButton(I18n.message("button.checkSync")).apply {
            addActionListener { onCheckSyncStatus() }
        }
        val buttonPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            add(projectButton)
            add(Box.createHorizontalStrut(8))
            add(refreshButton)
            add(Box.createHorizontalStrut(8))
            add(checkSyncButton)
            add(Box.createHorizontalStrut(8))
            add(syncButton)
            add(Box.createHorizontalGlue())
        }

        // ── Selection toolbar ──
        val selectAllButton = JButton(I18n.message("button.selectAll")).apply {
            addActionListener { selectAll() }
        }
        val invertButton = JButton(I18n.message("button.invert")).apply {
            addActionListener { invertSelection() }
        }
        val selectionToolbar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(0, 8, 8, 8)
            add(JLabel(I18n.message("button.selectLabel")))
            add(selectAllButton)
            add(Box.createHorizontalStrut(4))
            add(invertButton)
            add(Box.createHorizontalGlue())
        }

        // ── Root layout ──
        val northPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(buttonPanel)
            add(filterToolbar)
            add(selectionToolbar)
        }
        bottomPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            add(statusLabel)
            add(Box.createHorizontalGlue())
            add(JLabel("  "))
            add(timeLabel)
        }
        val rootPanel = JPanel(BorderLayout()).apply {
            add(northPanel, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
            add(bottomPanel, BorderLayout.SOUTH)
        }
        content = rootPanel

        // ── 从缓存恢复上次刷新后的扫描数据 ──
        restoreCachedScanData()
    }

    // ====================================================================
    //  Sync status row renderer
    // ====================================================================

    /**
     * 根据每行的 [rowSyncStatus] 设置非编辑列的背景色。
     * 复选框列已有自己的渲染器，不受影响。
     */
    private inner class SyncStatusCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable?, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            if (!isSelected) {
                val sorter = table?.rowSorter
                val modelRow = if (sorter != null) {
                    try { sorter.convertRowIndexToModel(row) } catch (_: IndexOutOfBoundsException) { -1 }
                } else {
                    row
                }
                val status = if (modelRow in rowSyncStatus.indices) rowSyncStatus[modelRow]
                    else SyncStatus.UNSYNCED
                background = when (status) {
                    SyncStatus.SYNCED -> JBColor(
                        Color(232, 250, 232),   // 亮色主题：浅绿
                        Color(50, 75, 50)        // 暗色主题：深绿
                    )
                    SyncStatus.MODIFIED -> JBColor(
                        Color(220, 235, 252),   // 亮色主题：浅蓝
                        Color(45, 55, 75)        // 暗色主题：深蓝
                    )
                    SyncStatus.UNSYNCED -> null  // 默认背景
                }
            }
            return comp
        }
    }

    // ====================================================================
    //  Selection
    // ====================================================================

    /** 选中所有可见（未被过滤）的行 */
    private fun selectAll() {
        for (viewRow in 0 until viewRowCount()) {
            val modelRow = rowSorter.convertRowIndexToModel(viewRow)
            tableModel.setValueAt(true, modelRow, 1)
        }
    }

    /** 反选所有可见行 */
    private fun invertSelection() {
        for (viewRow in 0 until viewRowCount()) {
            val modelRow = rowSorter.convertRowIndexToModel(viewRow)
            val current = tableModel.getValueAt(modelRow, 1) as? Boolean ?: false
            tableModel.setValueAt(!current, modelRow, 1)
        }
    }

    /** 排序/过滤后的可见行数 */
    private fun viewRowCount(): Int = rowSorter.viewRowCount

    // ====================================================================
    //  Project multi-select popup
    // ====================================================================

    private fun updateProjectButton() {
        projectButton.text = when {
            selectedProjects.size == allProjects.size -> I18n.message("project.buttonAll")
            selectedProjects.size == 1 -> I18n.message("project.single", selectedProjects.first())
            selectedProjects.isEmpty() -> I18n.message("project.none")
            else -> {
                val sorted = selectedProjects.sorted()
                I18n.message("project.multi", sorted.first())
            }
        }
        // 重置 preferredSize 让按钮自动适应新文字的宽度
        projectButton.preferredSize = null
        projectButton.revalidate()
    }

    private fun showProjectPopup() {
        if (allProjects.isEmpty()) return

        val popup = JPopupMenu()
        val allItem = JMenuItem(I18n.message("project.allPopup"))
        allItem.addActionListener {
            selectedProjects = if (selectedProjects.size == allProjects.size)
                emptySet()
            else
                allProjects.toSet()
            updateProjectButton()
            saveSelectedProjectsToCache()
        }
        popup.add(allItem)

        val invertItem = JMenuItem(I18n.message("project.invert"))
        invertItem.addActionListener {
            val inverted = allProjects.filter { it !in selectedProjects }.toSet()
            selectedProjects = inverted
            updateProjectButton()
            saveSelectedProjectsToCache()
            popup.isVisible = false
        }
        popup.add(invertItem)
        popup.addSeparator()

        for (project in allProjects) {
            val checked = project in selectedProjects
            val cb = JCheckBox(project, checked)
            cb.isOpaque = false
            cb.addActionListener {
                if (cb.isSelected) selectedProjects = selectedProjects + project
                else selectedProjects = selectedProjects - project
                updateProjectButton()
                saveSelectedProjectsToCache()
            }
            popup.add(cb)
        }

        popup.show(projectButton, 0, projectButton.height)
    }

    // ====================================================================
    //  Filter
    // ====================================================================

    private fun applyFilter() {
        val searchText = searchField.text.trim().lowercase()

        rowSorter.rowFilter = object : RowFilter<TableModel, Int>() {
            override fun include(entry: Entry<out TableModel, out Int>): Boolean {
                return searchText.isEmpty() ||
                    (0 until entry.valueCount).any { col ->
                        col != 1 && entry.getStringValue(col)?.lowercase()?.contains(searchText) == true
                    }
            }
        }
        // 过滤自动选中：可见行 √，不可见行 ✗
        syncSelectionWithFilter()
    }

    /** 可见行选中，隐藏行取消选中 */
    private fun syncSelectionWithFilter() {
        for (modelRow in 0 until tableModel.dataVector.size) {
            val viewRow = rowSorter.convertRowIndexToView(modelRow)
            tableModel.setValueAt(viewRow >= 0, modelRow, 1)
        }
    }

    // ====================================================================
    //  Column auto-size & width persistence
    // ====================================================================

    /** 列 3-6 按内容自适应宽度。用户已保存的宽度不覆盖。 */
    private fun autoSizeColumns() {
        val saved = AppSettings.getInstance().getColumnWidthMap()
        val metrics = table.getFontMetrics(table.font)
        val colModel = table.getColumnModel()

        for (colIdx in 2..6) {
            // 用户已手动调过宽度的列不覆盖
            if (colIdx in saved) continue

            val col = try { colModel.getColumn(colIdx) } catch (_: Exception) { null } ?: continue
            val headerText = col.headerValue?.toString() ?: ""
            var maxWidth = metrics.stringWidth(headerText) + 20

            for (row in 0 until tableModel.dataVector.size) {
                val value = tableModel.getValueAt(row, colIdx)?.toString() ?: ""
                val w = metrics.stringWidth(value) + 24
                if (w > maxWidth) maxWidth = w
            }

            col.minWidth = 0
            col.preferredWidth = (maxWidth + 20).coerceIn(60, 600)
            col.maxWidth = Int.MAX_VALUE
        }
    }

    /** 保存列 2-6 的当前宽度到 [AppSettings] */
    private fun saveColumnWidths() {
        val widths = mutableMapOf<Int, Int>()
        val colModel = table.getColumnModel()
        for (colIdx in 2..6) {
            try { widths[colIdx] = colModel.getColumn(colIdx).width } catch (_: Exception) {}
        }
        AppSettings.getInstance().setColumnWidths(widths)
    }

    // ====================================================================
    //  Column visibility
    // ====================================================================

    /** 从 [AppSettings] 恢复列可见性 */
    private fun applyColumnVisibility() {
        val hidden = AppSettings.getInstance().getHiddenColumnSet()
        val saved = AppSettings.getInstance().getColumnWidthMap()
        for ((colIdx, _) in columnDefaults) {
            val col = try { table.getColumnModel().getColumn(colIdx) } catch (_: Exception) { null } ?: continue
            if (colIdx in hidden) {
                col.minWidth = 0; col.maxWidth = 0; col.preferredWidth = 0; col.width = 0
            } else {
                val (_, w) = columnDefaults[colIdx]!!
                col.minWidth = w.min
                col.preferredWidth = saved[colIdx] ?: w.pref
                col.maxWidth = w.max
            }
        }
    }

    /** 切换单列可见性并持久化 */
    private fun toggleColumn(columnIdx: Int, visible: Boolean) {
        val col = try { table.getColumnModel().getColumn(columnIdx) } catch (_: Exception) { return }
        val settings = AppSettings.getInstance()
        val hidden = settings.getHiddenColumnSet().toMutableSet()

        if (visible) {
            hidden.remove(columnIdx)
            val (_, w) = columnDefaults[columnIdx] ?: return
            val saved = settings.getColumnWidthMap()
            col.minWidth = w.min
            col.preferredWidth = saved[columnIdx] ?: w.pref
            col.maxWidth = w.max
        } else {
            hidden.add(columnIdx)
            col.minWidth = 0; col.maxWidth = 0; col.preferredWidth = 0; col.width = 0
        }

        settings.setHiddenColumns(hidden)
        table.revalidate()
    }

    private fun showColumnPopup() {
        val popup = JPopupMenu()
        val hidden = AppSettings.getInstance().getHiddenColumnSet()

        for ((colIdx, pair) in columnDefaults.entries.sortedBy { it.key }) {
            val colName = I18n.message(pair.first)
            val isVisible = colIdx !in hidden
            val cb = JCheckBox(colName, isVisible)
            cb.isOpaque = false
            cb.addActionListener { toggleColumn(colIdx, cb.isSelected) }
            popup.add(cb)
        }

        popup.show(columnsButton, 0, columnsButton.height)
    }

    // ====================================================================
    //  Actions
    // ====================================================================

    private fun onRefresh() {
        if (selectedProjects.isEmpty()) {
            statusLabel.text = I18n.message("status.noProjectsSelected")
            Messages.showWarningDialog(
                project,
                I18n.message("dialog.noProjectsSelected.message"),
                I18n.message("dialog.noProjectsSelected.title")
            )
            return
        }
        statusLabel.text = I18n.message("status.scanning")
        timeLabel.text = "耗时: --"
        val startTime = System.currentTimeMillis()
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, I18n.message("progress.scanning.title"), false
        ) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = I18n.message("progress.indicator.scanning")

                // 1. Controller 扫描
                val (groups, error) = scanControllersSafe()

                // 更新耗时（不受错误影响，始终显示）
                val elapsed = System.currentTimeMillis() - startTime
                val elapsedText = "耗时: ${elapsed}ms"
                SwingUtilities.invokeLater {
                    timeLabel.text = elapsedText
                    timeLabel.revalidate()
                    timeLabel.repaint()
                    bottomPanel.revalidate()
                    bottomPanel.repaint()
                }

                // 扫描失败时直接返回
                if (error != null) {
                    SwingUtilities.invokeLater {
                        statusLabel.text = I18n.message("status.scanFailed", error.message ?: "")
                        Messages.showErrorDialog(
                            project,
                            I18n.message("dialog.scanError.message", error.message ?: ""),
                            I18n.message("dialog.scanError.title")
                        )
                    }
                    return
                }

                // 2. 服务端同步状态检查（与 onCheckSyncStatus 共用逻辑）
                val serverStatuses = performServerCheck(groups ?: emptyList(), indicator)

                // 3. 统一更新 UI
                SwingUtilities.invokeLater {
                    scannedGroups = groups ?: emptyList()
                    refreshTable()
                    // 用服务端校验结果覆盖同步状态
                    if (serverStatuses != null) {
                        for (i in 0 until minOf(serverStatuses.size, rowSyncStatus.size)) {
                            rowSyncStatus[i] = serverStatuses[i]
                        }
                        table.repaint()
                    }
                    cacheCurrentScanData() // 缓存扫描结果
                    updateStatusAfterScan()
                }
            }
        })
    }


    private fun onSyncSelected() {
        val selectedPairs = getSelectedEndpointsWithGroups()
        if (selectedPairs.isEmpty()) {
            Messages.showInfoMessage(
                project,
                I18n.message("dialog.noSelection.message"),
                I18n.message("dialog.noSelection.title")
            )
            return
        }

        val totalSelected = selectedPairs.size
        val confirmResult = Messages.showYesNoDialog(
            project,
            I18n.message("dialog.confirmSync.message", totalSelected),
            I18n.message("dialog.confirmSync.title"),
            Messages.getQuestionIcon()
        )
        if (confirmResult != Messages.YES) return

        val settings = AppSettings.getInstance()
        if (settings.serverUrl.isBlank() || settings.accessToken.isBlank()) {
            Messages.showWarningDialog(
                project,
                I18n.message("dialog.notConfigured.message"),
                "Hoppscotch Sync"
            )
            return
        }

        // 弹出集合树选择对话框，让用户选择目标父集合
        val pickerClient = HoppscotchClient(
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
            pickerClient.tryRefreshSession()
        }
        val pickerDialog = CollectionPickerDialog(pickerClient)
        if (!pickerDialog.showAndGet()) return // 用户取消选择，不继续同步

        val targetId = pickerDialog.selectedCollectionId
            .takeIf { it.isNotBlank() }

        statusLabel.text = I18n.message("status.syncing", totalSelected)
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, I18n.message("progress.syncing.title"), false
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
                val parser = SpringControllerParser(project)
                val service = SyncService(project, client, parser, indicator)

                val filteredGroups = buildFilteredGroups()
                val strategy = settings.getSyncStrategy()
                // 开始推送 — 记录推送耗时
                SwingUtilities.invokeLater {
                    timeLabel.text = "耗时: --"
                }
                val pushStartTime = System.currentTimeMillis()
                val syncResult = service.syncGroups(filteredGroups, targetId, strategy, settings.createSubDirectory)
                val pushElapsed = System.currentTimeMillis() - pushStartTime
                val pushElapsedText = "耗时: ${pushElapsed}ms"

                SwingUtilities.invokeLater {
                    timeLabel.text = pushElapsedText
                    timeLabel.revalidate()
                    timeLabel.repaint()
                    bottomPanel.revalidate()
                    bottomPanel.repaint()

                    showSyncResult(syncResult)
                    updateStatusAfterSync(syncResult)

                    // ── 同步后记录 hash + serverId（本地 + 服务端请求）并更新行状态 ──
                    val converter = HoppscotchDataConverter()
                    val syncMap = AppSettings.getInstance().getSyncStatusMap().toMutableMap()
                    for (group in filteredGroups) {
                        for (endpoint in group.endpoints) {
                            val key = computeEndpointKey(endpoint, group)
                            val localHash = computeEndpointHash(endpoint)
                            val srvHash = HoppscotchDataConverter.computeServerRequestHash(
                                converter.toHoppscotchRequest(endpoint)
                            )
                            // 优先使用 syncGroups 返回的 serverId（新格式），否则用旧格式
                            val serverId = syncResult.syncedEndpoints[key]
                            syncMap[key] = if (serverId != null) {
                                HoppscotchDataConverter.buildSyncValue(serverId, localHash, srvHash)
                            } else {
                                HoppscotchDataConverter.buildSyncValue(localHash, srvHash)
                            }
                        }
                    }
                    AppSettings.getInstance().setSyncStatusMap(syncMap)

                    val syncedKeys = mutableSetOf<String>()
                    for (group in filteredGroups) {
                        for (endpoint in group.endpoints) {
                            syncedKeys.add(computeEndpointKey(endpoint, group))
                        }
                    }
                    for (i in rowEndpoints.indices) {
                        val key = computeEndpointKey(rowEndpoints[i], rowGroups[i])
                        if (key in syncedKeys) {
                            rowSyncStatus[i] = SyncStatus.SYNCED
                        }
                    }

                    table.repaint()
                }
            }
        })
    }

    // ====================================================================
    //  Sync status check
    // ====================================================================

    /**
     * "检查同步状态"按钮：对比本地端点与服务端请求，更新每行同步状态。
     *
     * 仅从服务端获取数据做对比，不修改服务端数据。
     * 结果与持久化 hash 一起决定 UNSYNCED / SYNCED / MODIFIED 状态。
     * 持久化 hash 只在实际同步时写入，所以 SYNCED=hash+服务端都存在且一致。
     */
    private fun onCheckSyncStatus() {
        if (scannedGroups.isEmpty()) {
            Messages.showInfoMessage(
                project,
                I18n.message("dialog.checkSync.noEndpoints"),
                I18n.message("dialog.checkSync.title")
            )
            return
        }

        val settings = AppSettings.getInstance()
        if (settings.serverUrl.isBlank() || settings.accessToken.isBlank()) {
            Messages.showWarningDialog(
                project,
                I18n.message("dialog.notConfigured.message"),
                "Hoppscotch Sync"
            )
            return
        }

        statusLabel.text = I18n.message("status.checking")
        timeLabel.text = "耗时: --"
        val startTime = System.currentTimeMillis()

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, I18n.message("progress.checking.title"), false
        ) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = I18n.message("progress.indicator.scanning")

                try {
                    // 1. 重新扫描，获取最新端点数据
                    val (freshGroups, scanError) = scanControllersSafe()
                    if (scanError != null) {
                        log.warn("检查同步状态时重新扫描失败: ${scanError.message}", scanError)
                        SwingUtilities.invokeLater {
                            val elapsed = System.currentTimeMillis() - startTime
                            timeLabel.text = "耗时: ${elapsed}ms"
                            statusLabel.text = I18n.message("status.checkFailed")
                            Messages.showErrorDialog(
                                project,
                                "重新扫描控制器失败: ${scanError.message}",
                                I18n.message("dialog.checkSync.title")
                            )
                        }
                        return
                    }
                    val groups = freshGroups ?: emptyList()

                    // 2. 服务端同步状态检查（与 onRefresh 共用逻辑）
                    val serverStatuses = performServerCheck(groups, indicator)

                    // 3. 更新 UI
                    val elapsed = System.currentTimeMillis() - startTime
                    val elapsedText = "耗时: ${elapsed}ms"
                    SwingUtilities.invokeLater {
                        timeLabel.text = elapsedText
                        timeLabel.revalidate()
                        timeLabel.repaint()
                        bottomPanel.revalidate()
                        bottomPanel.repaint()

                        scannedGroups = groups
                        refreshTable()
                        // 用服务端校验结果覆盖同步状态
                        if (serverStatuses != null) {
                            for (i in 0 until minOf(serverStatuses.size, rowSyncStatus.size)) {
                                rowSyncStatus[i] = serverStatuses[i]
                            }
                            table.repaint()
                        }
                        statusLabel.text = I18n.message("status.checkDone")
                    }
                } catch (e: Exception) {
                    log.warn("检查同步状态异常: ${e.message}", e)
                    SwingUtilities.invokeLater {
                        val elapsed = System.currentTimeMillis() - startTime
                        timeLabel.text = "耗时: ${elapsed}ms"
                        timeLabel.revalidate()
                        bottomPanel.revalidate()
                        bottomPanel.repaint()
                        statusLabel.text = I18n.message("status.checkFailed")
                        Messages.showErrorDialog(
                            project,
                            "检查同步状态异常: ${e.message}",
                            I18n.message("dialog.checkSync.title")
                        )
                    }
                }
            }
        })
    }

    // ====================================================================
    //  Sync status check helper
    // ====================================================================

    /**
     * 在后台线程中执行服务端同步状态检查。
     *
     * 查询服务端集合中的请求标题，结合持久化 hash 对比，为所有端点计算同步状态。
     * 如果服务端未配置（URL/Token 为空），跳过检查返回 null。
     *
     * @param groups 当前扫描到的分组数据
     * @param indicator 进度指示器（可选），用于更新提示文本
     * @return 与 [groups] 展开后端点顺序一致的 [SyncStatus] 列表，跳过检查时返回 null
     */
    private fun performServerCheck(
        groups: List<ControllerGroup>,
        indicator: ProgressIndicator? = null
    ): List<SyncStatus>? {
        val settings = AppSettings.getInstance()
        if (settings.serverUrl.isBlank() || settings.accessToken.isBlank()) {
            LogUtil.info(log) { "服务端未配置，跳过服务端同步状态检查" }
            return null
        }

        indicator?.text = I18n.message("progress.indicator.checking")

        val client = HoppscotchClient(
            serverUrl = settings.serverUrl,
            accessToken = settings.accessToken,
            refreshToken = settings.refreshToken,
            onTokenRefreshed = { newAccess, newRefresh ->
                settings.accessToken = newAccess
                if (newRefresh != null) settings.refreshToken = newRefresh
            }
        )
        if (settings.refreshToken.isNotBlank()) {
            client.tryRefreshSession()
        }

        // 从扫描结果构建端点/分组列表
        val freshEndpoints = mutableListOf<SpringEndpoint>()
        val freshGroups = mutableListOf<ControllerGroup>()
        for (group in groups) {
            for (endpoint in group.endpoints) {
                freshEndpoints.add(endpoint)
                freshGroups.add(group)
            }
        }

        // 根据选中项目构建期望集合标题
        val expectedCollectionTitles = selectedProjects.mapTo(mutableSetOf()) { project ->
            project.replace(Regex("[<>:\"/\\\\|?*\\[\\]]"), "_")
        }

        LogUtil.info(log) { "=== 检查同步状态 - 选中项目: ${selectedProjects.joinToString(", ")} ===" }
        LogUtil.info(log) { "期望集合标题: ${expectedCollectionTitles.joinToString(", ")}" }
        LogUtil.info(log) { "targetCollectionId: ${settings.targetCollectionId.ifBlank { "(无)" }}" }

        // 搜索所有集合层次（root + target 子集合），确保覆盖同步时选择的不同位置
        // 同时收集请求 JSON 用于服务端修改检测
        // 使用 serverId 匹配优先，不依赖 title
        val allServerIds = mutableSetOf<String>()
        val requestById = mutableMapOf<String, RequestInfo>() // id → RequestInfo
        // method:endpoint → id 映射，用于旧数据回填（无 serverId 时）
        val methodEndpointToInfo = mutableMapOf<String, RequestInfo>()
        val targetId = settings.targetCollectionId.ifBlank { null }

        fun collectRequests(infos: List<RequestInfo>) {
            for (info in infos) {
                if (info.id.isNotBlank()) {
                    allServerIds.add(info.id)
                    requestById[info.id] = info
                }
                val meKey = info.methodEndpointKey
                if (meKey != null) {
                    methodEndpointToInfo.putIfAbsent(meKey, info)
                }
            }
        }

        // 1. 先搜索 target 子集合（如果有设置）
        if (targetId != null) {
            val targetResult = client.listRequestInfosForCollections(
                expectedTitles = expectedCollectionTitles,
                parentCollectionId = targetId
            )
            if (targetResult.isSuccess) {
                val infos = targetResult.getOrThrow()
                collectRequests(infos)
                LogUtil.info(log) { "target 模式 [${settings.targetCollectionPath}] 找到 ${infos.size} 个请求（含 ${infos.count { it.request.isNotBlank() }} 个含请求体）" }
            } else {
                log.warn("target 模式查询失败: ${targetResult.exceptionOrNull()?.message}")
            }
        }

        // 2. 再搜索根集合（确保覆盖通过 SyncAction 等方式同步到根的数据）
        val rootResult = client.listRequestInfosForCollections(
            expectedTitles = expectedCollectionTitles,
            parentCollectionId = null
        )
        if (rootResult.isSuccess) {
            val infos = rootResult.getOrThrow()
            val beforeSize = allServerIds.size
            collectRequests(infos)
            LogUtil.info(log) { "根集合搜索找到 ${infos.size} 个请求（其中 ${allServerIds.size - beforeSize} 个新 id，${infos.count { it.request.isNotBlank() }} 个含请求体）" }
        } else {
            log.warn("根集合查询失败: ${rootResult.exceptionOrNull()?.message}")
        }

        if (allServerIds.isEmpty()) {
            LogUtil.info(log) { "在所有位置均未找到匹配的请求数据，所有端点将标记为 UNSYNCED" }
        }

        // 获取持久化 hash 映射
        val persistedMap = settings.getSyncStatusMap()
        // 准备持久化更新（回填 serverId 等）
        val updatedMap = persistedMap.toMutableMap()
        var hasPendingUpdates = false

        // 使用扫描数据判断每行同步状态，对比本地 hash + 服务端请求 hash
        val statuses = MutableList(freshEndpoints.size) { SyncStatus.UNSYNCED }
        for (i in freshEndpoints.indices) {
            val endpoint = freshEndpoints[i]
            val group = freshGroups[i]

            // 只检查选中项目的端点
            if (group.moduleName !in selectedProjects) {
                continue
            }

            val key = computeEndpointKey(endpoint, group)
            val currentLocalHash = computeEndpointHash(endpoint)
            val storedValue = persistedMap[key]
            val matchKey = "${endpoint.httpMethod.name}:${endpoint.fullPath}"

            // 解析持久化的数据（含 serverId）
            val syncData = if (storedValue != null) SyncPersistData.parse(storedValue) else null
            val storedLocalHash = syncData?.localHash
            val storedSrvHash = syncData?.srvHash ?: 0
            val serverId = syncData?.serverId
            val hasPersistedData = storedValue != null

            LogUtil.info(log) { "--- 端点 [$i] 对比详情 ---" }
            LogUtil.info(log) { "  端点 Key: $key" }
            LogUtil.info(log) { "  本地 FullPath: ${endpoint.fullPath}" }
            LogUtil.info(log) { "  本地 HTTP Method: ${endpoint.httpMethod.name}" }
            LogUtil.info(log) { "  本地参数: ${endpoint.parameters.map { "${it.source.name}:${it.name}(${it.type})" }.joinToString(", ")}" }
            LogUtil.info(log) { "  本地当前 Hash: $currentLocalHash" }
            LogUtil.info(log) { "  持久化: ${storedValue ?: "无"}" }
            LogUtil.info(log) { "  持久化 serverId: ${serverId ?: "无"}" }

            // ── 确定服务端请求是否存在 ──
            val serverReqInfo: RequestInfo? = if (serverId != null && serverId in allServerIds) {
                // 有 serverId 且服务端存在 → 直接取
                requestById[serverId]
            } else if (serverId != null && serverId !in allServerIds) {
                // 有 serverId 但服务端找不到（可能被手动删除）→ 尝试 method:endpoint 匹配
                methodEndpointToInfo[matchKey]
            } else {
                // 无 serverId（旧数据）→ 尝试 method:endpoint 匹配（回填）
                methodEndpointToInfo[matchKey]
            }

            val foundOnServer = serverReqInfo != null

            // ── 回填 serverId（仅当无 serverId 但匹配到了）──
            if (serverId == null && serverReqInfo != null) {
                val oldLocalHash = syncData?.localHash ?: currentLocalHash
                val oldSrvHash = syncData?.srvHash ?: 0
                updatedMap[key] = HoppscotchDataConverter.buildSyncValue(
                    serverReqInfo.id, oldLocalHash, oldSrvHash
                )
                LogUtil.info(log) { "  ↑ 已回填 serverId: ${serverReqInfo.id}" }
                hasPendingUpdates = true
            }

            LogUtil.info(log) { "  服务端是否存在: $foundOnServer" }

            statuses[i] = if (foundOnServer) {
                val localChanged = hasPersistedData && currentLocalHash != storedLocalHash
                var serverChanged = false

                // 仅在本地未变化时检查服务端请求 hash
                if (!localChanged && hasPersistedData && storedSrvHash != 0) {
                    val serverReqJson = serverReqInfo.request
                    if (serverReqJson.isNotBlank()) {
                        val currentSrvHash = HoppscotchDataConverter.computeServerRequestHashFromJson(serverReqJson)
                        serverChanged = currentSrvHash != storedSrvHash
                        LogUtil.info(log) { "  服务端请求 Hash: 当前=$currentSrvHash, 持久化=$storedSrvHash" }
                    }
                }

                if (localChanged) {
                    LogUtil.info(log) { "  → MODIFIED (本地 hash 改变: $storedLocalHash → $currentLocalHash)" }
                } else if (serverChanged) {
                    LogUtil.info(log) { "  → MODIFIED (服务端请求 hash 改变)" }
                }

                when {
                    localChanged || serverChanged -> SyncStatus.MODIFIED
                    else -> SyncStatus.SYNCED
                }
            } else {
                LogUtil.info(log) { "  → 状态: UNSYNCED (服务端无此请求)" }
                SyncStatus.UNSYNCED
            }
            LogUtil.info(log) { "------------------------" }
        }

        // 保存回填的 serverId
        if (hasPendingUpdates) {
            settings.setSyncStatusMap(updatedMap)
            LogUtil.info(log) { "已持久化回填的 serverId" }
        }

        LogUtil.info(log) { "=== 检查同步状态完成 ===" }
        return statuses
    }

    // ====================================================================
    //  Scanning helpers
    // ====================================================================

    /**
     * 在 [ReadAction] 内执行 Controller 扫描，按 [selectedProjects] 过滤模块。
     *
     * [selectedProjects] 为空时不扫描任何模块（跳过）。
     */
    private fun scanControllersSafe(): Pair<List<ControllerGroup>?, Throwable?> {
        if (selectedProjects.isEmpty()) {
            return Pair(emptyList(), null)
        }
        return try {
            var groups: List<ControllerGroup> = emptyList()
            ApplicationManager.getApplication().runReadAction {
                groups = SpringControllerParser(project).parseAllControllers(selectedProjects)
            }
            Pair(groups, null)
        } catch (e: Exception) {
            log.warn("Controller 扫描异常: ${e.message}", e)
            Pair(null, e)
        }
    }

    // ====================================================================
    //  Build filtered groups from checked rows
    // ====================================================================

    /**
     * 收集表格中所有选中（复选框 √）的端点及其所属 [ControllerGroup]。
     */
    private fun getSelectedEndpointsWithGroups(): List<Pair<ControllerGroup, SpringEndpoint>> {
        val selected = mutableListOf<Pair<ControllerGroup, SpringEndpoint>>()
        for (viewRow in 0 until viewRowCount()) {
            val modelRow = rowSorter.convertRowIndexToModel(viewRow)
            val checked = tableModel.getValueAt(modelRow, 1) as? Boolean ?: false
            if (checked && modelRow < rowEndpoints.size && modelRow < rowGroups.size) {
                selected.add(rowGroups[modelRow] to rowEndpoints[modelRow])
            }
        }
        return selected
    }

    /**
     * 将选中的端点重新聚合成 [ControllerGroup] 列表供同步。
     * 每个组只包含被选中的端点。
     */
    private fun buildFilteredGroups(): List<ControllerGroup> {
        val selected = getSelectedEndpointsWithGroups()
        // 按 controllerQualifiedName@moduleName 分组
        val groupMap = linkedMapOf<String, Pair<ControllerGroup, MutableList<SpringEndpoint>>>()
        for ((group, endpoint) in selected) {
            val key = "${group.controllerQualifiedName}@${group.moduleName}"
            val entry = groupMap.getOrPut(key) { group to mutableListOf() }
            entry.second.add(endpoint)
        }
        return groupMap.values.map { (group, endpoints) ->
            group.copy(endpoints = endpoints)
        }
    }

    // ====================================================================
    //  Table display
    // ====================================================================

    private fun refreshTable() {
        // 临时分离 frozen 表的 rowSorter，避免添加行时与主表重复通知导致崩溃
        frozenTable.rowSorter = null

        tableModel.dataVector.clear()
        tableModel.fireTableDataChanged()
        rowEndpoints.clear()
        rowGroups.clear()
        rowSyncStatus.clear()

        val persistedMap = AppSettings.getInstance().getSyncStatusMap()

        var index = 0
        for (group in scannedGroups) {
            for (endpoint in group.endpoints) {
                index++
                rowEndpoints.add(endpoint)
                rowGroups.add(group)

                // 从持久化数据做启发式推断同步状态
                val key = computeEndpointKey(endpoint, group)
                val currentHash = computeEndpointHash(endpoint)
                val storedValue = persistedMap[key]
                val storedLocalHash = if (storedValue != null) HoppscotchDataConverter.parseLocalHash(storedValue) else null
                val status = when {
                    storedValue == null -> SyncStatus.UNSYNCED
                    storedLocalHash == currentHash -> SyncStatus.SYNCED
                    else -> SyncStatus.MODIFIED
                }
                rowSyncStatus.add(status)

                val titleText = endpoint.description?.takeIf { it.isNotBlank() } ?: endpoint.fullPath
                tableModel.addRow(
                    arrayOf<Any>(
                        index,
                        false,
                        titleText,
                        endpoint.fullPath,
                        endpoint.controllerClassName,
                        endpoint.httpMethod.name,
                        formatParameters(endpoint.parameters, endpoint.consumes),
                        group.moduleName
                    )
                )
            }
        }

        // 恢复 frozen 表的 rowSorter
        frozenTable.rowSorter = this@HoppscotchSyncPanel.rowSorter

        searchField.text = ""
        updateProjectButton()
        applyFilter()
        autoSizeColumns()
    }

    // ====================================================================
    //  Scan/project cache persistence
    // ====================================================================

    /** 将当前的 [scannedGroups] 持久化缓存到设置 */
    private fun cacheCurrentScanData() {
        if (scannedGroups.isEmpty()) return
        AppSettings.getInstance().setCachedScanGroups(scannedGroups)
    }

    /** 从持久化缓存恢复上次的扫描数据 */
    private fun restoreCachedScanData() {
        val settings = AppSettings.getInstance()
        val cached = settings.getCachedScanGroups()
        if (cached.isNullOrEmpty()) return
        scannedGroups = cached
        refreshTable()
        statusLabel.text = I18n.message("status.cachedData")
    }

    /** 将当前 [selectedProjects] 持久化缓存到设置 */
    private fun saveSelectedProjectsToCache() {
        AppSettings.getInstance().setSelectedProjects(selectedProjects)
    }

    private fun formatParameters(parameters: List<EndpointParameter>, consumes: List<String> = emptyList()): String {
        if (parameters.isEmpty()) return ""

        // 预先计算 consumes 关键词，用于判断 QUERY 参数是真正的 query 还是 form-data/form
        val consumesLower = consumes.joinToString(" ").lowercase()
        val isFormData = "form-data" in consumesLower || "multipart" in consumesLower
        val isFormUrlEncoded = "urlencoded" in consumesLower || "x-www-form-urlencoded" in consumesLower

        val parts = mutableListOf<String>()
        val grouped = parameters.groupBy { it.source }

        for ((source, params) in grouped.entries) {
            when (source) {
                ParamSource.QUERY -> {
                    val items = mutableListOf<String>()
                    for (param in params) {
                        if (param.objectFields.isNotEmpty()) {
                            param.objectFields.forEach { field ->
                                items.add("$field=")
                            }
                        } else {
                            val value = param.defaultValue?.takeIf { it.isNotBlank() } ?: ""
                            items.add("${param.name}=$value")
                        }
                    }
                    if (items.isEmpty()) continue
                    val prefix = when {
                        isFormData -> "form-data"
                        isFormUrlEncoded -> "form"
                        else -> "query"
                    }
                    parts.add("$prefix: ${items.joinToString("&")}")
                }
                ParamSource.BODY -> {
                    // 根据 consumes 推断 body 格式前缀
                    val prefix = resolveBodyPrefix(consumes)
                    for (param in params) {
                        if (param.bodyJsonSkeleton != null) {
                            // 使用递归解析生成的 JSON 骨架展示
                            parts.add("$prefix: ${param.bodyJsonSkeleton}")
                        } else {
                            // 无法解析类型时回退为旧格式
                            parts.add("$prefix: {\"${param.name}\":...}")
                        }
                    }
                }
                ParamSource.HEADER -> {
                    val display = params.joinToString(", ") { it.name }
                    parts.add("header: $display")
                }
                ParamSource.PATH -> {
                    // PATH 变量已在 Path 列显示，不重复
                }
            }
        }

        if (parts.isEmpty()) return ""

        val result = parts.joinToString(" | ")
        return if (result.length > 200) result.take(197) + "..." else result
    }

    /**
     * 根据 consumes 推断 body 的请求格式前缀。
     * - multipart/form-data → form-data
     * - application/x-www-form-urlencoded → form
     * - 其他/未指定 → raw-json
     */
    private fun resolveBodyPrefix(consumes: List<String>): String {
        val combined = consumes.joinToString(" ").lowercase()
        return when {
            "form-data" in combined || "multipart" in combined -> "form-data"
            "urlencoded" in combined || "x-www-form-urlencoded" in combined -> "form"
            else -> "raw-json"
        }
    }

    // ====================================================================
    //  Status
    // ====================================================================

    private fun updateStatusAfterScan() {
        val endpointCount = scannedGroups.sumOf { it.endpoints.size }
        val projectsStr = if (scannedGroups.isNotEmpty())
            scannedGroups.map { it.moduleName }.distinct().joinToString { m -> m.ifEmpty { "?" } }
        else ""
        statusLabel.text = I18n.message("status.scanResult", endpointCount, scannedGroups.size, projectsStr)
    }

    private fun updateStatusAfterSync(result: SyncResult) {
        val suffix = if (result.errors.isNotEmpty()) I18n.message("result.errorsSuffix", result.errors.size) else ""
        statusLabel.text = I18n.message("status.syncResult", result.success, result.failed, suffix)
    }

    // ====================================================================
    //  Sync result dialog
    // ====================================================================

    private fun showSyncResult(result: SyncResult) {
        val message = buildString {
            appendLine(I18n.message("result.total", result.total))
            appendLine(I18n.message("result.success", result.success))
            appendLine(I18n.message("result.failed", result.failed))
            appendLine(I18n.message("result.collections", result.collectionsCreated))
            appendLine(I18n.message("result.requests", result.requestsCreated))
            if (result.requestsSkipped > 0) appendLine(I18n.message("result.skipped", result.requestsSkipped))
            if (result.requestsUpdated > 0) appendLine(I18n.message("result.updated", result.requestsUpdated))
            if (result.requestsMerged > 0) appendLine(I18n.message("result.merged", result.requestsMerged))
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
