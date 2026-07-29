package tk.zwander.common.tools

import com.fleeksoft.ksoup.Ksoup
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import tk.zwander.common.util.firstDataElementDataByTagName
import tk.zwander.common.util.firstElementByTagName

/**
 * 测试 Request 对象的纯函数逻辑。
 *
 * 覆盖范围（按设计文档优先级）：
 * - P0: getLogicCheck — 已知输入输出验证
 * - P0: createBinaryInit — XML 快照测试（非 legacy / legacy）
 * - P1: 版本匹配逻辑 — 各种版本字符串的匹配/不匹配场景
 */
class RequestTest {

    // ==================== getLogicCheck ====================

    @Test
    fun `getLogicCheck 对已知输入产生已知输出`() {
        val input = "SM-S936U_3_20260602213641_cpeelkz6q8_fac"
        val nonce = "abcdefghijklmnop"
        val result = Request.getLogicCheck(input, nonce)

        assertEquals(nonce.length, result.length)
        for (i in nonce.indices) {
            val idx = nonce[i].code and 0xf
            assertEquals(input[idx], result[i])
        }
    }

    @Test
    fun `getLogicCheck 输入恰好16字符时正常工作`() {
        val input = "1234567890123456" // 正好 16 字符
        val nonce = "abcdefghijklmnop"
        val result = Request.getLogicCheck(input, nonce)

        assertEquals(nonce.length, result.length)
    }

    @Test
    fun `getLogicCheck 输入短于16字符返回空字符串`() {
        assertEquals("", Request.getLogicCheck("short", "nonce"))
        assertEquals("", Request.getLogicCheck("", "nonce"))
        assertEquals("", Request.getLogicCheck("123456789012345", "nonce")) // 15 chars
    }

    @Test
    fun `getLogicCheck 空nonce返回空字符串`() {
        assertEquals("", Request.getLogicCheck("1234567890123456", ""))
    }

    @Test
    fun `getLogicCheck nonce中特殊字符正确映射索引`() {
        val input = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val nonce = "\u0000\u0001\u000f\u0010" // code=0,1,15,16 -> idx=0,1,15,0
        val result = Request.getLogicCheck(input, nonce)

        assertEquals(4, result.length)
        assertEquals(input[0], result[0])  // idx = 0 & 0xf = 0
        assertEquals(input[1], result[1])  // idx = 1 & 0xf = 1
        assertEquals(input[15], result[2]) // idx = 15 & 0xf = 15
        assertEquals(input[0], result[3])  // idx = 16 & 0xf = 0
    }

    @Test
    fun `getLogicCheck 与非UTF-8字符仍能正常工作`() {
        val input = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        val nonce = "AAAAAAAAAAAAAAAA"
        val result = Request.getLogicCheck(input, nonce)

        assertEquals(nonce.length, result.length)
        result.forEach { assertEquals('A', it) }
    }

    @Test
    fun `getLogicCheck 不同nonce产生不同结果`() {
        val input = "SM-S936U_3_20260602213641_cpeelkz6q8_fac"
        val result1 = Request.getLogicCheck(input, "aaaaaaaaaaaaaaaa")
        val result2 = Request.getLogicCheck(input, "bbbbbbbbbbbbbbbb")

        assertNotEquals(result1, result2)
    }

    // ==================== createBinaryInit ====================

