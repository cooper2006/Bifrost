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
import tk.zwander.common.util.BifrostLogger

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
        BifrostLogger.general.info("JobManager.launch: starting new job")
        val job = scope.launch(block = block)
        _hasRunningJobs.value = true
        job.invokeOnCompletion { cause ->
            val activeCount = scope.coroutineContext[Job]?.children?.count { it.isActive } ?: 0
            BifrostLogger.general.info("JobManager.invokeOnCompletion: cause=$cause, remaining active jobs=$activeCount")
            _hasRunningJobs.value = activeCount > 0
        }
        return job
    }

    /**
     * 取消所有正在运行的协程。
     */
    fun cancelAll() {
        val children = scope.coroutineContext[Job]?.children?.toList()
        BifrostLogger.general.info("JobManager.cancelAll: cancelling ${children?.size ?: 0} jobs")
        children?.forEach { it.cancel() }
        _hasRunningJobs.value = false
        BifrostLogger.general.info("JobManager.cancelAll: hasRunningJobs set to false")
    }

    /**
     * 取消指定 Job。
     */
    fun cancel(job: Job) {
        BifrostLogger.general.info("JobManager.cancel: cancelling single job")
        job.cancel()
    }
}
