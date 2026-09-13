package at.websters.tabbyandroid.data.ssh

import com.jcraft.jsch.JSch
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class SshKeyManagerTest {

    @Test fun generateKeyRoundTrip() {
        val g = SshKeyManager.generateKey("test")
        assertTrue(g.privatePem.contains("PRIVATE KEY"))
        assertTrue(g.publicOpenSsh.startsWith("ssh-rsa "))
        // generated key must be loadable again (validates both halves)
        SshKeyManager.validatePem(g.privatePem, null)
        val jsch = JSch()
        jsch.addIdentity("rt", g.privatePem.toByteArray(), null, null)
        val ids = jsch.identityRepository.identities
        assertEquals(1, ids.size)
        assertNotNull(ids[0].name)
    }

    @Test fun garbagePemFailsValidation() {
        try {
            SshKeyManager.validatePem("not a key", null)
            fail("must throw")
        } catch (e: Exception) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test fun looksLikePem() {
        assertTrue(SshKeyManager.looksLikePem("-----BEGIN OPENSSH PRIVATE KEY-----\nx\n-----END OPENSSH PRIVATE KEY-----"))
        assertTrue(!SshKeyManager.looksLikePem("hello"))
    }

    @Test fun generatedKeyToStringHidesPrivatePem() {
        val g = SshKeyManager.generateKey("test")
        assertFalse(g.toString().contains("PRIVATE KEY"))
        assertFalse(g.toString().contains(g.privatePem.lines().getOrNull(1).orEmpty()))
    }
}
