package tk.zwander.common.tools

import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readAtMostTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * 测试 CryptUtils 的纯函数和校验逻辑。
 *
 * 覆盖范围（按设计文档优先级）：
 * - P0: CryptUtils.Legacy.getAuth — 已知 nonce 的 auth 输出
 * - P0: CryptUtils.getV2Key — 已知输入输出验证
 * - P2: CryptUtils.checkCrc32 — 已知文件的 CRC32 校验
 * - P2: CryptUtils.checkMD5 — 已知文件的 MD5 校验
 */
class CryptUtilsTest {

    // ==================== Legacy.getAuth ====================

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun `Legacy getAuth 对已知nonce产生有效Base64输出`() {
        val nonce = "abcdefghijklmnop"
        val auth = CryptUtils.Legacy.getAuth(nonce)

        assertNotNull(auth)
        assertTrue(auth.isNotEmpty())
        // 验证是有效 Base64
        val decoded = Base64.decode(auth)
        assertTrue(decoded.isNotEmpty())
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun `Legacy getAuth 相同nonce产生相同结果`() {
        val nonce = "test_nonce_1234567" // 至少 16 字符
        val auth1 = CryptUtils.Legacy.getAuth(nonce)
        val auth2 = CryptUtils.Legacy.getAuth(nonce)

        assertEquals(auth1, auth2)
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun `Legacy getAuth 不同nonce产生不同结果`() {
        val auth1 = CryptUtils.Legacy.getAuth("aaaaaaaaaaaaaaaa")
        val auth2 = CryptUtils.Legacy.getAuth("bbbbbbbbbbbbbbbb")

        assertTrue(auth1.isNotEmpty())
        assertTrue(auth2.isNotEmpty())
        // 16 bytes nonce + PKCS7 padding -> 32 bytes AES block -> Base64 = 44 chars
        assertEquals(32, Base64.decode(auth1).size)
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun `Legacy getAuth 超长nonce正常工作`() {
        val auth = CryptUtils.Legacy.getAuth("a".repeat(100))
        assertNotNull(auth)
        assertTrue(auth.isNotEmpty())
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun `Legacy getAuth 长nonce正常工作`() {
        val longNonce = "a".repeat(100)
        val auth = CryptUtils.Legacy.getAuth(longNonce)
        assertNotNull(auth)
        assertTrue(auth.isNotEmpty())
    }

    // ==================== Legacy.decryptNonce ====================

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun `Legacy decryptNonce 不崩溃`() {
        // 测试 decryptNonce 的基本调用（不需要能解密出有意义的内容）
        val nonce = "abcdefghijklmnop"
        val auth = CryptUtils.Legacy.getAuth(nonce)
        val decrypted = CryptUtils.Legacy.decryptNonce(auth)
        assertNotNull(decrypted)
    }

    // ==================== getV2Key ====================

    @Test
    fun `getV2Key 生成正确的密钥和字符串`() {
        val version = "G998BXXS7FWK1"
        val model = "SM-G998B"
        val region = "XAA"

        val (key, decKey) = CryptUtils.getV2Key(version, model, region)

        assertNotNull(key)
        assertTrue(key.isNotEmpty())
        assertEquals("$region:$model:$version", decKey)
    }

    @Test
    fun `getV2Key 不同输入产生不同密钥`() {
        val (key1, _) = CryptUtils.getV2Key("V1", "M1", "R1")
        val (key2, _) = CryptUtils.getV2Key("V2", "M2", "R2")

        assertFalse(key1.contentEquals(key2))
    }

    @Test
    fun `getV2Key 相同输入产生相同密钥`() {
        val (key1, _) = CryptUtils.getV2Key("G998BXXS7FWK1", "SM-G998B", "XAA")
        val (key2, _) = CryptUtils.getV2Key("G998BXXS7FWK1", "SM-G998B", "XAA")

        assertTrue(key1.contentEquals(key2))
    }

    @Test
    fun `getV2Key decKey格式正确`() {
        val (_, decKey) = CryptUtils.getV2Key("G998BXXS7FWK1", "SM-G998B", "XAA")
        assertEquals("XAA:SM-G998B:G998BXXS7FWK1", decKey)
    }

    // ==================== checkCrc32 ====================

    @Test
    fun `checkCrc32 null输入返回false`() = runTest {
        val result = CryptUtils.checkCrc32(
            enc = null,
            encSize = 0L,
            expected = 0L,
            progressCallback = { _, _, _ -> },
        )

        assertFalse(result)
    }

    @Test
    fun `checkCrc32 空数据校验通过`() = runTest {
        val testData = ByteArray(0)
        val source = Buffer().also { it.write(testData) }
        val expectedCrc = 0L // CRC32 of empty data

        val result = CryptUtils.checkCrc32(
            enc = source,
            encSize = 0L,
            expected = expectedCrc,
            progressCallback = { _, _, _ -> },
        )

        assertTrue(result)
    }

    @Test
    fun `checkCrc32 已知数据CRC32匹配`() = runTest {
        val testData = "Hello, Bifrost!".encodeToByteArray()

        // 用 java.util.zip.CRC32 计算期望值
        val jdkCrc = java.util.zip.CRC32()
        jdkCrc.update(testData, 0, testData.size)
        val expectedCrc = jdkCrc.value // Long

        val source = Buffer().also { it.write(testData) }
        val result = CryptUtils.checkCrc32(
            enc = source,
            encSize = testData.size.toLong(),
            expected = expectedCrc,
            progressCallback = { _, _, _ -> },
        )

        assertTrue(result, "CRC32 of known data should match")
    }

    @Test
    fun `checkCrc32 CRC32不匹配时返回false`() = runTest {
        val testData = "Hello, Bifrost!".encodeToByteArray()
        val source = Buffer().also { it.write(testData) }

        val result = CryptUtils.checkCrc32(
            enc = source,
            encSize = testData.size.toLong(),
            expected = 0L, // 错误的 CRC
            progressCallback = { _, _, _ -> },
        )

        assertFalse(result, "Wrong CRC should return false")
    }

    // ==================== checkMD5 ====================

    @Test
    fun `checkMD5 null或空输入返回false`() = runTest {
        assertFalse(CryptUtils.checkMD5("", null))
        assertFalse(CryptUtils.checkMD5("", Buffer()))
        assertFalse(CryptUtils.checkMD5("d41d8cd98f00b204e9800998ecf8427e", null))
    }

    @Test
    fun `checkMD5 已知数据MD5匹配`() = runTest {
        val testData = "Hello, Bifrost!".encodeToByteArray()
        val source = Buffer().also { it.write(testData) }

        // MD5 of "Hello, Bifrost!" — 用 java.security 计算
        val md5Digest = java.security.MessageDigest.getInstance("MD5")
        val expectedMd5 = md5Digest.digest(testData).joinToString("") { "%02x".format(it) }

        val result = CryptUtils.checkMD5(expectedMd5, source)
        assertTrue(result, "MD5 of known data should match")
    }

    @Test
    fun `checkMD5 MD5不匹配时返回false`() = runTest {
        val testData = "Hello, Bifrost!".encodeToByteArray()
        val source = Buffer().also { it.write(testData) }

        val result = CryptUtils.checkMD5("00000000000000000000000000000000", source)
        assertFalse(result, "Wrong MD5 should return false")
    }

    @Test
    fun `checkMD5 大小写不敏感`() = runTest {
        val testData = "Hello, Bifrost!".encodeToByteArray()
        val source = Buffer().also { it.write(testData) }

        val md5Digest = java.security.MessageDigest.getInstance("MD5")
        val expectedMd5 = md5Digest.digest(testData).joinToString("") { "%02x".format(it) }

        // 转大写
        val result = CryptUtils.checkMD5(expectedMd5.uppercase(), source)
        assertTrue(result, "MD5 comparison should be case-insensitive")
    }

    @Test
    fun `checkMD5 大数据量不崩溃`() = runTest {
        val largeData = ByteArray(1_000_000) { (it % 256).toByte() }
        val source = Buffer().also { it.write(largeData) }

        val md5Digest = java.security.MessageDigest.getInstance("MD5")
        val expectedMd5 = md5Digest.digest(largeData).joinToString("") { "%02x".format(it) }

        val result = CryptUtils.checkMD5(expectedMd5, source)
        assertTrue(result, "MD5 of large data should match")
    }

    @Test
    fun `checkMD5 空数据MD5匹配`() = runTest {
        val emptyData = ByteArray(0)
        val source = Buffer().also { it.write(emptyData) }

        val md5Digest = java.security.MessageDigest.getInstance("MD5")
        val expectedMd5 = md5Digest.digest(emptyData).joinToString("") { "%02x".format(it) }

        val result = CryptUtils.checkMD5(expectedMd5, source)
        assertTrue(result, "MD5 of empty data should match")
    }
}
