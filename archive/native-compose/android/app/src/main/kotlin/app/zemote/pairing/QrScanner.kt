package app.zemote.pairing

import android.util.Log

/**
 * 扫码取配对链接。
 *
 * 生产实现是 [CameraQrScanner]（CameraX + ML Kit）；界面层只负责权限与预览视图，
 * 拿到原始文本（或手动粘贴的文本）后交给 [PairingUrl.preflight] 即可。
 */
interface QrScanner {
    /** 返回扫到的原始文本；`null` = 用户取消（或还没扫到就退出了）。 */
    suspend fun scan(): String?
}

/** 扫码能力尚未接入时的占位实现：永远"用户取消"。 */
object UnavailableQrScanner : QrScanner {
    private const val TAG = "QrScanner"

    override suspend fun scan(): String? {
        Log.i(TAG, "扫码未接入：UnavailableQrScanner.scan() 返回 null（视为用户取消）")
        return null
    }
}
