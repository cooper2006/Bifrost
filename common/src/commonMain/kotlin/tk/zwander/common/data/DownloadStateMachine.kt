package tk.zwander.common.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 下载状态机，管理 [DownloadPhase] 的合法转换。
 *
 * 当前为宽松模式：允许任意状态到任意状态的转换。
 * 后续可添加严格校验（如不允许从 Idle 直接跳到 Done）。
 *
 * 每个 [DownloadModel] 持有此状态机的一个实例，
 * 替代原先的 progress/speed/statusText 三个独立字段。
 */
class DownloadStateMachine {
    private val _state = MutableStateFlow<DownloadPhase>(DownloadPhase.Idle)
    val state: StateFlow<DownloadPhase> = _state.asStateFlow()

    /**
     * 转换到新状态。
     * @param newState 目标状态
     */
    fun transition(newState: DownloadPhase) {
        _state.value = newState
    }

    /**
     * 带进度的转换。
     * @param current 当前进度
     * @param max 总大小
     * @param bytesPerSecond 速度
     */
    fun transitionWithProgress(
        phase: (Progress) -> DownloadPhase,
        current: Long,
        max: Long,
        bytesPerSecond: Long = 0L,
    ) {
        _state.value = phase(Progress(current, max, bytesPerSecond))
    }

    /**
     * 重置到空闲状态。
     */
    fun reset() {
        _state.value = DownloadPhase.Idle
    }

    /** 当前是否处于非空闲状态（有任务在运行）。 */
    val isActive: Boolean
        get() = _state.value !is DownloadPhase.Idle
}
