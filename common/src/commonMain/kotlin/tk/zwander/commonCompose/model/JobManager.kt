package tk.zwander.commonCompose.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 管理协程生命周期的工具类。
 * 职责单一：只负责协程的启动和取消，不关心状态字段。
 * 使用 SupervisorJob 确保子协程独立失败，互不影响。
 */
class JobManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _hasRunningJobs = MutableStateFlow(false)
    val hasRunningJobs: StateFlow<Boolean> = _hasRunningJobs.asStateFlow()

    /**
     * 启动一个新协程。
     * @param block 协程体
     * @return 启动的 Job 引用
     */
    fun launch(block: suspend CoroutineScope.() -> Unit): Job {
        val job = scope.launch(block = block)
        _hasRunningJobs.value = true
        job.invokeOnCompletion {
            _hasRunningJobs.value = scope.coroutineContext[Job]?.children?.any { it.isActive } == true
        }
        return job
    }

    /**
     * 取消所有正在运行的协程。
     */
    fun cancelAll() {
        scope.coroutineContext[Job]?.children?.forEach { it.cancel() }
        _hasRunningJobs.value = false
    }

    /**
     * 取消指定 Job。
     */
    fun cancel(job: Job) {
        job.cancel()
    }
}
