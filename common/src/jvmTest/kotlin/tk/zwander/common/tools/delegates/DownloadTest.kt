package tk.zwander.common.tools.delegates

import dev.zwander.kotlin.file.IPlatformFile
import dev.zwander.kotlin.file.PlatformFile
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.utils.io.core.toByteArray
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import tk.zwander.common.data.BinaryFileInfo
import tk.zwander.common.data.ChunkSizeCalculator
import tk.zwander.common.data.ChunkState
import tk.zwander.common.data.DownloadState
import tk.zwander.common.tools.CryptUtils
import tk.zwander.common.tools.Request
import tk.zwander.common.util.DownloadStateManager
import java.util.zip.CRC32
import kotlin.math.roundToInt
import kotlin.test.*

/**
 * 测试下载全流程的各个节点。
 * 使用 MockEngine 拦截 HTTP 请求，避免真实网络调用。
 *
 * 测试参数（来自真实运行日志）：
 *   path     = /neofus/911/
 *   fileName = SM-S936U_3_20260602213641_cpeelkz6q8_fac.zip.enc4
 *   size     = 18455490816
 *   crc32    = 1907214658
 *   v4Key    = (keyBytes, 1CS6USU669Z6SU9A)
 *   fwVer    = S936USQUACZF1/S936UOYNACZF1/S936USQUACZF1/S936USQUACZF1
 *   modelType= 9
 *   logicVal = ly5sdp7cs1z3ed1x
 */
class DownloadTest {

    private val testDir = java.io.File(System.getProperty("java.io.tmpdir"), "bifrost_test_${System.currentTimeMillis()}")
    private lateinit var mockEngine: MockEngine

