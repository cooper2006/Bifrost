package tk.zwander.common.tools

import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import tk.zwander.common.data.DownloadPhase
import tk.zwander.common.data.DownloadStateMachine
import tk.zwander.common.data.Progress

/**
 * 测试 DownloadStateMachine 的状态转换逻辑。
 *
 * 覆盖范围（按设计文档优先级）：
 * - P1: DownloadStateMachine.transition — 合法/非法状态转换
 */
class DownloadStateMachineTest {

    // ==================== 初始状态 ====================

    @Test
    fun `初始状态为Idle`() {
        val machine = DownloadStateMachine()
        assertEquals(DownloadPhase.Idle, machine.state.value)
    }

    @Test
    fun `初始状态isActive为false`() {
        val machine = DownloadStateMachine()
        assertFalse(machine.isActive)
    }

    // ==================== 基本转换 ====================

    @Test
    fun `从Idle转换到FetchingInfo`() {
        val machine = DownloadStateMachine()
        machine.transition(DownloadPhase.FetchingInfo)
        assertEquals(DownloadPhase.FetchingInfo, machine.state.value)
    }

    @Test
    fun `从FetchingInfo转换到BinaryInit`() {
        val machine = DownloadStateMachine()
        machine.transition(DownloadPhase.FetchingInfo)
        machine.transition(DownloadPhase.BinaryInit)
        assertEquals(DownloadPhase.BinaryInit, machine.state.value)
    }

    @Test
    fun `完整的下载生命周期转换`() {
        val machine = DownloadStateMachine()

        machine.transition(DownloadPhase.FetchingInfo)
        assertIs<DownloadPhase.FetchingInfo>(machine.state.value)
        assertTrue(machine.isActive)

        machine.transition(DownloadPhase.BinaryInit)
        assertIs<DownloadPhase.BinaryInit>(machine.state.value)

        machine.transition(DownloadPhase.Downloading(Progress(0, 1000)))
        assertIs<DownloadPhase.Downloading>(machine.state.value)

        machine.transition(DownloadPhase.VerifyingCrc(Progress(0, 1000)))
        assertIs<DownloadPhase.VerifyingCrc>(machine.state.value)

        machine.transition(DownloadPhase.VerifyingMd5(Progress(0, 1000)))
        assertIs<DownloadPhase.VerifyingMd5>(machine.state.value)

        machine.transition(DownloadPhase.Copying(Progress(0, 1000)))
        assertIs<DownloadPhase.Copying>(machine.state.value)

        machine.transition(DownloadPhase.Decrypting(Progress(0, 1000)))
        assertIs<DownloadPhase.Decrypting>(machine.state.value)

        machine.transition(DownloadPhase.Done("完成"))
        assertIs<DownloadPhase.Done>(machine.state.value)
        assertEquals("完成", (machine.state.value as DownloadPhase.Done).message)
    }

    // ==================== 错误状态 ====================

    @Test
    fun `任意状态可转换到Error`() {
        val machine = DownloadStateMachine()

        machine.transition(DownloadPhase.Downloading(Progress(500, 1000)))
        machine.transition(DownloadPhase.Error("出错了"))

        val error = machine.state.value
        assertIs<DownloadPhase.Error>(error)
        assertEquals("出错了", error.message)
        assertNull(error.exception)
    }

    @Test
    fun `Error状态携带异常信息`() {
        val machine = DownloadStateMachine()
        val exception = RuntimeException("测试异常")

        machine.transition(DownloadPhase.Error("出错了", exception))

        val error = machine.state.value as DownloadPhase.Error
        assertEquals("出错了", error.message)
        assertEquals(exception, error.exception)
    }

    @Test
    fun `从Error状态可转换回Idle`() {
        val machine = DownloadStateMachine()
        machine.transition(DownloadPhase.Error("出错了"))
        machine.reset()

        assertEquals(DownloadPhase.Idle, machine.state.value)
    }

    // ==================== reset ====================

    @Test
    fun `reset回到Idle`() {
        val machine = DownloadStateMachine()
        machine.transition(DownloadPhase.Downloading(Progress(500, 1000)))
        machine.reset()

        assertEquals(DownloadPhase.Idle, machine.state.value)
        assertFalse(machine.isActive)
    }

    @Test
    fun `重复reset不报错`() {
        val machine = DownloadStateMachine()
        machine.reset()
        machine.reset()
        machine.reset()

        assertEquals(DownloadPhase.Idle, machine.state.value)
    }

    // ==================== transitionWithProgress ====================

