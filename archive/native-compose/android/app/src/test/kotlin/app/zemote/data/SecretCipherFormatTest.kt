package app.zemote.data

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 密文格式（`enc:v1:<base64(iv||ciphertext)>`）的单测。
 *
 * Keystore 本身在 JVM 单测里跑不了（`AndroidKeyStore` 是空壳），所以这里只验**纯字节那一层**：
 * IV 有没有随密文一起存、前缀是不是自描述、认不出的输入是不是抛错而不是静默返回空串。
 */
class SecretCipherFormatTest {

    private val iv = ByteArray(SecretCipherFormat.IV_LENGTH) { it.toByte() }
    private val ciphertext = byteArrayOf(0x01, 0x02, 0x03, 0x04)

    @Test
    fun packUnpack_roundTripsIvAndCiphertext() {
        val packed = SecretCipherFormat.pack(iv, ciphertext)

        assertTrue("必须是自描述格式：$packed", packed.startsWith(SecretCipherFormat.PREFIX))
        assertTrue("base64 里不能带 padding", !packed.contains('='))
        val (ivOut, ctOut) = SecretCipherFormat.unpack(packed)
        assertArrayEquals(iv, ivOut)
        assertArrayEquals(ciphertext, ctOut)
    }

    @Test
    fun pack_rejectsWrongIvLength() {
        assertThrows(IllegalArgumentException::class.java) {
            SecretCipherFormat.pack(ByteArray(8), ciphertext)
        }
    }

    @Test
    fun unpack_rejectsForeignOrCorruptInput() {
        // 明文（旧数据 / 别的东西）—— 必须抛错，不能返回空串或原值
        assertThrows(SecretCipherException::class.java) {
            SecretCipherFormat.unpack("https://zcode.z.ai/remote/v4?sid=s&hash=h")
        }
        // 前缀对但 base64 坏了
        assertThrows(SecretCipherException::class.java) {
            SecretCipherFormat.unpack(SecretCipherFormat.PREFIX + "!!!not-base64!!!")
        }
        // 只有 IV、没有密文
        assertThrows(SecretCipherException::class.java) {
            SecretCipherFormat.unpack(
                SecretCipherFormat.PREFIX + Base64.getEncoder().encodeToString(iv)
            )
        }
    }

    @Test
    fun fakeCipher_roundTripsAndFailsOnDifferentKey() {
        val cipher = FakeSecretCipher("k1")
        val plain = "https://zcode.z.ai/remote/v4?sid=s&hash=SECRET"
        assertEquals(plain, cipher.decrypt(cipher.encrypt(plain)))

        val other = FakeSecretCipher("k2")
        assertThrows(SecretCipherException::class.java) { other.decrypt(cipher.encrypt(plain)) }
    }
}
