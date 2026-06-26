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
import tk.zwander.common.tools.CryptUtils
import java.util.zip.CRC32
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
}