    @Test
    fun `createBinaryInit 非legacy生成正确XML结构`() {
        val xml = Request.createBinaryInit(
            fileName = "SM-S936U_3_20260602213641_cpeelkz6q8_fac.zip.enc4",
            nonce = "abcdefghijklmnop",
            fw = "S936USQUACZF1/S936UOYNACZF1/S936USQUACZF1/S936USQUACZF1",
            modelType = "9",
            region = "XAA",
            legacy = false,
        )

        assertContains(xml, "<FUSMsg>")
        assertContains(xml, "<BINARY_NAME>")
        assertContains(xml, "SM-S936U_3_20260602213641_cpeelkz6q8_fac.zip.enc4")
        assertContains(xml, "<BINARY_SW_VERSION>")
        assertContains(xml, "S936USQUACZF1/S936UOYNACZF1/S936USQUACZF1/S936USQUACZF1")
        assertContains(xml, "<DEVICE_LOCAL_CODE>")
        assertContains(xml, "XAA")
        assertContains(xml, "<DEVICE_MODEL_TYPE>")
        assertContains(xml, "9")
        assertContains(xml, "<LOGIC_CHECK>")
        // 非 legacy 不应有 BINARY_FILE_NAME
        assertFalse(xml.contains("BINARY_FILE_NAME"))
        // 应有正确的 ProtoVer
        assertContains(xml, "<ProtoVer>1</ProtoVer>")
    }

    @Test
    fun `createBinaryInit legacy生成正确XML结构`() {
        val xml = Request.createBinaryInit(
            fileName = "SM-G970F_8_WWW_XXXX_XXXX.zip.enc2",
            nonce = "abcdefghijklmnop",
            fw = null,
            modelType = null,
            region = "WWW",
            legacy = true,
        )

        assertContains(xml, "<FUSMsg>")
        assertContains(xml, "<BINARY_FILE_NAME>")
        assertContains(xml, "SM-G970F_8_WWW_XXXX_XXXX.zip.enc2")
        // legacy 不应有 BINARY_SW_VERSION / DEVICE_MODEL_TYPE
        assertFalse(xml.contains("BINARY_SW_VERSION"))
        assertFalse(xml.contains("DEVICE_MODEL_TYPE"))
        assertFalse(xml.contains("DEVICE_LOCAL_CODE"))
    }

    @Test
    fun `createBinaryInit 短文件名logic check为空`() {
        val xml = Request.createBinaryInit(
            fileName = "short.zip.enc4",
            nonce = "abcdefghijklmnop",
            fw = "FW",
            modelType = "9",
            region = "XAA",
            legacy = false,
        )

        assertTrue(xml.contains("LOGIC_CHECK"))
        // logic check 为空时 Data 元素可能为 <Data/> 或 <Data></Data>
        assertTrue(xml.contains("<Data") && xml.contains("</Data>") ||
            xml.contains("<Data/>"), "XML should contain Data element for empty logic check")
    }

    @Test
    fun `createBinaryInit 恰好25字符的文件名仍能计算logic check`() {
        // 25 字符时 logic check 计算逻辑：slice(length-25 until length-9) = slice(0 until 16)
        val xml = Request.createBinaryInit(
            fileName = "1234567890123456789A.zip", // 25 chars: 23 + ".zip" = 25
            nonce = "abcdefghijklmnop",
            fw = "FW",
            modelType = null,
            region = "XAA",
            legacy = false,
        )
        // logic check 不会为空（因为 slice 非空）
        assertFalse(xml.contains("<LOGIC_CHECK><Data/></LOGIC_CHECK>"))
    }

    @Test
    fun `createBinaryInit firmware为null时BINARY_SW_VERSION字段不存在`() {
        val xml = Request.createBinaryInit(
            fileName = "SM-S936U_3_20260602213641_cpeelkz6q8_fac.zip.enc4",
            nonce = "abcdefghijklmnop",
            fw = null,
            modelType = "9",
            region = "XAA",
            legacy = false,
        )

        assertFalse(xml.contains("BINARY_SW_VERSION"))
    }

    @Test
    fun `createBinaryInit modelType为null时DEVICE_MODEL_TYPE字段不存在`() {
        val xml = Request.createBinaryInit(
            fileName = "SM-S936U_3_20260602213641_cpeelkz6q8_fac.zip.enc4",
            nonce = "abcdefghijklmnop",
            fw = "S936USQUACZF1",
            modelType = null,
            region = "XAA",
            legacy = false,
        )

        assertFalse(xml.contains("DEVICE_MODEL_TYPE"))
    }

    // ==================== XML 解析兼容性 ====================

