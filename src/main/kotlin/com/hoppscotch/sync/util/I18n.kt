package com.hoppscotch.sync.util

import com.hoppscotch.sync.settings.AppSettings
import java.text.MessageFormat
import java.util.*

/**
 * 国际化工具 — 根据 [AppSettings.language] 切换中英文。
 *
 * 资源文件位置：src/main/resources/messages/HoppscotchSyncBundle[_zh].properties
 */
object I18n {

    private val enBundle by lazy { load("messages/HoppscotchSyncBundle.properties") }
    private val zhBundle by lazy { load("messages/HoppscotchSyncBundle_zh.properties") }

    private fun load(path: String): Map<String, String> {
        val props = Properties()
        val stream = I18n::class.java.classLoader.getResourceAsStream(path)
            ?: error("Missing resource: $path")
        props.load(stream.reader(Charsets.UTF_8))
        val map = linkedMapOf<String, String>()
        for (key in props.stringPropertyNames()) {
            map[key] = props.getProperty(key)
        }
        return map
    }

    private fun current(): Map<String, String> {
        return if (AppSettings.getInstance().language == "zh") zhBundle else enBundle
    }

    /** 获取翻译文本，支持 {0} {1}… 占位符。找不到 key 时返回 "!key!" */
    fun message(key: String, vararg args: Any?): String {
        val pattern = current()[key] ?: return "!$key!"
        return if (args.isEmpty()) pattern else MessageFormat.format(pattern, *args)
    }

}
