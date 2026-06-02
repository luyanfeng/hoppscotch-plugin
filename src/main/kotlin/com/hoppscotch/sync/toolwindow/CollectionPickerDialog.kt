package com.hoppscotch.sync.toolwindow

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.hoppscotch.sync.hoppscotch.HoppscotchClient
import com.hoppscotch.sync.settings.AppSettings
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.Enumeration
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * 集合选择对话框，展示 Hoppscotch 集合树并让用户选择一个目标父集合。
 *
 * 使用方法：
 *   val dialog = CollectionPickerDialog(client)
 *   if (dialog.showAndGet()) {
 *       val id = dialog.selectedCollectionId
 *       val path = dialog.selectedCollectionPath
 *       // 保存到 AppSettings
 *   }
 */
class CollectionPickerDialog(
    private val client: HoppscotchClient
) : DialogWrapper(true) {

    private val log = Logger.getInstance(CollectionPickerDialog::class.java)

    private val tree: JTree
    private val treeModel: DefaultTreeModel
    private val rootNode: DefaultMutableTreeNode
    private val pathLabel: JLabel
    private val loadingLabel: JLabel
    private val scrollPane: JBScrollPane
    private val createSubDirCheckBox: JCheckBox

    private var selectedId: String = ""
    private var selectedPath: String = ""

    val selectedCollectionId: String get() = selectedId
    val selectedCollectionPath: String get() = selectedPath
    val isSelected: Boolean get() = selectedId.isNotBlank()

    init {
        title = "Select Target Collection"

        // ── 必须在 DialogWrapper.init() 之前初始化所有 UI 组件 ──
        rootNode = DefaultMutableTreeNode(null) // root node with null userObject
        treeModel = DefaultTreeModel(rootNode)
        tree = JTree(treeModel).apply {
            isRootVisible = false
            showsRootHandles = true
            toggleClickCount = 1
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION

            // 整行点击展开/合拢 —— 不只是点展开箭头，点行任意位置都触发展开
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val path = getPathForLocation(e.x, e.y) ?: return
                    val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                    if (node.isLeaf) return
                    // 计算展开箭头的大致宽度（showsRootHandles 为 true 时约 20px）
                    val rowBounds = getRowBounds(getRowForPath(path)) ?: return
                    val handleWidth = 20
                    // 只在非箭头区域触发展开/合拢，避免与默认箭头点击行为冲突
                    if (e.x >= rowBounds.x + handleWidth) {
                        if (isExpanded(path)) collapsePath(path) else expandPath(path)
                    }
                }
            })

            addTreeSelectionListener { onTreeSelection() }
            addTreeWillExpandListener(object : javax.swing.event.TreeWillExpandListener {
                override fun treeWillExpand(event: javax.swing.event.TreeExpansionEvent) {
                    val node = event.path.lastPathComponent as DefaultMutableTreeNode
                    val userObj = node.userObject
                    if (userObj is CollectionTreeItem && !userObj.loaded) {
                        loadChildren(node, userObj)
                    }
                }

                override fun treeWillCollapse(event: javax.swing.event.TreeExpansionEvent) {
                    // allowed
                }
            })
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        }

        pathLabel = JLabel(" ").apply {
            border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
        }

        createSubDirCheckBox = JCheckBox("以项目及类结构生成子集合", true).apply {
            border = BorderFactory.createEmptyBorder(0, 8, 4, 8)
            isSelected = AppSettings.getInstance().createSubDirectory
        }

        loadingLabel = JLabel("Loading collections...").apply {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            isVisible = false
        }

        scrollPane = JBScrollPane(tree).apply {
            preferredSize = Dimension(400, 500)
            minimumSize = Dimension(300, 300)
        }

        // 恢复上次选中
        val savedId = AppSettings.getInstance().targetCollectionId
        if (savedId.isNotBlank()) {
            selectedId = savedId
            selectedPath = AppSettings.getInstance().targetCollectionPath
            updatePathLabel()
        } else {
            updatePathLabel()
        }

        // 初始化 DialogWrapper（会在内部调用 createCenterPanel，此时组件已就绪）
        init()

        loadRoots()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout()).apply {
            add(loadingLabel, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)

            // 底部信息区域
            val southPanel = Box.createVerticalBox()
            southPanel.add(pathLabel)
            southPanel.add(createSubDirCheckBox)
            add(southPanel, BorderLayout.SOUTH)
        }
        return panel
    }

    override fun doOKAction() {
        // 选择为空时回退到根级同步（不做持久化，清空之前的设置）
        AppSettings.getInstance().apply {
            targetCollectionId = selectedId
            targetCollectionPath = selectedPath
            createSubDirectory = createSubDirCheckBox.isSelected
        }
        super.doOKAction()
    }

    /**
     * 加载根级集合。
     */
    private fun loadRoots() {
        loadingLabel.isVisible = true
        tree.isEnabled = false

        val task = object : Task.Backgroundable(null, "Loading collections...", false) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                val result = client.listCollections(1000)
                SwingUtilities.invokeLater {
                    loadingLabel.isVisible = false
                    tree.isEnabled = true

                    result.onSuccess { collections ->
                        rootNode.removeAllChildren()
                        for (col in collections) {
                            val item = CollectionTreeItem(col.id, col.title, false)
                            val node = DefaultMutableTreeNode(item)
                            // 添加占位子节点以显示展开箭头
                            node.add(DefaultMutableTreeNode("Loading..."))
                            rootNode.add(node)
                        }
                        treeModel.reload()
                        // 展开根节点
                        if (rootNode.childCount > 0) {
                            tree.expandPath(TreePath(arrayOf(rootNode)))
                        }
                        // 如果之前有选中，尝试恢复高亮
                        restoreSelection()
                    }.onFailure { e ->
                        log.warn("Failed to load root collections", e)
                        pathLabel.text = "Error: ${e.message}"
                    }
                }
            }
        }
        ProgressManager.getInstance().run(task)
    }

    /**
     * 懒加载指定节点的子集合。
     */
    private fun loadChildren(parentNode: DefaultMutableTreeNode, parentItem: CollectionTreeItem) {
        parentItem.loaded = true
        // 移除占位节点
        parentNode.removeAllChildren()

        val task = object : Task.Backgroundable(null, "Loading children...", false) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                val result = client.listChildCollections(parentItem.id, 1000)
                SwingUtilities.invokeLater {
                    result.onSuccess { children ->
                        for (child in children) {
                            val childItem = CollectionTreeItem(child.id, child.title, false)
                            val childNode = DefaultMutableTreeNode(childItem)
                            childNode.add(DefaultMutableTreeNode("Loading..."))
                            parentNode.add(childNode)
                        }
                        treeModel.reload(parentNode)
                        restoreSelection()
                    }.onFailure { e ->
                        log.warn("Failed to load children for ${parentItem.title}", e)
                        pathLabel.text = "Error loading children: ${e.message}"
                    }
                }
            }
        }
        ProgressManager.getInstance().run(task)
    }

    /**
     * 获取从根到指定节点的路径字符串。
     */
    private fun buildPath(node: DefaultMutableTreeNode): String {
        val segments = mutableListOf<String>()
        var current: DefaultMutableTreeNode? = node
        while (current != null) {
            val obj = current.userObject
            if (obj is CollectionTreeItem) {
                segments.add(obj.title)
            }
            current = current.parent as? DefaultMutableTreeNode
        }
        segments.reverse()
        return segments.joinToString(" / ")
    }

    /**
     * 树选择变化时更新显示。
     */
    private fun onTreeSelection() {
        val selectionPath = tree.selectionPath ?: return
        val node = selectionPath.lastPathComponent as? DefaultMutableTreeNode ?: return
        val item = node.userObject
        if (item !is CollectionTreeItem) return

        selectedId = item.id
        selectedPath = buildPath(node)
        updatePathLabel()
    }

    /**
     * 更新路径标签，无选择时提示将同步到根级。
     */
    private fun updatePathLabel() {
        pathLabel.text = if (selectedId.isNotBlank()) {
            "Selected: $selectedPath"
        } else {
            "None (sync to root level)"
        }
    }

    /**
     * 如果之前有持久化的选中，尝试在树中选中它。
     */
    private fun restoreSelection() {
        if (selectedId.isBlank()) return
        // 遍历树查找匹配的节点
        val root = rootNode
        val enum: Enumeration<*> = root.depthFirstEnumeration()
        while (enum.hasMoreElements()) {
            val n = enum.nextElement()
            if (n is DefaultMutableTreeNode) {
                val obj = n.userObject
                if (obj is CollectionTreeItem && obj.id == selectedId) {
                    val path = TreePath(n.path)
                    tree.selectionPath = path
                    tree.scrollPathToVisible(path)
                    return
                }
            }
        }
        // 没找到，清除失效的持久化选中
        if (selectedId.isNotBlank()) {
            log.warn("Saved target collection $selectedId not found in tree, clearing")
            selectedId = ""
            selectedPath = ""
            updatePathLabel()
        }
    }

    /**
     * 树节点中存储的数据。loaded 表示是否已加载子节点（用于懒加载）。
     */
    data class CollectionTreeItem(
        val id: String,
        val title: String,
        var loaded: Boolean = false
    ) {
        override fun toString(): String = title
    }
}
