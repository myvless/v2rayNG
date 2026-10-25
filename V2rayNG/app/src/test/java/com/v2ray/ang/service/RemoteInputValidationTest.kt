package com.v2ray.ang.service

import com.v2ray.ang.service.RemoteInputValidation.SubscriptionFormError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.util.Random

class RemoteInputValidationTest {

    @Test
    fun pairingCode_acceptsMatchingSixDigits() {
        assertTrue(RemoteInputValidation.isValidPairingCode("123456", "123456"))
    }

    @Test
    fun pairingCode_rejectsMismatchShortLongNonDigitAndNull() {
        assertFalse(RemoteInputValidation.isValidPairingCode("123457", "123456"))
        assertFalse(RemoteInputValidation.isValidPairingCode("12345", "123456"))
        assertFalse(RemoteInputValidation.isValidPairingCode("1234567", "123456"))
        assertFalse(RemoteInputValidation.isValidPairingCode("12a456", "123456"))
        assertFalse(RemoteInputValidation.isValidPairingCode(null, "123456"))
        assertFalse(RemoteInputValidation.isValidPairingCode("", "123456"))
    }

    @Test
    fun generatePairingCode_isAlwaysSixDigitsInRange() {
        val fixed = RemoteInputValidation.generatePairingCode(Random(0))
        assertEquals(6, fixed.length)
        assertTrue(fixed.all { it.isDigit() })
        assertTrue(fixed.toInt() in 100000..999999)
        val random = RemoteInputValidation.generatePairingCode()
        assertEquals(6, random.length)
        assertTrue(random.all { it.isDigit() })
    }

    @Test
    fun allowedRemote_acceptsSiteLocalAndLoopbackOnly() {
        assertTrue(RemoteInputValidation.isAllowedRemote("192.168.1.10"))
        assertTrue(RemoteInputValidation.isAllowedRemote("10.0.0.5"))
        assertTrue(RemoteInputValidation.isAllowedRemote("172.16.0.9"))
        assertTrue(RemoteInputValidation.isAllowedRemote("127.0.0.1"))
        assertTrue(RemoteInputValidation.isAllowedRemote("::1"))
        assertFalse(RemoteInputValidation.isAllowedRemote("172.32.0.1"))
        assertFalse(RemoteInputValidation.isAllowedRemote("8.8.8.8"))
        assertFalse(RemoteInputValidation.isAllowedRemote("203.0.113.7"))
        assertFalse(RemoteInputValidation.isAllowedRemote(null))
        assertFalse(RemoteInputValidation.isAllowedRemote(""))
        assertFalse(RemoteInputValidation.isAllowedRemote("not-an-ip"))
    }

    @Test
    fun subscriptionForm_validInputPasses() {
        assertNull(
            RemoteInputValidation.validateSubscriptionForm(
                "My sub", "https://example.com/sub", true, 60, 15
            )
        )
        assertNull(
            RemoteInputValidation.validateSubscriptionForm(
                "My sub", "http://192.168.1.2/sub", false, null, 15
            )
        )
    }

    @Test
    fun subscriptionForm_rejectsEmptyRemarksBadUrlAndSmallInterval() {
        assertEquals(
            SubscriptionFormError.EMPTY_REMARKS,
            RemoteInputValidation.validateSubscriptionForm(
                "  ", "https://example.com/sub", false, null, 15
            )
        )
        assertEquals(
            SubscriptionFormError.INVALID_URL_SHAPE,
            RemoteInputValidation.validateSubscriptionForm(
                "My sub", "ftp://example.com/sub", false, null, 15
            )
        )
        assertEquals(
            SubscriptionFormError.INVALID_URL_SHAPE,
            RemoteInputValidation.validateSubscriptionForm(
                "My sub", "https://", false, null, 15
            )
        )
        assertEquals(
            SubscriptionFormError.INVALID_URL_SHAPE,
            RemoteInputValidation.validateSubscriptionForm(
                "My sub", null, false, null, 15
            )
        )
        assertEquals(
            SubscriptionFormError.INTERVAL_TOO_SMALL,
            RemoteInputValidation.validateSubscriptionForm(
                "My sub", "https://example.com/sub", true, 5, 15
            )
        )
        assertEquals(
            SubscriptionFormError.INTERVAL_TOO_SMALL,
            RemoteInputValidation.validateSubscriptionForm(
                "My sub", "https://example.com/sub", true, null, 15
            )
        )
    }

    @Test
    fun parseSessionToken_extractsCookieValue() {
        assertEquals(
            "tok123",
            RemoteInputValidation.parseSessionToken("a=b; ri_session=tok123; c=d")
        )
        assertEquals(
            "tok123",
            RemoteInputValidation.parseSessionToken("ri_session=tok123")
        )
        assertNull(RemoteInputValidation.parseSessionToken("a=b; c=d"))
        assertNull(RemoteInputValidation.parseSessionToken("ri_session="))
        assertNull(RemoteInputValidation.parseSessionToken(null))
        assertNull(RemoteInputValidation.parseSessionToken(""))
    }

    @Test
    fun escapeHtml_escapesAllSpecialChars() {
        assertEquals(
            "&lt;b&gt;&amp;&quot;&#39;",
            RemoteInputValidation.escapeHtml("<b>&\"'")
        )
        assertEquals("plain", RemoteInputValidation.escapeHtml("plain"))
    }

    @Test
    fun findLanIpv4_returnsNullOrSiteLocalIpv4() {
        // 环境相关：有局域网时返回 site-local IPv4，否则返回 null；两种结果都合法。
        val result = RemoteInputValidation.findLanIpv4()
        if (result != null) {
            val address = InetAddress.getByName(result)
            assertTrue(address is java.net.Inet4Address)
            assertTrue(address.isSiteLocalAddress)
        }
    }
}
