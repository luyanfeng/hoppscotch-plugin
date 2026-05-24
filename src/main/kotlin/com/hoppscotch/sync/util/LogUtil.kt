package com.hoppscotch.sync.util

import com.hoppscotch.sync.model.LogLevel
import com.hoppscotch.sync.settings.AppSettings
import com.intellij.openapi.diagnostic.Logger

/**
 * 调试日志工具 — 根据 [AppSettings.logLevel] 控制日志输出。
 *
 * 用法：
 *   LogUtil.info(log) { "消息" }
 *   LogUtil.stdout("debug 输出")   // 对应 System.err.println 的替换
 */
object LogUtil {

    private val currentLevel: LogLevel
        get() = LogLevel.fromId(AppSettings.getInstance().logLevel)

    fun debug(log: Logger, msg: () -> String) {
        if (currentLevel.allows(LogLevel.DEBUG)) log.debug(msg())
    }

    fun info(log: Logger, msg: () -> String) {
        if (currentLevel.allows(LogLevel.INFO)) log.info(msg())
    }

    fun warn(log: Logger, msg: () -> String) {
        if (currentLevel.allows(LogLevel.WARN)) log.warn(msg())
    }

    fun warn(log: Logger, t: Throwable, msg: () -> String) {
        if (currentLevel.allows(LogLevel.WARN)) log.warn(msg(), t)
    }

    fun error(log: Logger, msg: () -> String) {
        // ERROR 始终输出
        log.error(msg())
    }

    fun error(log: Logger, t: Throwable, msg: () -> String) {
        log.error(msg(), t)
    }

    /** 替代 System.err.println 的调试输出 */
    fun stdout(msg: () -> String) {
        if (currentLevel.allows(LogLevel.DEBUG)) System.err.println(msg())
    }

    /** 打印完整的异常堆栈（受日志级别控制） */
    fun stackTrace(e: Throwable) {
        if (currentLevel.allows(LogLevel.ERROR)) {
            e.printStackTrace(System.err)
        }
    }
}
