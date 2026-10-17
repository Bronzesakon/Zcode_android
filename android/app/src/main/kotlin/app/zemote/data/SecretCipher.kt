package app.zemote.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 凭据加解密失败。
 *
 * 两种情况都会抛它：
 *  - **密钥丢失**：应用被卸载 / 清除数据后 Android Keystore 里的密钥没了，旧密文永远解不开；
 *  - **密文损坏或不是本应用写的**（格式对不上、GCM tag 校验不过）。
 *
 * 调用方（[DeviceStore]）**必须**捕获它并降级成「该设备需要重新配对」，不要让异常冒到界面。
 */
class SecretCipherException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 凭据加解密的抽象。
 *
 * 存在的唯一目的：把「设备远控 URL 里的配对 hash」从明文变成磁盘上的密文。
 * 生产用 [KeystoreSecretCipher]（Android Keystore，AES-256-GCM）；JVM 单测用可逆的假实现，
 * **单测里绝不碰 Keystore**。
 *
 * 实现约定：
 *  - [encrypt] 返回自描述字符串（带格式前缀 + 内嵌 IV），能独立解密，不依赖外部状态；
 *  - [decrypt] 只接受本实现 [encrypt] 出来的值，遇到认不出的输入或密钥不可用时抛
 *    [SecretCipherException]（**不返回空串**——空串会被下游当成一个合法的空 URL）；
 *  - 两者对同一份输入不保证输出稳定（GCM 每次随机 IV），所以密文不能拿来做去重/比较键。
 */
interface SecretCipher {

    /** 加密明文，返回自描述的密文串。失败抛 [SecretCipherException]。 */
    fun encrypt(plain: String): String

    /** 解密 [encrypt] 产出的密文。失败（密钥丢失 / 密文损坏）抛 [SecretCipherException]。 */
    fun decrypt(stored: String): String
}

/**
 * 密文的自描述格式：`enc:v1:<base64(iv || ciphertext)>`。
 *
 * - 前缀 `enc:v1:` 标出「这是本应用写的、格式版本 v1」，将来换算法/换 IV 长度可以直接上 `enc:v2:`，
 *   读的时候按前缀分派，不需要猜；
 * - IV 直接拼在密文前面（GCM 的 IV 固定 12 字节，不加密也不需要保密，但必须随密文一起存）；
 * - base64 去掉 padding，避免 `=` 在 URL / 剪贴板里被吞。
 *
 * 这一层是纯字节搬运，不依赖 Android，所以能在 JVM 单测里直接验。
 */
internal object SecretCipherFormat {

    const val PREFIX = "enc:v1:"

    /** GCM 推荐 IV 长度（96 bit）。 */
    const val IV_LENGTH = 12

    /** GCM tag 长度（bit）。 */
    const val TAG_LENGTH_BITS = 128

    private val encoder: Base64.Encoder = Base64.getEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getDecoder()

    fun pack(iv: ByteArray, ciphertext: ByteArray): String {
        require(iv.size == IV_LENGTH) { "IV 长度必须是 $IV_LENGTH 字节，实际 ${iv.size}" }
        require(ciphertext.isNotEmpty()) { "密文不能为空" }
        return PREFIX + encoder.encodeToString(iv + ciphertext)
    }

    /** 拆出 `(iv, ciphertext)`；格式不对抛 [SecretCipherException]。 */
    fun unpack(stored: String): Pair<ByteArray, ByteArray> {
        if (!stored.startsWith(PREFIX)) {
            throw SecretCipherException("不是本应用写入的密文（缺少 $PREFIX 前缀）")
        }
        val raw = try {
            decoder.decode(stored.substring(PREFIX.length))
        } catch (e: IllegalArgumentException) {
            throw SecretCipherException("密文不是合法的 base64", e)
        }
        if (raw.size <= IV_LENGTH) {
            throw SecretCipherException("密文长度不合法（${raw.size} 字节）")
        }
        return raw.copyOfRange(0, IV_LENGTH) to raw.copyOfRange(IV_LENGTH, raw.size)
    }
}

/**
 * 生产实现：Android Keystore 里的 AES-256-GCM 密钥。
 *
 * - 密钥别名固定（[DEFAULT_KEY_ALIAS]），首次使用时生成，之后一直复用；
 * - `setUserAuthenticationRequired(false)`：不做锁屏认证门禁，后台重连也能解密（远控是长连接，
 *   要求每次解密都弹指纹会把体验做废）；
 * - IV 由 Keystore 随机生成（`setRandomizedEncryptionRequired` 默认开），每次加密都不同；
 * - 密钥**只存在于 Keystore**，不会随应用数据备份走，也导不出来。
 *
 * ## 密钥丢失（这是设计上必须接受的代价）
 *
 * 应用卸载 / 清除数据 / 恢复出厂后 Keystore 里的密钥就没了，磁盘上遗留的密文**永久解不开**。
 * 这里的选择是「宁可丢凭据也不能明文落盘」：解不开就解不开，由 [DeviceStore] 把记录降级成
 * 「需要重新配对」，用户重扫一次二维码即可。
 *
 * 注意副作用：密钥丢失后**任何一次** [encrypt] 都会生成一把新密钥，旧密文从此无解。所以
 * [DeviceStore] 的读路径永远是「先解密、再按需加密」，绝不会拿解不开的记录去重写密文。
 *
 * @param keyAlias Keystore 里的密钥别名，单测/多实例隔离时可以换。
 */
class KeystoreSecretCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : SecretCipher {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    override fun encrypt(plain: String): String = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        SecretCipherFormat.pack(cipher.iv, ciphertext)
    } catch (e: Exception) {
        // 加密失败没有安全降级可言（要么加密要么拒绝写盘），直接抛给调用方。
        throw SecretCipherException("凭据加密失败（Keystore 不可用）", e)
    }

    override fun decrypt(stored: String): String {
        val (iv, ciphertext) = SecretCipherFormat.unpack(stored)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, requireKey(), GCMParameterSpec(SecretCipherFormat.TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            throw SecretCipherException("凭据解密失败（密钥可能已丢失，该设备需要重新配对）", e)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private fun requireKey(): SecretKey = (keyStore.getKey(keyAlias, null) as? SecretKey)
        ?: throw SecretCipherException("Keystore 里没有密钥 $keyAlias（应用数据被清除或从未生成）")

    companion object {
        /** Keystore 提供方。 */
        const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** 固定别名：改名等于丢密钥，不要动。 */
        const val DEFAULT_KEY_ALIAS = "zemote.device.credentials.v1"

        const val TRANSFORMATION = "AES/GCM/NoPadding"

        const val KEY_SIZE_BITS = 256
    }
}
