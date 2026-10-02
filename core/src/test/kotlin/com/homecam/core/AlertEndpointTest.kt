package com.homecam.core

import com.homecam.core.crypto.AlertEndpoint
import com.homecam.core.crypto.AlertEndpoint.Result
import org.junit.Assert.assertEquals
import org.junit.Test

class AlertEndpointTest {

    private fun valid(raw: String) = (AlertEndpoint.check(raw) as Result.Valid).endpoint
    private fun refused(raw: String) = (AlertEndpoint.check(raw) as Result.Refused).reason

    @Test
    fun `ntfy and a self-hosted https server pass, including on a private address`() {
        assertEquals("https://ntfy.sh", valid("https://ntfy.sh"))
        assertEquals("https://ntfy.sh", valid("  https://NTFY.sh/  "))
        assertEquals("https://192.168.0.20:8443", valid("https://192.168.0.20:8443"))
        assertEquals("https://push.home.lan/ntfy", valid("https://push.home.lan/ntfy/"))
        assertEquals("https://100.64.1.2", valid("https://100.64.1.2"))
    }

    @Test
    fun `cleartext is refused wherever it points`() {
        assertEquals("not https", refused("http://ntfy.sh"))
        assertEquals("not https", refused("http://192.168.0.20"))
        assertEquals("not https", refused("ftp://ntfy.sh"))
        assertEquals("not https", refused("ntfy.sh"))
    }

    @Test
    fun `credentials, queries and fragments are refused`() {
        assertEquals("credentials in URL", refused("https://user:pass@ntfy.sh"))
        assertEquals("credentials in URL", refused("https://user@ntfy.sh"))
        assertEquals("query or fragment", refused("https://ntfy.sh/?auth=abc"))
        assertEquals("query or fragment", refused("https://ntfy.sh/#x"))
    }

    @Test
    fun `a URL that hides its host behind the userinfo is read as the credentials it is`() {
        assertEquals("credentials in URL", refused("https://ntfy.sh@169.254.169.254/"))
    }

    @Test
    fun `malformed, empty, oversized and traversing input is refused, never thrown on`() {
        assertEquals("length", refused(""))
        assertEquals("length", refused("https://" + "a".repeat(300)))
        assertEquals("characters", refused("https://ntfy.sh/a b"))
        assertEquals("characters", refused("https://ntfy.sh/\r\nX: y"))
        assertEquals("path", refused("https://ntfy.sh/a/../b"))
        assertEquals("not a URL", refused("https://[bad"))
        assertEquals("no host", refused("https:///path"))
    }

    @Test
    fun `the host is what a person is shown`() {
        assertEquals("ntfy.sh", AlertEndpoint.hostOf("https://ntfy.sh"))
        assertEquals("", AlertEndpoint.hostOf("garbage"))
    }
}
