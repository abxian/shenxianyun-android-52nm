package com.github.kr328.clash

import org.junit.Assert.*
import org.junit.Test

class CodeImportPolicyTest {
    @Test fun authoritativeCodeAndExpiryAreDistinct() {
        assertTrue(codeImportFailureMessage("invalid_code").contains("错误或不存在"))
        assertTrue(codeImportFailureMessage("expired").contains("已过期"))
        assertFalse(codeImportFailureMessage("expired").contains("提取码错误"))
    }
    @Test fun networkFailureNeverBlamesTheCode() {
        val message = codeImportFailureMessage(networkFailure = true)
        assertTrue(message.contains("网络连接失败"))
        assertTrue(message.contains("原配置已保留"))
        assertFalse(message.contains("提取码错误"))
    }
    @Test fun limitsAndDisabledCodesKeepTheirActualMeaning() {
        for ((code, text) in listOf("bound_limit" to "绑定设备", "device_limit" to "同时在线设备",
            "traffic_limit" to "流量额度", "code_disabled" to "已停用",
            "device_identity_conflict" to "设备绑定信息")) {
            assertTrue(codeImportFailureMessage(code).contains(text))
            assertFalse(codeImportFailureMessage(code).contains("提取码错误"))
        }
    }
    @Test fun ticketExpiryDoesNotMeanSubscriptionExpiry() {
        assertEquals("ticket_expired", importRejectionReason(410, ""))
        assertTrue(codeImportFailureMessage("ticket_expired").contains("验证凭证"))
        assertFalse(codeImportFailureMessage("ticket_expired").contains("提取码已过期"))
        assertEquals("expired", importRejectionReason(404, "expired"))
    }
    @Test fun ambiguousLegacyRejectionsAndUnexpectedDetailsRemainSafe() {
        assertEquals("unavailable", importRejectionReason(403, ""))
        val unsafe = "Traceback token=secret C:/sensitive.yaml"
        assertFalse(codeImportFailureMessage(unsafe).contains(unsafe))
        assertTrue(codeImportFailureMessage(importRejectionReason(429, "")).contains("服务暂忙"))
        assertTrue(codeImportFailureMessage(importRejectionReason(503, "")).contains("服务暂忙"))
    }
}
