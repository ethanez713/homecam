package com.homecam.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.homecam.app.crypto.DeviceIdentity
import com.homecam.app.net.PinnedTls
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A real TLS handshake signed by the Keystore identity key. Conscrypt signs a pre-hashed
 * digest through the key handle, which KeyMint refuses unless the key allows DIGEST_NONE —
 * a constraint no JVM test can see.
 */
@RunWith(AndroidJUnit4::class)
class PinnedTlsHandshakeTest {

    private val identity = DeviceIdentity.get()

    @Test
    fun cameraIdentityCompletesAHandshakeWithAViewerThatPinnedIt() {
        var served: ByteArray? = null
        handshake(identity.identity.spki) { served = it.publicKey.encoded }
        assertArrayEquals(identity.identity.spki, served)
    }

    @Test
    fun viewerRejectsACameraThatIsNotThePinnedOne() {
        val stranger = KeyPairGenerator.getInstance("EC").apply { initialize(256) }
            .generateKeyPair().public.encoded
        assertThrows(SSLHandshakeException::class.java) { handshake(stranger) {} }
    }

    @Test
    fun theHandshakeNegotiatesTls13WhereThePlatformHasItAndNeverAnythingOlderThanTls12() {
        val protocol = handshake(identity.identity.spki) {}
        val expected = if (android.os.Build.VERSION.SDK_INT >= 29) "TLSv1.3" else "TLSv1.2"
        org.junit.Assert.assertEquals(expected, protocol)
    }

    private fun handshake(pin: ByteArray, onServed: (X509Certificate) -> Unit): String {
        val loopback = InetAddress.getLoopbackAddress()
        val server = PinnedTls.serverContext(identity).serverSocketFactory
            .createServerSocket(0, 1, loopback) as SSLServerSocket
        val pool = Executors.newSingleThreadExecutor()
        try {
            val accepted = pool.submit {
                (server.accept() as SSLSocket).use { runCatching { it.startHandshake() } }
            }
            val client = PinnedTls.clientContext(pin, onServed).socketFactory
                .createSocket(loopback, server.localPort) as SSLSocket
            val negotiated = client.use { it.startHandshake(); it.session.protocol }
            accepted.get(10, TimeUnit.SECONDS)
            return negotiated
        } finally {
            server.close()
            pool.shutdownNow()
        }
    }
}
