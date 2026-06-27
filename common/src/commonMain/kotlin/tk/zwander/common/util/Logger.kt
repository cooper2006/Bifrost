package tk.zwander.common.util

import org.slf4j.LoggerFactory

/**
 * 日志工具类，提供统一的日志接口
 * 使用 SLF4J 框架，支持不同日志级别控制
 */
object Logger {
    /**
     * 获取指定类的日志记录器
     */
    fun getLogger(clazz: Class<*>): org.slf4j.Logger {
        return LoggerFactory.getLogger(clazz)
    }
    
    /**
     * 获取指定名称的日志记录器
     */
    fun getLogger(name: String): org.slf4j.Logger {
        return LoggerFactory.getLogger(name)
    }
}

/**
 * 便捷的日志扩展函数 - 仅消息版本
 */
inline fun org.slf4j.Logger.debug(msg: String) {
    if (isDebugEnabled) {
        debug(msg)
    }
}

inline fun org.slf4j.Logger.info(msg: String) {
    if (isInfoEnabled) {
        info(msg)
    }
}

inline fun org.slf4j.Logger.warn(msg: String) {
    if (isWarnEnabled) {
        warn(msg)
    }
}

inline fun org.slf4j.Logger.error(msg: String) {
    if (isErrorEnabled) {
        error(msg)
    }
}
