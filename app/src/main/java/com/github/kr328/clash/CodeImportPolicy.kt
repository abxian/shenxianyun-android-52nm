package com.github.kr328.clash

/** Only structured service codes enter user-facing messages. Never print remote details. */
internal class ManagedImportRejected(val reason: String) : IllegalStateException()

internal fun importRejectionReason(status: Int, code: String): String = when {
    code.isNotBlank() -> code
    status == 410 -> "ticket_expired"
    status == 429 || status >= 500 -> "busy"
    else -> "unavailable"
}

internal fun codeImportFailureMessage(reason: String? = null, networkFailure: Boolean = false): String =
    when (reason) {
        "invalid_code" -> "提取码错误或不存在，请检查后重新输入"
        "expired" -> "提取码已过期，请续费后重试，或输入其他提取码"
        "code_disabled" -> "提取码已停用，请联系管理员"
        "traffic_limit" -> "流量额度已用尽，请续费或等待额度恢复"
        "device_limit" -> "同时在线设备已达上限，请断开其他设备后重试"
        "bound_limit" -> "绑定设备已达上限，请联系管理员解绑旧设备"
        "device_identity_conflict" -> "设备绑定信息不一致，请联系管理员核对"
        "ticket_expired" -> "验证凭证已失效，请重新点击导入"
        "busy" -> "服务暂忙，请稍后重试；原配置已保留"
        else -> if (networkFailure) "网络连接失败或超时，请检查网络后重试；原配置已保留"
            else "配置未导入，请确认提取码和套餐状态后重试；原配置已保留"
    }
