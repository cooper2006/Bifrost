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
 * 便捷的日志扩展函数 - 带懒加载和异常支持
 * 这些函数不会与 SLF4J 原生方法冲突
 */
inline fun org.slf4j.Logger.debugLazy(msg: () -> String) {
    if (isDebugEnabled) {
        debug(msg())
    }
}

inline fun org.slf4j.Logger.infoLazy(msg: () -> String) {
    if (isInfoEnabled) {
        info(msg())
    }
}

inline fun org.slf4j.Logger.warnLazy(msg: () -> String) {
    if (isWarnEnabled) {
        warn(msg())
    }
}

inline fun org.slf4j.Logger.errorLazy(msg: () -> String) {
    if (isErrorEnabled) {
        error(msg())
    }
}

/**
 * 带异常的懒加载日志扩展函数
 */
inline fun org.slf4j.Logger.debugLazy(msg: () -> String, throwable: () -> Throwable) {
    if (isDebugEnabled) {
        debug(msg(), throwable())
    }
}

inline fun org.slf4j.Logger.infoLazy(msg: () -> String, throwable: () -> Throwable) {
    if (isInfoEnabled) {
        info(msg(), throwable())
    }
}

inline fun org.slf4j.Logger.warnLazy(msg: () -> String, throwable: () -> Throwable) {
    if (isWarnEnabled) {
        warn(msg(), throwable())
    }
}

inline fun org.slf4j.Logger.errorLazy(msg: () -> String, throwable: () -> Throwable) {
    if (isErrorEnabled) {
        error(msg(), throwable())
    }
}
