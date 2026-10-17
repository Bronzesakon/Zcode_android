package app.zemote.data

import java.util.Base64

/**
 * 单测用的可逆假实现——**绝不在 JVM 单测里碰 Android Keystore**（那些类在单测里是空壳）。
 *
 * 变换是可逆的（base64 而已，没有任何安全含义），但带上了实例自己的 [keyId]：
 * 换一个 [keyId] 就解不开另一个实例写下的密文，正好用来模拟「Keystore 密钥丢失」。
 */
class FakeSecretCipher(private val keyId: String = "test-key") : SecretCipher {

    override fun encrypt(plain: String): String =
        PREFIX + keyId + ":" + Base64.getEncoder().encodeToString(plain.toByteArray(Charsets.UTF_8))

    override fun decrypt(stored: String): String {
        val prefix = PREFIX + keyId + ":"
        if (!stored.startsWith(prefix)) {
            throw SecretCipherException("FakeSecretCipher 密钥不匹配（模拟 Keystore 密钥丢失）")
        }
        return try {
            String(Base64.getDecoder().decode(stored.substring(prefix.length)), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            throw SecretCipherException("密文不是合法的 base64", e)
        }
    }

    companion object {
        const val PREFIX = "fake:"
    }
}
