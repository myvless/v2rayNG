package com.v2ray.ang.service

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URI
import java.security.SecureRandom
import java.util.Random

/**
 * 远程输入的纯逻辑校验（不依赖任何 Android API，可 JVM 单测）。
 *
 * 调用方注意：订阅 URL 还要再过 Utils.isValidUrl / Utils.isValidSubUrl
 * （与 SubEditActivity.saveServer 一致），那部分依赖 android.util.Patterns，
 * 只能在真机/模拟器上跑，不进这里。
 */
object RemoteInputValidation {

    const val SESSION_COOKIE_NAME = "ri_session"

    /** 订阅表单校验失败的原因；null 表示通过。 */
    enum class SubscriptionFormError {
        EMPTY_REMARKS,
        INVALID_URL_SHAPE,
        INTERVAL_TOO_SMALL
    }

    /**
     * 配对码校验：必须是 6 位数字且与电视端当前配对码一致。
     * 配对码本身不存储、不进日志，调用方只传当前值进来比对。
     */
    fun isValidPairingCode(input: String?, expected: String): Boolean {
        if (input == null || input.length != 6) return false
        if (!input.all { it.isDigit() }) return false
        return input == expected
    }

    /** 生成 6 位配对码（100000-999999），每次开启服务时重新生成。 */
    fun generatePairingCode(random: Random = SecureRandom()): String {
        return (100000 + random.nextInt(900000)).toString()
    }

    /**
     * 远端地址是否允许访问：只放行局域网地址与回环地址。
     * NanoHTTPD 传进来的 remoteIpAddress 一定是 IP 字面量，不会触发 DNS 查询。
     */
    fun isAllowedRemote(ip: String?): Boolean {
        if (ip.isNullOrBlank()) return false
        return try {
            val address = InetAddress.getByName(ip)
            address.isSiteLocalAddress || address.isLoopbackAddress
        } catch (_: Exception) {
            false
        }
    }

    /** 枚举本机网卡，取第一个局域网 IPv4；没有可用局域网时返回 null。 */
    fun findLanIpv4(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 订阅表单的纯校验（不含 android.util.Patterns 的部分）。
     * remarks 非空；URL 必须是 http/https 且带 host；开启自动更新时
     * 更新间隔不得小于 AppConfig.SUBSCRIPTION_MIN_INTERVAL_MINUTES（15 分钟）。
     */
    fun validateSubscriptionForm(
        remarks: String?,
        url: String?,
        autoUpdate: Boolean,
        updateIntervalMinutes: Long?,
        minIntervalMinutes: Long
    ): SubscriptionFormError? {
        if (remarks.isNullOrBlank()) return SubscriptionFormError.EMPTY_REMARKS
        if (!isHttpUrlShape(url)) return SubscriptionFormError.INVALID_URL_SHAPE
        if (autoUpdate && (updateIntervalMinutes == null || updateIntervalMinutes < minIntervalMinutes)) {
            return SubscriptionFormError.INTERVAL_TOO_SMALL
        }
        return null
    }

    /** 纯 URL 形态检查：http/https scheme + 非空 host（不做 DNS、不依赖 Android）。 */
    fun isHttpUrlShape(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            val uri = URI(url.trim())
            (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    /** 从 Cookie 请求头里取 ri_session 的值；没有则返回 null。 */
    fun parseSessionToken(cookieHeader: String?): String? {
        if (cookieHeader.isNullOrBlank()) return null
        return cookieHeader.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("$SESSION_COOKIE_NAME=") }
            ?.removePrefix("$SESSION_COOKIE_NAME=")
            ?.takeIf { it.isNotEmpty() }
    }

    /** HTML 转义：所有回显到手机页面的用户输入都必须经过它。 */
    fun escapeHtml(text: String): String {
        val builder = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                '&' -> builder.append("&amp;")
                '<' -> builder.append("&lt;")
                '>' -> builder.append("&gt;")
                '"' -> builder.append("&quot;")
                '\'' -> builder.append("&#39;")
                else -> builder.append(ch)
            }
        }
        return builder.toString()
    }
}