    @Test
    fun `transitionWithProgress创建Downloading状态`() {
        val machine = DownloadStateMachine()
        machine.transitionWithProgress(DownloadPhase::Downloading, 500, 1000, 200)

        val state = machine.state.value
        assertIs<DownloadPhase.Downloading>(state)
        assertEquals(500, state.progress.current)
        assertEquals(1000, state.progress.max)
        assertEquals(200, state.progress.bytesPerSecond)
    }

    @Test
    fun `transitionWithProgress创建VerifyingCrc状态`() {
        val machine = DownloadStateMachine()
        machine.transitionWithProgress(DownloadPhase::VerifyingCrc, 300, 900)

        val state = machine.state.value
        assertIs<DownloadPhase.VerifyingCrc>(state)
        assertEquals(300, state.progress.current)
        assertEquals(900, state.progress.max)
        assertEquals(0, state.progress.bytesPerSecond) // 默认值
    }

    @Test
    fun `transitionWithProgress创建VerifyingMd5状态`() {
        val machine = DownloadStateMachine()
        machine.transitionWithProgress(DownloadPhase::VerifyingMd5, 0, 1)

        val state = machine.state.value
        assertIs<DownloadPhase.VerifyingMd5>(state)
    }

    @Test
    fun `transitionWithProgress创建Copying状态`() {
        val machine = DownloadStateMachine()
        machine.transitionWithProgress(DownloadPhase::Copying, 0, 500)

        val state = machine.state.value
        assertIs<DownloadPhase.Copying>(state)
    }

    @Test
    fun `transitionWithProgress创建Decrypting状态`() {
        val machine = DownloadStateMachine()
        machine.transitionWithProgress(DownloadPhase::Decrypting, 0, 100)

        val state = machine.state.value
        assertIs<DownloadPhase.Decrypting>(state)
    }

    // ==================== isActive ====================

    @Test
    fun `非Idle状态isActive为true`() {
        val machine = DownloadStateMachine()
        machine.transition(DownloadPhase.FetchingInfo)
        assertTrue(machine.isActive)

        machine.transition(DownloadPhase.BinaryInit)
        assertTrue(machine.isActive)

        machine.transition(DownloadPhase.Downloading(Progress(0, 100)))
        assertTrue(machine.isActive)

        machine.transition(DownloadPhase.VerifyingCrc(Progress(0, 100)))
        assertTrue(machine.isActive)

        machine.transition(DownloadPhase.Done("done"))
        assertTrue(machine.isActive)
    }

    @Test
    fun `Error状态isActive为true`() {
        val machine = DownloadStateMachine()
        machine.transition(DownloadPhase.Error("error"))
        assertTrue(machine.isActive)
    }

    @Test
    fun `Idle状态isActive为false`() {
        val machine = DownloadStateMachine()
        assertFalse(machine.isActive)
    }

    @Test
    fun `reset后isActive为false`() {
        val machine = DownloadStateMachine()
        machine.transition(DownloadPhase.Downloading(Progress(0, 100)))
        assertTrue(machine.isActive)
        machine.reset()
        assertFalse(machine.isActive)
    }

    // ==================== StateFlow ====================

    @Test
    fun `state作为StateFlow可被收集`() = kotlinx.coroutines.test.runTest {
        val machine = DownloadStateMachine()
        val initial = machine.state.first()
        assertEquals(DownloadPhase.Idle, initial)

        machine.transition(DownloadPhase.FetchingInfo)
        val afterTransition = machine.state.first()
        assertEquals(DownloadPhase.FetchingInfo, afterTransition)
    }

    // ==================== Done / Error 消息 ====================

    @Test
    fun `Done状态携带正确消息`() {
        val machine = DownloadStateMachine()
        machine.transition(DownloadPhase.Done("下载完成"))

        val done = machine.state.value as DownloadPhase.Done
        assertEquals("下载完成", done.message)
    }

    @Test
    fun `连续多次转换状态正确变化`() {
        val machine = DownloadStateMachine()
        val phases = listOf(
            DownloadPhase.FetchingInfo,
            DownloadPhase.BinaryInit,
            DownloadPhase.Downloading(Progress(0, 100)),
            DownloadPhase.VerifyingCrc(Progress(50, 100)),
            DownloadPhase.VerifyingMd5(Progress(80, 100)),
            DownloadPhase.Copying(Progress(90, 100)),
            DownloadPhase.Decrypting(Progress(95, 100)),
            DownloadPhase.Done("完成"),
        )

        for (phase in phases) {
            machine.transition(phase)
            assertEquals(phase::class, machine.state.value::class)
        }
    }
}
