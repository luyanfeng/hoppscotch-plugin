package com.hoppscotch.sync.toolwindow

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.hoppscotch.sync.hoppscotch.HoppscotchClient
import com.hoppscotch.sync.model.CollectionTreeNode
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
 * 一次 GraphQL 查询获取完整集合树（含所有层级的子集合），禁用懒加载。
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
                    val rowBounds = getRowBounds(getRowForPath(path)) ?: return
                    val handleWidth = 20
                    if (e.x >= rowBounds.x + handleWidth) {
                        if (isExpanded(path)) collapsePath(path) else expandPath(path)
                    }
                }
            })

            addTreeSelectionListener { onTreeSelection() }
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

        loadFullTree()
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
        AppSettings.getInstance().apply {
            targetCollectionId = selectedId
            targetCollectionPath = selectedPath
            createSubDirectory = createSubDirCheckBox.isSelected
        }
        super.doOKAction()
    }

    /**
     * 一次性加载完整集合树。
     * 使用 [HoppscotchClient.getFullCollectionTree] 一次 GraphQL 查询获取所有层级的集合。
     */
    private fun loadFullTree() {
        loadingLabel.isVisible = true
        tree.isEnabled = false

        val task = object : Task.Backgroundable(null, "Loading collections...", false) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                val result = client.getFullCollectionTree()
                SwingUtilities.invokeLater {
                    loadingLabel.isVisible = false
                    tree.isEnabled = true

                    result.onSuccess { roots ->
                        rootNode.removeAllChildren()
                        for (root in roots) {
                            buildTreeNode(rootNode, root)
                        }
                        treeModel.reload()
                        if (rootNode.childCount > 0) {
                            tree.expandPath(TreePath(arrayOf(rootNode)))
                        }
                        restoreSelection()
                    }.onFailure { e ->
                        log.warn("Failed to load collection tree", e)
                        pathLabel.text = "Error: ${e.message}"
                    }
                }
            }
        }
        ProgressManager.getInstance().run(task)
    }

    /**
     * 递归将 [CollectionTreeNode] 构建为 [DefaultMutableTreeNode] 并添加到 [parent] 下。
     */
    private fun buildTreeNode(
        parent: DefaultMutableTreeNode,
        treeNode: CollectionTreeNode
    ) {
        val node = DefaultMutableTreeNode(CollectionTreeItem(treeNode.id, treeNode.title))
        for (child in treeNode.children) {
            buildTreeNode(node, child)
        }
        parent.add(node)
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
        if (selectedId.isNotBlank()) {
            log.warn("Saved target collection $selectedId not found in tree, clearing")
            selectedId = ""
            selectedPath = ""
            updatePathLabel()
        }
    }

    /**
     * 树节点中存储的数据。title 作为 JTree 显示文本。
     */
    private data class CollectionTreeItem(
        val id: String,
        val title: String
    ) {
        override fun toString(): String = title
    }
}