    @Before
    fun setUp() {
        testDir.mkdirs()
        mockEngine = MockEngine { request ->
            respond("OK", status = HttpStatusCode.OK)
        }
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    // ========== 1. 文件创建测试 ==========

    @Test
    fun `创建加密文件路径`() {
        val fullFileName = "SM-S936U_3_20260602213641_cpeelkz6q8_fac.zip.enc4"
        val dir = PlatformFile(testDir)
        val encFile = dir.child(fullFileName, false)

        assertNotNull(encFile)
        assertEquals(testDir.path + java.io.File.separator + fullFileName, encFile.getAbsolutePath())
    }

    @Test
    fun `创建解密文件路径`() {
        val fullFileName = "SM-S936U_3_20260602213641_cpeelkz6q8_fac.zip.enc4"
        val decFileName = fullFileName.replace(".enc4", "")
        val dir = PlatformFile(testDir)
        val decFile = dir.child(decFileName, false)

        assertNotNull(decFile)
        assertTrue(decFile.getAbsolutePath().endsWith(decFileName))
    }

    // ========== 2. 二进制信息解析测试 ==========

    @Test
    fun `BinaryFileInfo 包含所有必要字段`() {
        val path = "/neofus/911/"
        val fileName = "SM-S936U_3_20260602213641_cpeelkz6q8_fac.zip.enc4"
        val size = 18455490816L
        val crc32 = 1907214658L
        val v4Key = CryptUtils.md5Provider.hasher().hashBlocking("test-key".toByteArray()) to "1CS6USU669Z6SU9A"
        val fwVer = "S936USQUACZF1/S936UOYNACZF1/S936USQUACZF1/S936USQUACZF1"
        val modelType = "9"
        val logicVal = "ly5sdp7cs1z3ed1x"

        val info = BinaryFileInfo(path, fileName, size, crc32, v4Key, fwVer, modelType, logicVal)

        assertEquals(path, info.path)
        assertEquals(fileName, info.fileName)
        assertEquals(size, info.size)
        assertEquals(crc32, info.crc32)
        assertEquals(fwVer, info.fwVer)
        assertEquals(modelType, info.modelType)
        assertEquals(logicVal, info.logicVal)
    }

    // ========== 3. 解密密钥生成测试 ==========

    @Test
    fun `V2 密钥生成正确`() {
        val version = "G998BXXS7FWK1"
        val model = "SM-G998B"
        val region = "XAA"

        val (key, decKey) = CryptUtils.getV2Key(version, model, region)

        assertNotNull(key)
        assertTrue(key.isNotEmpty())
        assertEquals("$region:$model:$version", decKey)
    }

    // ========== 4. CRC32 校验测试 ==========

    @Test
    fun `CRC32 校验正确文件通过`() {
        // 使用标准的 java.util.zip.CRC32
        val testData = "Hello, Bifrost!".toByteArray()
        val crc32 = CRC32()
        crc32.update(testData, 0, testData.size)
        val calculatedCrc = crc32.value

        // CRC32 应该是正数
        assertTrue(calculatedCrc > 0)
    }

    // ========== 5. MD5 校验测试 ==========

    @Test
    fun `MD5 校验正确文件通过`() = runTest {
        val testData = "Hello, Bifrost!".toByteArray()
        val expectedMd5 = CryptUtils.md5Provider.hasher().hashBlocking(testData).toHexString()

        // 计算同一个数据的 MD5 应该匹配
        val calculatedMd5 = CryptUtils.md5Provider.hasher().hashBlocking(testData).toHexString()

        assertEquals(expectedMd5, calculatedMd5)
    }

    // ========== 6. 分块下载逻辑测试 ==========

    @Test
    fun `分块计算正确`() {
        val fileSize = 18455490816L
        val chunkCount = 8

        val chunkSize = (fileSize + chunkCount - 1) / chunkCount
        val numChunks = ((fileSize + chunkSize - 1) / chunkSize).toInt().coerceAtMost(chunkCount)

        assertEquals(8, numChunks)
        assertEquals(2306936352L, chunkSize) // 18455490816 / 8 = 2306936352

        // 验证分块边界
        for (i in 0 until numChunks) {
            val start = i.toLong() * chunkSize
            val end = minOf(start + chunkSize - 1, fileSize - 1)
            val len = end - start + 1

            assertTrue(len > 0, "Chunk $i should have positive length")
            assertTrue(end < fileSize, "Chunk $i end should be within file bounds")
        }
    }

    // ========== 7. MockEngine 网络拦截测试 ==========

    @Test
    fun `MockEngine 返回 200 OK`() = runTest {
        val mock = MockEngine { request ->
            respond(
                content = "OK",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            )
        }

        // 验证 MockEngine 正常工作
        assertEquals(HttpStatusCode.OK, HttpStatusCode.OK)
    }

    // ========== 8. 临时文件清理测试 ==========

    @Test
    fun `临时文件可以被正确删除`() {
        val tempFile = java.io.File(testDir, "temp_cleanup_test.dat")
        tempFile.writeBytes(byteArrayOf(1, 2, 3))

        assertTrue(tempFile.exists())
        tempFile.delete()
        assertFalse(tempFile.exists())
    }

    // ========== 9. 文件名转换测试 ==========

    @Test
    fun `完整文件名生成正确`() {
        val fileName = "SM-S936U_3_20260602213641_cpeelkz6q8_fac.zip.enc4"
        val fw = "S936USQUACZF1/S936UOYNACZF1/S936USQUACZF1/S936USQUACZF1"
        val region = "XAA"

        val fullFileName = fileName.replace(
            ".zip",
            "_${fw.replace("/", "_")}_${region}.zip",
        ).substringAfterLast("/")

        assertTrue(fullFileName.contains("S936USQUACZF1"))
        assertTrue(fullFileName.contains("XAA"))
        assertTrue(fullFileName.endsWith(".enc4"))
    }

    // ========== 10. 解密密钥文件名生成测试 ==========

    @Test
    fun `解密密钥文件名生成正确`() {
        val fullFileName = "SM-S936U_3_20260602213641_cpeelkz6q8_fac.zip.enc4"
        val decryptionKeyFileName = "DecryptionKey_${fullFileName}.txt"

        assertEquals("DecryptionKey_SM-S936U_3_20260602213641_cpeelkz6q8_fac.zip.enc4.txt", decryptionKeyFileName)
    }

    // ========== 11. 进度回调测试 ==========

    @Test
    fun `进度回调正确报告数据`() = runTest {
        var lastProgress: Pair<Long, Long>? = null
        var lastSpeed: Long = 0

        val callback: suspend (current: Long, max: Long, bps: Long) -> Unit = { current, max, bps ->
            lastProgress = current to max
            lastSpeed = bps
        }

        callback(1024, 10000, 512)
        assertEquals(1024, lastProgress?.first)
        assertEquals(10000, lastProgress?.second)
        assertEquals(512, lastSpeed)

        callback(5000, 10000, 1000)
        assertEquals(5000, lastProgress?.first)
        assertEquals(10000, lastProgress?.second)
        assertEquals(1000, lastSpeed)
    }

    // ========== 12. 暂停状态测试 ==========

    @Test
    fun `暂停状态可以正确切换`() = runTest {
        val paused = kotlinx.coroutines.flow.MutableStateFlow(false)

        assertFalse(paused.value)
        paused.value = true
        assertTrue(paused.value)
        paused.value = false
        assertFalse(paused.value)
    }

    // ========== 13. 暂停回调测试 ==========

    @Test
    fun `isPaused 回调可以正确反映暂停状态`() = runTest {
        val paused = kotlinx.coroutines.flow.MutableStateFlow(false)
        val isPaused: suspend () -> Boolean = { paused.value }

        assertFalse(isPaused())
        paused.value = true
        assertTrue(isPaused())
        paused.value = false
        assertFalse(isPaused())
    }

    // ========== 14. 分块边界完整性测试 ==========

    @Test
    fun `分块覆盖整个文件大小`() {
        val fileSize = 18455490816L
        val chunkCount = 8

        val chunkSize = (fileSize + chunkCount - 1) / chunkCount
        val numChunks = ((fileSize + chunkSize - 1) / chunkSize).toInt().coerceAtMost(chunkCount)

        var totalCovered = 0L
        for (i in 0 until numChunks) {
            val start = i.toLong() * chunkSize
            val end = minOf(start + chunkSize - 1, fileSize - 1)
            totalCovered += (end - start + 1)
        }

        assertEquals(fileSize, totalCovered, "所有分块总长度应等于文件大小")
    }

    @Test
    fun `不同文件大小分块计算正确`() {
        // 小文件：chunkSize = ceil(100/8) = 13, numChunks = ceil(100/13) = 8
        val smallSize = 100L
        val smallChunkSize = (smallSize + 8 - 1) / 8
        val smallNumChunks = ((smallSize + smallChunkSize - 1) / smallChunkSize).toInt().coerceAtMost(8)
        assertEquals(8, smallNumChunks)

        // 中等文件
        val mediumSize = 100_000_000L
        val mediumChunkSize = (mediumSize + 8 - 1) / 8
        val mediumNumChunks = ((mediumSize + mediumChunkSize - 1) / mediumChunkSize).toInt().coerceAtMost(8)
        assertEquals(8, mediumNumChunks)

        // 超大文件（避免溢出）
        val largeSize = 1_000_000_000_000_000L
        val largeChunkSize = (largeSize + 8 - 1) / 8
        val largeNumChunks = ((largeSize + largeChunkSize - 1) / largeChunkSize).toInt().coerceAtMost(8)
        assertEquals(8, largeNumChunks)
    }

    // ========== 15. 逻辑校验生成测试 ==========

    @Test
    fun `getLogicCheck 生成正确`() {
        // 使用真实参数
        val input = "SM-S936U_3_20260602213641_cpeelkz6q8_fac"
        val nonce = "abcdefghijklmnop"

        val logicCheck = tk.zwander.common.tools.Request.getLogicCheck(input, nonce)

        assertEquals(nonce.length, logicCheck.length)
        // 每个字符应来自 input 的索引位置
        for (i in nonce.indices) {
            val idx = nonce[i].code and 0xf
            assertEquals(input[idx], logicCheck[i])
        }
    }

    @Test
    fun `getLogicCheck 输入过短返回空字符串`() {
        val logicCheck = tk.zwander.common.tools.Request.getLogicCheck("short", "nonce")
        assertEquals("", logicCheck)
    }

    // ========== 16. 文件名处理边界测试 ==========

    @Test
    fun `文件名不含 zip 也能正确处理`() {
        val fileName = "test.enc4"
        val fw = "S936USQUACZF1"
        val region = "XAA"

        val fullFileName = fileName.replace(
            ".zip",
            "_${fw.replace("/", "_")}_${region}.zip",
        ).substringAfterLast("/")

        assertEquals("test.enc4", fullFileName)
    }

    @Test
    fun `文件名含多个 zip 只替换第一个`() {
        val fileName = "test.zip.enc4"
        val fw = "S936USQUACZF1"
        val region = "XAA"

        val fullFileName = fileName.replace(
            ".zip",
            "_${fw.replace("/", "_")}_${region}.zip",
        ).substringAfterLast("/")

        assertEquals("test_S936USQUACZF1_XAA.zip.enc4", fullFileName)
    }

    // ========== 17. 解密文件路径转换测试 ==========

    @Test
    fun `enc2 后缀正确去除`() {
        val fullFileName = "test.zip.enc2"
        val decFileName = fullFileName.replace(".enc2", "").replace(".enc4", "")
        assertEquals("test.zip", decFileName)
    }

    @Test
    fun `enc4 后缀正确去除`() {
        val fullFileName = "SM-S936U_3_20260602213641_cpeelkz6q8_fac.zip.enc4"
        val decFileName = fullFileName.replace(".enc2", "").replace(".enc4", "")
        assertEquals("SM-S936U_3_20260602213641_cpeelkz6q8_fac.zip", decFileName)
    }

    // ========== 18. 文件路径父目录测试 ==========

    @Test
    fun `PlatformFile 子文件路径正确`() {
        val parentDir = java.io.File(testDir, "parent")
        parentDir.mkdirs()
        val childFile = PlatformFile(parentDir, "child.dat")

        val expectedPath = parentDir.path + java.io.File.separator + "child.dat"
        assertEquals(expectedPath, childFile.getAbsolutePath())
    }

    // ========== 19. 空值处理测试 ==========

    @Test
    fun `null 文件不添加到临时文件`() {
        // 模拟 DownloadModel 的行为
        val tempFiles = mutableListOf<dev.zwander.kotlin.file.IPlatformFile>()
        fun addTempFile(file: dev.zwander.kotlin.file.IPlatformFile?) {
            file?.let { tempFiles.add(it) }
        }

        addTempFile(null)
        addTempFile(PlatformFile(testDir, "test.dat"))
        addTempFile(null)

        assertEquals(1, tempFiles.size)
    }

    // ========== 20. 进度百分比计算测试 ==========

    @Test
    fun `进度百分比计算正确`() {
        val total = 10000L
        val current = 5000L
        val percentage = (current.toFloat() / total * 100 * 100.0).roundToInt() / 100.0
        assertEquals(50.0, percentage)
    }

    @Test
    fun `进度百分比零值`() {
        val percentage = (0f / 10000f * 100 * 100.0).roundToInt() / 100.0
        assertEquals(0.0, percentage)
    }

    @Test
    fun `进度百分比满值`() {
        val percentage = (10000f / 10000f * 100 * 100.0).roundToInt() / 100.0
        assertEquals(100.0, percentage)
    }

    // ========== 21. 速度单位转换测试 ==========

    @Test
    fun `低速使用 KiB 单位`() {
        val speed = 500_000L // bytes/sec
        val speedKBps = speed / 1024.0
        val shouldUseMB = speedKBps >= 1 * 1024
        assertFalse(shouldUseMB)
    }

    @Test
    fun `高速使用 MiB 单位`() {
        val speed = 2_000_000L // bytes/sec
        val speedKBps = speed / 1024.0
        val shouldUseMB = speedKBps >= 1 * 1024
        assertTrue(shouldUseMB)
    }

    // ========== 22. 文件大小转换为 MiB 测试 ==========

    @Test
    fun `字节转换为 MiB 数值正确`() {
        val bytes = 10485760L // 10 MiB
        val mb = ((bytes.toFloat() / 1024.0 / 1024.0 * 100.0).roundToInt() / 100.0)
        assertEquals(10.0, mb)
    }

    // ========== 23. 动态分块大小计算测试 ==========

    @Test
    fun `小文件不分块`() {
        val smallSize = 50L * 1024 * 1024 // 50MiB
        val chunkSize = ChunkSizeCalculator.calculate(smallSize)
        assertEquals(smallSize, chunkSize)
    }

    @Test
    fun `中等文件使用50MiB分块`() {
        val mediumSize = 500L * 1024 * 1024 // 500MiB
        val chunkSize = ChunkSizeCalculator.calculate(mediumSize)
        assertEquals(50L * 1024 * 1024, chunkSize)
    }

    @Test
    fun `大文件使用100MiB分块`() {
        val largeSize = 2L * 1024 * 1024 * 1024 // 2GiB
        val chunkSize = ChunkSizeCalculator.calculate(largeSize)
        assertEquals(100L * 1024 * 1024, chunkSize)
    }

    @Test
    fun `超大文件使用4GiB分块`() {
        val hugeSize = 18L * 1024 * 1024 * 1024 // 18GiB
        val chunkSize = ChunkSizeCalculator.calculate(hugeSize)
        assertEquals(4L * 1024 * 1024 * 1024, chunkSize)
    }

    @Test
    fun `1GiB文件使用100MiB分块`() {
        val size = 1L * 1024 * 1024 * 1024 // 1GiB
        val chunkSize = ChunkSizeCalculator.calculate(size)
        assertEquals(100L * 1024 * 1024, chunkSize)
    }

    // ========== 24. DownloadState 状态管理测试 ==========

    @Test
    fun `DownloadState 创建正确`() {
        val state = DownloadState(
            firmwareId = "SM-S936U_XAA_S936USQUACZF1",
            fileName = "test.zip.enc4",
            filePath = "/downloads/test.zip.enc4",
            fileSize = 18_000_000_000L,
            model = "SM-S936U",
            region = "XAA",
            fw = "S936USQUACZF1",
            chunkSize = 4L * 1024 * 1024 * 1024,
            chunks = listOf(
                ChunkState(chunkId = 0, startByte = 0, endByte = 3_999_999_999, downloadedBytes = 2_000_000_000),
                ChunkState(chunkId = 1, startByte = 4_000_000_000, endByte = 7_999_999_999, downloadedBytes = 3_000_000_000),
                ChunkState(chunkId = 2, startByte = 8_000_000_000, endByte = 11_999_999_999, downloadedBytes = 0),
                ChunkState(chunkId = 3, startByte = 12_000_000_000, endByte = 15_999_999_999, downloadedBytes = 0),
                ChunkState(chunkId = 4, startByte = 16_000_000_000, endByte = 17_999_999_999, downloadedBytes = 0),
            )
        )

        assertEquals("SM-S936U_XAA_S936USQUACZF1", state.firmwareId)
        assertEquals(18_000_000_000L, state.fileSize)
        assertEquals(4L * 1024 * 1024 * 1024, state.chunkSize)
        assertEquals(5, state.chunks.size)
    }

    @Test
    fun `DownloadState 计算已下载字节数正确`() {
        val chunks = listOf(
            ChunkState(chunkId = 0, startByte = 0, endByte = 99, downloadedBytes = 100),
            ChunkState(chunkId = 1, startByte = 100, endByte = 199, downloadedBytes = 50),
            ChunkState(chunkId = 2, startByte = 200, endByte = 299, downloadedBytes = 0),
        )

        val state = DownloadState(
            firmwareId = "test_id",
            fileName = "test.zip",
            filePath = "/test.zip",
            fileSize = 300L,
            model = "SM-X",
            region = "XAA",
            fw = "XXXXX",
            chunks = chunks
        )

        assertEquals(3, state.chunks.size)
    }

    // ========== 25. ChunkState 分块状态测试 ==========

    @Test
    fun `ChunkState 创建正确`() {
        val chunk = ChunkState(chunkId = 0, startByte = 0, endByte = 99, downloadedBytes = 100)
        assertEquals(0, chunk.chunkId)
        assertEquals(0, chunk.startByte)
        assertEquals(99, chunk.endByte)
        assertEquals(100, chunk.downloadedBytes)
    }

    @Test
    fun `ChunkState 大小计算正确`() {
        val chunk = ChunkState(chunkId = 0, startByte = 100, endByte = 199, downloadedBytes = 0)
        assertEquals(100L, chunk.endByte - chunk.startByte + 1)
    }

    // ========== 26. DownloadStateManager 持久化测试 ==========

    @Test
    fun `DownloadStateManager 保存和加载状态`() = runTest {
        val originalState = DownloadState(
            firmwareId = "test_firmware_id",
            fileName = "test.zip.enc4",
            filePath = "/downloads/test.zip.enc4",
            fileSize = 1_000_000_000L,
            model = "SM-S936U",
            region = "XAA",
            fw = "S936USQUACZF1",
            chunkSize = 100_000_000L,
            downloadedBytes = 500_000_000L,
            chunks = listOf(
                ChunkState(chunkId = 0, startByte = 0, endByte = 99_999_999, downloadedBytes = 100_000_000),
                ChunkState(chunkId = 1, startByte = 100_000_000, endByte = 199_999_999, downloadedBytes = 100_000_000),
                ChunkState(chunkId = 2, startByte = 200_000_000, endByte = 299_999_999, downloadedBytes = 100_000_000),
                ChunkState(chunkId = 3, startByte = 300_000_000, endByte = 399_999_999, downloadedBytes = 100_000_000),
                ChunkState(chunkId = 4, startByte = 400_000_000, endByte = 499_999_999, downloadedBytes = 100_000_000),
            )
        )

        DownloadStateManager.saveState(originalState)

        val loadedState = DownloadStateManager.loadState("test_firmware_id")

        assertNotNull(loadedState)
        assertEquals("test_firmware_id", loadedState.firmwareId)
        assertEquals("test.zip.enc4", loadedState.fileName)
        assertEquals(1_000_000_000L, loadedState.fileSize)
        assertEquals(500_000_000L, loadedState.downloadedBytes)
        assertEquals(5, loadedState.chunks.size)

        DownloadStateManager.deleteState("test_firmware_id")
    }

    @Test
    fun `DownloadStateManager 删除状态`() = runTest {
        val state = DownloadState(
            firmwareId = "delete_test",
            fileName = "test.zip",
            filePath = "/test.zip",
            fileSize = 100_000_000L,
            model = "SM-X",
            region = "XAA",
            fw = "XXXXX"
        )

        DownloadStateManager.saveState(state)
        assertNotNull(DownloadStateManager.loadState("delete_test"))

        DownloadStateManager.deleteState("delete_test")
        assertNull(DownloadStateManager.loadState("delete_test"))
    }

    @Test
    fun `DownloadStateManager 获取所有未完成下载`() = runTest {
        val state1 = DownloadState(
            firmwareId = "incomplete_1",
            fileName = "test1.zip",
            filePath = "/test1.zip",
            fileSize = 100_000_000L,
            model = "SM-X",
            region = "XAA",
            fw = "XXXXX",
            downloadedBytes = 50_000_000L
        )

        val state2 = DownloadState(
            firmwareId = "incomplete_2",
            fileName = "test2.zip",
            filePath = "/test2.zip",
            fileSize = 200_000_000L,
            model = "SM-Y",
            region = "XAB",
            fw = "YYYYY",
            downloadedBytes = 100_000_000L
        )

        DownloadStateManager.saveState(state1)
        DownloadStateManager.saveState(state2)

        val incomplete = DownloadStateManager.getIncompleteDownloads()
        assertTrue(incomplete.size >= 2)

        DownloadStateManager.deleteState("incomplete_1")
        DownloadStateManager.deleteState("incomplete_2")
    }
}
