package app.zemote.update

import java.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * 拉取版本清单，与当前安装的 `BuildConfig.VERSION_CODE` 比较。
 *
 * ## 取清单的方式：先 API 拿资产列表，再取清单
 *
 * 另一条路是直接请求
 * `https://github.com/…/releases/download/android-pre/zemote-version.json`，
 * 少一次请求、也不吃 api.github.com 的匿名限流（60 次/小时/IP）。这里仍选 API，理由是：
 *
 * 1. **能区分两种 404**：release 还不存在（还没发过预发布，属正常）与 release 存在但
 *    少了某个资产（CI 出问题）。直链方式下两者都是 404，只能给用户一句含糊的失败。
 * 2. **资产 URL 是数据不是猜测**：清单只给资产名，直链方式得拿"下载基址 + 资产名"拼，
 *    名字一改就成了猜。走 API 则按名字在真实资产列表里查，查不到就是 [UpdateFailure.InvalidAsset]。
 * 3. 与旧 Flutter 实现（走 GitHub API 列 release）行为一致。
 *
 * 代价是匿名限流。检查是用户手动触发的低频操作，可以接受；真被限流会拿到 403，
 * 归类为 [UpdateFailure.Http]，UI 可引导用户去发布页手动下载。
 *
 * 全部依赖注入（[fetcher] / [releaseApiUrl] / [json]），单测用假 fetcher 跑，不碰网络。
 */
