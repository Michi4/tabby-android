package at.websters.tabbyandroid.data.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream

/**
 * SSH private-key helpers (import validation + in-app generation).
 * Key material is only ever handled in memory here; persistence is the
 * caller's job ([at.websters.tabbyandroid.data.local.SecureTokenStorage]).
 */
object SshKeyManager {

    data class GeneratedKey(val privatePem: String, val publicOpenSsh: String)

    /** Generates an RSA-3072 keypair. Returns PEM + `ssh-rsa ...` public line. */
    fun generateKey(comment: String = "tabby-android"): GeneratedKey {
        // NOTE: Ed25519 generation exists in JSch but private-key export throws
        // UnsupportedOperationException there, so RSA-3072 it is (accepted everywhere).
        val jsch = JSch()
        val kp = KeyPair.genKeyPair(jsch, KeyPair.RSA, 3072)
        try {
            val priv = ByteArrayOutputStream().also { kp.writePrivateKey(it) }
                .toString(Charsets.UTF_8.name())
            val pub = ByteArrayOutputStream().also { kp.writePublicKey(it, comment) }
                .toString(Charsets.UTF_8.name()).trim()
            require(priv.contains("PRIVATE KEY")) { "Key generation produced no private key" }
            require(pub.startsWith("ssh-rsa ")) { "Unexpected public key format" }
            return GeneratedKey(priv, pub)
        } finally {
            kp.dispose()
        }
    }

    /** Throws if the PEM can't be parsed (wrong passphrase, corrupt, unsupported). */
    fun validatePem(pem: String, passphrase: String?) {
        val jsch = JSch()
        if (passphrase.isNullOrBlank()) {
            jsch.addIdentity("check", pem.trim().toByteArray(Charsets.UTF_8), null, null)
        } else {
            jsch.addIdentity(
                "check", pem.trim().toByteArray(Charsets.UTF_8),
                null, passphrase.toByteArray(Charsets.UTF_8),
            )
        }
    }

    fun looksLikePem(text: String): Boolean =
        text.contains("PRIVATE KEY") && text.contains("BEGIN") && text.contains("END")
}