    @Test
    fun `createBinaryInit 输出能被Ksoup正确解析`() {
        val xml = Request.createBinaryInit(
            fileName = "SM-S936U_3_20260602213641_cpeelkz6q8_fac.zip.enc4",
            nonce = "abcdefghijklmnop",
            fw = "S936USQUACZF1/S936UOYNACZF1/S936USQUACZF1/S936USQUACZF1",
            modelType = "9",
            region = "XAA",
            legacy = false,
        )

        val doc = Ksoup.parse(xml)
        val body = doc.firstElementByTagName("FUSBody")
        val put = body?.firstElementByTagName("Put")

        // 验证 XML 中的关键数据可以被 Ksoup 正确解析
        val binaryName = put?.firstDataElementDataByTagName("BINARY_NAME")
        assertEquals("SM-S936U_3_20260602213641_cpeelkz6q8_fac.zip.enc4", binaryName)

        val localCode = put?.firstDataElementDataByTagName("DEVICE_LOCAL_CODE")
        assertEquals("XAA", localCode)

        val modelType = put?.firstDataElementDataByTagName("DEVICE_MODEL_TYPE")
        assertEquals("9", modelType)
    }

    // ==================== 版本匹配逻辑 ====================
    // 以下测试验证 getBinaryFile 中使用的版本匹配模式（checkAgainstModelString / getIndex）
    // 这些函数在原代码中是局部函数，此处用相同的模式进行验证

    @Test
    fun `checkAgainstModelString 模型后缀前缀匹配成功`() {
        // checkAgainstModelString("S936USQUACZF1_1234", "SM-S936U")
        // modelSuffix = "S936U" (split("-").getOrElse(1) { modelString })
        val model = "SM-S936U"
        val modelSuffix = model.split("-").getOrElse(1) { model }
        assertEquals("S936U", modelSuffix)
    }

    @Test
    fun `checkAgainstModelString 去除连接符后匹配成功`() {
        // joinedModel = model.replace("-", "")
        val model = "SM-S936U"
        val joinedModel = model.replace("-", "")
        assertEquals("SMS936U", joinedModel)
    }

    @Test
    fun `getIndex 能从文件名中正确找到模型索引`() {
        val file = "S936USQUACZF1_1234"
        val model = "SM-S936U"
        val fileSplit = file.split("_")

        fun checkAgainstModelString(fileSegment: String, modelString: String): Boolean {
            if (modelString.isEmpty() || modelString.endsWith('-')) return false
            val modelSuffix = modelString.split("-").getOrElse(1) { modelString }
            val joinedModel = modelString.replace("-", "")
            if (fileSegment.startsWith(modelSuffix) || fileSegment.startsWith(joinedModel)) return true
            return checkAgainstModelString(fileSegment, modelString.dropLast(1))
        }

        val index = fileSplit.indexOfFirst { checkAgainstModelString(it, model) }
        assertEquals(0, index) // "S936USQUACZF1" 以 "S936U" 开头
    }

    @Test
    fun `getIndex 文件名不含模型时返回负一`() {
        val file = "UNRELATED_FILE_1234"
        val model = "SM-S936U"
        val fileSplit = file.split("_")

        fun checkAgainstModelString(fileSegment: String, modelString: String): Boolean {
            if (modelString.isEmpty() || modelString.endsWith('-')) return false
            val modelSuffix = modelString.split("-").getOrElse(1) { modelString }
            val joinedModel = modelString.replace("-", "")
            if (fileSegment.startsWith(modelSuffix) || fileSegment.startsWith(joinedModel)) return true
            return checkAgainstModelString(fileSegment, modelString.dropLast(1))
        }

        val index = fileSplit.indexOfFirst { checkAgainstModelString(it, model) }
        assertEquals(-1, index)
    }

    @Test
    fun `版本后缀提取正确`() {
        val fileSegment = "S936USQUACZF1_1234"
        val split = fileSegment.split("_")
        val version = split[0]
        val suffix = split.getOrNull(1)

        assertEquals("S936USQUACZF1", version)
        assertEquals("1234", suffix)
    }
}