class UpdateChecker(
    private val fetcher: HttpFetcher,
    /** 当前安装包的 versionCode，通常是 `BuildConfig.VERSION_CODE`。 */
    private val currentVersionCode: Int,
    private val releaseApiUrl: String = UpdateConfig.RELEASE_API_URL,
    private val json: Json = UpdateConfig.json,
    /** 清单响应体上限，防止被一个超大响应吃掉内存。 */
    private val maxManifestBytes: Long = 256 * 1024,
) {

    suspend fun check(): UpdateCheckResult {
        // 1. 取 release（含资产列表）。
        val release = try {
            val body = fetchText(releaseApiUrl)
                ?: return UpdateCheckResult.NoPublishedRelease("release ${UpdateConfig.ROLLING_TAG} 还不存在")
            json.decodeFromString<ReleaseJson>(body)
        } catch (e: HttpStatusException) {
            return UpdateCheckResult.Failed(UpdateFailure.Http, e.message ?: "HTTP ${e.code}")
        } catch (e: IOException) {
            return UpdateCheckResult.Failed(UpdateFailure.Network, e.describe())
        } catch (e: SerializationException) {
            return UpdateCheckResult.Failed(UpdateFailure.MalformedManifest, "release 响应不是预期的 JSON：${e.message}")
        } catch (e: IllegalArgumentException) {
            return UpdateCheckResult.Failed(UpdateFailure.MalformedManifest, e.describe())
        } catch (e: OversizedResponseException) {
            return UpdateCheckResult.Failed(UpdateFailure.MalformedManifest, e.describe())
        }

        // 2. 从资产列表里找清单。找不到通常意味着 release 是旧构建发的（还没有清单）。
        val manifestAsset = release.assets.firstOrNull { it.name == UpdateConfig.MANIFEST_ASSET }
            ?: return UpdateCheckResult.Failed(
                UpdateFailure.InvalidAsset,
                "release 里没有 ${UpdateConfig.MANIFEST_ASSET}（可能是清单上线前的旧构建）",
            )

        // 3. 取清单本体。
        val manifest = try {
            val body = fetchText(manifestAsset.browserDownloadUrl)
                ?: return UpdateCheckResult.Failed(
                    UpdateFailure.InvalidAsset,
                    "${UpdateConfig.MANIFEST_ASSET} 直链返回 404",
                )
            json.decodeFromString<VersionManifest>(body)
        } catch (e: HttpStatusException) {
            return UpdateCheckResult.Failed(UpdateFailure.Http, e.message ?: "HTTP ${e.code}")
        } catch (e: IOException) {
            return UpdateCheckResult.Failed(UpdateFailure.Network, e.describe())
        } catch (e: SerializationException) {
            // 缺字段、类型不对、JSON 坏了都落这里（MissingFieldException 是它的子类）。
            return UpdateCheckResult.Failed(
                UpdateFailure.MalformedManifest,
                "版本清单解析失败：${e.message}",
            )
        } catch (e: IllegalArgumentException) {
            return UpdateCheckResult.Failed(UpdateFailure.MalformedManifest, e.describe())
        } catch (e: OversizedResponseException) {
            return UpdateCheckResult.Failed(UpdateFailure.MalformedManifest, e.describe())
        }

        // 4. 清单自洽性：abi 必须是我们出的那一份，sizeBytes 必须为正（否则下载校验没意义）。
        if (manifest.abi != UpdateConfig.ABI) {
            return UpdateCheckResult.Failed(
                UpdateFailure.InvalidAsset,
                "清单 abi=${manifest.abi}，本机只支持 ${UpdateConfig.ABI}",
            )
        }
        if (manifest.sizeBytes <= 0) {
            return UpdateCheckResult.Failed(
                UpdateFailure.MalformedManifest,
                "清单 sizeBytes=${manifest.sizeBytes}，无法用于校验",
            )
        }

        // 5. 按清单给的名字在真实资产里找 APK 与 .md5，而不是拼 URL。
        val apkAsset = release.assets.firstOrNull { it.name == manifest.apkAsset }
            ?: return UpdateCheckResult.Failed(
                UpdateFailure.InvalidAsset,
                "release 里没有清单指定的 ${manifest.apkAsset}",
            )
        val md5Asset = release.assets.firstOrNull { it.name == manifest.md5Asset }
            ?: return UpdateCheckResult.Failed(
                UpdateFailure.InvalidAsset,
                "release 里没有清单指定的 ${manifest.md5Asset}",
            )
        if (apkAsset.browserDownloadUrl.isBlank() || md5Asset.browserDownloadUrl.isBlank()) {
            return UpdateCheckResult.Failed(UpdateFailure.InvalidAsset, "资产缺少 browser_download_url")
        }

        // 6. 比较。versionCode 相同视为无更新——同一小时内重复构建会共用同一个 code，
        //    这时内容可能不同，但按"可覆盖安装"的语义没有必要提示用户更新。
        if (manifest.versionCode <= currentVersionCode) {
            return UpdateCheckResult.UpToDate(currentVersionCode)
        }
        return UpdateCheckResult.UpdateAvailable(
            manifest = manifest,
            apkUrl = apkAsset.browserDownloadUrl,
            md5Url = md5Asset.browserDownloadUrl,
            releasePageUrl = release.htmlUrl.ifBlank { UpdateConfig.RELEASE_PAGE_URL },
        )
    }

    /** 读一个小文本资源；404 返回 null，其它非 2xx 抛 [HttpStatusException]。 */
    private suspend fun fetchText(url: String): String? {
        val response = fetcher.fetch(url)
        response.use {
            return when {
                it.statusCode == 404 -> null
                it.statusCode !in 200..299 -> throw HttpStatusException(it.statusCode, url)
                else -> it.body.readLimited(maxManifestBytes).toString(Charsets.UTF_8)
            }
        }
    }
}

/**
 * `releases/tags/<tag>` 响应里我们关心的部分。未知键忽略。
 *
 * GitHub API 返回的是下划线命名的字段，Kotlin 侧用 [SerialName] 显式映射成驼峰，
 * 别把下划线名字直接当属性名用。
 */
@Serializable
internal data class ReleaseJson(
    @SerialName("html_url") val htmlUrl: String = "",
    val assets: List<ReleaseAssetJson> = emptyList(),
)

@Serializable
internal data class ReleaseAssetJson(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
)

private fun Throwable.describe(): String =
    "${this::class.java.simpleName}: ${message ?: "无详情"}"
