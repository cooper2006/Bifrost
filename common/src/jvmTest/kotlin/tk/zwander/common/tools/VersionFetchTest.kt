package tk.zwander.common.tools

import com.fleeksoft.ksoup.Ksoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 测试 VersionFetch 对象的 XML 解析逻辑。
 *
 * 覆盖范围（按设计文档优先级）：
 * - P1: parseHistoryInfos — 已知 XML 输入的正确解析
 */
class VersionFetchTest {

    @Test
    fun `parseHistoryInfos 解析空文档返回空列表`() {
        val doc = Ksoup.parse("<root></root>")
        val result = VersionFetch.parseHistoryInfos(doc)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseHistoryInfos 解析无BINARY_INFO的文档返回空列表`() {
        val doc = Ksoup.parse("""
            <FUSMsg>
              <FUSBody>
                <Put></Put>
              </FUSBody>
            </FUSMsg>
        """.trimIndent())
        val result = VersionFetch.parseHistoryInfos(doc)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseHistoryInfos 解析单个BINARY_INFO`() {
        val doc = Ksoup.parse("""
            <FUSMsg>
              <FUSBody>
                <Put>
                  <BINARY_INFO>
                    <BINARY_INDEX><Data>1</Data></BINARY_INDEX>
                    <BINARY_SEQUENCE><Data>3</Data></BINARY_SEQUENCE>
                    <BINARY_MODEL_NAME><Data>SM-S936U</Data></BINARY_MODEL_NAME>
                    <BINARY_MODEL_DISPLAYNAME><Data>Galaxy S24+</Data></BINARY_MODEL_DISPLAYNAME>
                    <BINARY_SW_VERSION><Data>S936USQUACZF1</Data></BINARY_SW_VERSION>
                    <BINARY_SW_DISPLAYVERSION><Data>S936USQUACZF1</Data></BINARY_SW_DISPLAYVERSION>
                    <BINARY_LOCAL_CODE><Data>XAA</Data></BINARY_LOCAL_CODE>
                    <BINARY_BUYER_CODE><Data>XAA</Data></BINARY_BUYER_CODE>
                    <BINARY_NATURE><Data>1</Data></BINARY_NATURE>
                    <BINARY_STATUS><Data>0</Data></BINARY_STATUS>
                    <BINARY_EXIST><Data>1</Data></BINARY_EXIST>
                    <BINARY_OS_NAME><Data>Android 15</Data></BINARY_OS_NAME>
                    <DEVICE_PLATFORM><Data>ARM64</Data></DEVICE_PLATFORM>
                    <BINARY_OPEN_DATE><Data>2026-06-05</Data></BINARY_OPEN_DATE>
                    <SHARING_BINARY><Data>0</Data></SHARING_BINARY>
                    <BINARY_CATEGORY><Data>General</Data></BINARY_CATEGORY>
                    <AID_OPEN><Data>1</Data></AID_OPEN>
                  </BINARY_INFO>
                </Put>
              </FUSBody>
            </FUSMsg>
        """.trimIndent())

        val result = VersionFetch.parseHistoryInfos(doc)
        assertEquals(1, result.size)

        val info = result[0]
        assertEquals(1, info.index)
        assertEquals(3, info.sequence)
        assertEquals("SM-S936U", info.modelName)
        assertEquals("Galaxy S24+", info.displayName)
        assertEquals("S936USQUACZF1", info.swVersion)
        assertEquals("S936USQUACZF1", info.displayVersion)
        assertEquals("XAA", info.localCode)
        assertEquals("XAA", info.buyerCode)
        assertEquals(1, info.nature)
        assertEquals(0, info.status)
        assertEquals(1, info.exists)
        assertEquals("Android 15", info.osName)
        assertEquals("ARM64", info.platform)
        assertEquals("2026-06-05", info.openDate)
        assertEquals(0, info.sharing)
        assertEquals("General", info.category)
        assertEquals(1, info.open)
    }

    @Test
    fun `parseHistoryInfos 解析多个BINARY_INFO并按sequence排序`() {
        val doc = Ksoup.parse("""
            <FUSMsg>
              <FUSBody>
                <Put>
                  <BINARY_INFO>
                    <BINARY_INDEX><Data>3</Data></BINARY_INDEX>
                    <BINARY_SEQUENCE><Data>3</Data></BINARY_SEQUENCE>
                    <BINARY_MODEL_NAME><Data>SM-S936U</Data></BINARY_MODEL_NAME>
                    <BINARY_SW_VERSION><Data>S936USQUACZF1</Data></BINARY_SW_VERSION>
                    <BINARY_LOCAL_CODE><Data>XAA</Data></BINARY_LOCAL_CODE>
                    <BINARY_OS_NAME><Data>Android 15</Data></BINARY_OS_NAME>
                  </BINARY_INFO>
                  <BINARY_INFO>
                    <BINARY_INDEX><Data>1</Data></BINARY_INDEX>
                    <BINARY_SEQUENCE><Data>1</Data></BINARY_SEQUENCE>
                    <BINARY_MODEL_NAME><Data>SM-S936U</Data></BINARY_MODEL_NAME>
                    <BINARY_SW_VERSION><Data>S936USQU1AXB5</Data></BINARY_SW_VERSION>
                    <BINARY_LOCAL_CODE><Data>XAA</Data></BINARY_LOCAL_CODE>
                    <BINARY_OS_NAME><Data>Android 14</Data></BINARY_OS_NAME>
                  </BINARY_INFO>
                  <BINARY_INFO>
                    <BINARY_INDEX><Data>2</Data></BINARY_INDEX>
                    <BINARY_SEQUENCE><Data>2</Data></BINARY_SEQUENCE>
                    <BINARY_MODEL_NAME><Data>SM-S936U</Data></BINARY_MODEL_NAME>
                    <BINARY_SW_VERSION><Data>S936USQU2BXI7</Data></BINARY_SW_VERSION>
                    <BINARY_LOCAL_CODE><Data>XAA</Data></BINARY_LOCAL_CODE>
                    <BINARY_OS_NAME><Data>Android 15</Data></BINARY_OS_NAME>
                  </BINARY_INFO>
                </Put>
              </FUSBody>
            </FUSMsg>
        """.trimIndent())

        val result = VersionFetch.parseHistoryInfos(doc)
        assertEquals(3, result.size)
        // 应按 sequence 升序排列
        assertEquals("S936USQU1AXB5", result[0].swVersion)
        assertEquals("S936USQU2BXI7", result[1].swVersion)
        assertEquals("S936USQUACZF1", result[2].swVersion)
    }

    @Test
    fun `parseHistoryInfos 过滤掉beta固件`() {
        val doc = Ksoup.parse("""
            <FUSMsg>
              <FUSBody>
                <Put>
                  <BINARY_INFO>
                    <BINARY_INDEX><Data>1</Data></BINARY_INDEX>
                    <BINARY_SEQUENCE><Data>1</Data></BINARY_SEQUENCE>
                    <BINARY_MODEL_NAME><Data>SM-S936U</Data></BINARY_MODEL_NAME>
                    <BINARY_SW_VERSION><Data>S936USQUACZF1</Data></BINARY_SW_VERSION>
                    <BINARY_LOCAL_CODE><Data>XAA</Data></BINARY_LOCAL_CODE>
                    <BINARY_OS_NAME><Data>Android 15</Data></BINARY_OS_NAME>
                  </BINARY_INFO>
                  <BINARY_INFO>
                    <BINARY_INDEX><Data>2</Data></BINARY_INDEX>
                    <BINARY_SEQUENCE><Data>2</Data></BINARY_SEQUENCE>
                    <BINARY_MODEL_NAME><Data>SM-S936U</Data></BINARY_MODEL_NAME>
                    <BINARY_SW_VERSION><Data>S936USXXXXXX</Data></BINARY_SW_VERSION>
                    <BINARY_LOCAL_CODE><Data>XAA</Data></BINARY_LOCAL_CODE>
                    <BINARY_OS_NAME><Data>Z(Android 99)</Data></BINARY_OS_NAME>
                  </BINARY_INFO>
                </Put>
              </FUSBody>
            </FUSMsg>
        """.trimIndent())

        val result = VersionFetch.parseHistoryInfos(doc)
        assertEquals(1, result.size)
        assertEquals("S936USQUACZF1", result[0].swVersion)
    }

    @Test
    fun `parseHistoryInfos 部分字段缺失时不崩溃`() {
        val doc = Ksoup.parse("""
            <FUSMsg>
              <FUSBody>
                <Put>
                  <BINARY_INFO>
                    <BINARY_INDEX><Data>1</Data></BINARY_INDEX>
                    <BINARY_SEQUENCE><Data>5</Data></BINARY_SEQUENCE>
                    <BINARY_MODEL_NAME><Data>SM-S936U</Data></BINARY_MODEL_NAME>
                    <BINARY_LOCAL_CODE><Data>XAA</Data></BINARY_LOCAL_CODE>
                  </BINARY_INFO>
                </Put>
              </FUSBody>
            </FUSMsg>
        """.trimIndent())

        val result = VersionFetch.parseHistoryInfos(doc)
        assertEquals(1, result.size)

        val info = result[0]
        assertEquals(1, info.index)
        assertEquals(5, info.sequence)
        assertEquals("SM-S936U", info.modelName)
        assertEquals("XAA", info.localCode)
        // 缺失的字段应为 null 或默认值
        assertNull(info.displayName)
        assertEquals("", info.swVersion)
        assertNull(info.displayVersion)
        assertNull(info.directVersion)
        assertNull(info.buyerCode)
        assertNull(info.nature)
        assertNull(info.status)
        assertNull(info.exists)
        assertNull(info.osName)
        assertNull(info.platform)
        assertNull(info.openDate)
        assertNull(info.sharing)
        assertNull(info.category)
        assertNull(info.open)
    }

    @Test
    fun `parseHistoryInfos BINARY_SEQUENCE缺失时默认为0`() {
        val doc = Ksoup.parse("""
            <FUSMsg>
              <FUSBody>
                <Put>
                  <BINARY_INFO>
                    <BINARY_INDEX><Data>1</Data></BINARY_INDEX>
                    <BINARY_MODEL_NAME><Data>SM-S936U</Data></BINARY_MODEL_NAME>
                    <BINARY_SW_VERSION><Data>S936USQUACZF1</Data></BINARY_SW_VERSION>
                    <BINARY_LOCAL_CODE><Data>XAA</Data></BINARY_LOCAL_CODE>
                    <BINARY_OS_NAME><Data>Android 15</Data></BINARY_OS_NAME>
                  </BINARY_INFO>
                </Put>
              </FUSBody>
            </FUSMsg>
        """.trimIndent())

        val result = VersionFetch.parseHistoryInfos(doc)
        assertEquals(0, result[0].sequence)
    }

    @Test
    fun `parseHistoryInfos BINARY_INDEX不是整数时不崩溃`() {
        val doc = Ksoup.parse("""
            <FUSMsg>
              <FUSBody>
                <Put>
                  <BINARY_INFO>
                    <BINARY_INDEX><Data>not-a-number</Data></BINARY_INDEX>
                    <BINARY_SEQUENCE><Data>1</Data></BINARY_SEQUENCE>
                    <BINARY_MODEL_NAME><Data>SM-S936U</Data></BINARY_MODEL_NAME>
                    <BINARY_SW_VERSION><Data>S936USQUACZF1</Data></BINARY_SW_VERSION>
                    <BINARY_LOCAL_CODE><Data>XAA</Data></BINARY_LOCAL_CODE>
                    <BINARY_OS_NAME><Data>Android 15</Data></BINARY_OS_NAME>
                  </BINARY_INFO>
                </Put>
              </FUSBody>
            </FUSMsg>
        """.trimIndent())

        val result = VersionFetch.parseHistoryInfos(doc)
        assertEquals(1, result.size)
        assertNull(result[0].index)
    }

    @Test
    fun `parseHistoryInfos 使用fixture文件验证完整解析`() {
        val fixtureXml = this::class.java.classLoader
            ?.getResource("fixtures/history_response.xml")
            ?.readText()
            ?: throw IllegalStateException("Cannot find fixtures/history_response.xml")

        val doc = Ksoup.parse(fixtureXml)
        val result = VersionFetch.parseHistoryInfos(doc)

        assertEquals(3, result.size)

        // 验证第一个条目
        assertEquals(1, result[0].index)
        assertEquals(1, result[0].sequence)
        assertEquals("SM-S936U", result[0].modelName)
        assertEquals("Galaxy S24+", result[0].displayName)
        assertEquals("S936USQU1AXB5", result[0].swVersion)
        assertEquals("S936USQU1AXB5", result[0].displayVersion)
        assertEquals("XAA", result[0].localCode)
        assertEquals("XAA", result[0].buyerCode)
        assertEquals(1, result[0].nature)
        assertEquals(0, result[0].status)
        assertEquals(1, result[0].exists)
        assertEquals("Android 14", result[0].osName)
        assertEquals("ARM64", result[0].platform)
        assertEquals("2024-01-15", result[0].openDate)
        assertEquals(0, result[0].sharing)
        assertEquals("General", result[0].category)
        assertEquals(1, result[0].open)

        // 验证最后一个条目是最新的
        assertEquals("S936USQUACZF1", result[2].swVersion)
        assertEquals("2026-06-05", result[2].openDate)
    }
}
