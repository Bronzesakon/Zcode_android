package app.zemote.ui.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** 模型目录里的一个 provider 分组。 */
internal data class ProviderGroup(
    val providerId: String,
    val providerName: String,
    val models: List<ModelOption>,
)

/** 模型目录里的一个模型。 */
internal data class ModelOption(
    val modelId: String,
    val reasoningLevels: List<String>,
)

/**
 * 解析 `model-selection.getView` 的返回：
 * `{revision, providers:[{providerId, providerName, models:[{modelId, config}]}]}`。
 */
internal fun providerGroups(view: JsonObject?): List<ProviderGroup> {
    val providers = view?.get("providers") as? JsonArray ?: return emptyList()
    return providers.mapNotNull { it as? JsonObject }.mapNotNull { provider ->
        val providerId = provider["providerId"].asString().orEmpty()
        if (providerId.isBlank()) return@mapNotNull null
        ProviderGroup(
            providerId = providerId,
            providerName = provider["providerName"].asString() ?: providerId,
            models = (provider["models"] as? JsonArray)
                .orEmpty()
                .mapNotNull { it as? JsonObject }
                .mapNotNull { model ->
                    val modelId = model["modelId"].asString().orEmpty()
                    if (modelId.isBlank()) return@mapNotNull null
                    ModelOption(
                        modelId = modelId,
                        reasoningLevels = reasoningLevelsOf(model),
                    )
                },
        )
    }
}

/** 某个模型可用的思考档位（`config.optionSpecs.reasoningLevel.values`）。 */
internal fun modelViewReasoningLevels(
    view: JsonObject?,
    providerId: String,
    modelId: String,
): List<String> = providerGroups(view)
    .firstOrNull { it.providerId == providerId }
    ?.models
    ?.firstOrNull { it.modelId == modelId }
    ?.reasoningLevels
    .orEmpty()

/** 当前 config 指向的模型的思考档位；目录缺失时返回空。 */
internal fun currentModelReasoningLevels(
    view: JsonObject?,
    config: JsonObject?,
): List<String> {
    val provider = config?.get("provider").asString() ?: return emptyList()
    val model = config?.get("model").asString() ?: return emptyList()
    return modelViewReasoningLevels(view, provider, model)
}

private fun reasoningLevelsOf(model: JsonObject): List<String> {
    val optionSpecs = (model["config"] as? JsonObject)
        ?.get("optionSpecs") as? JsonObject
    val reasoningLevel = optionSpecs?.get("reasoningLevel") as? JsonObject
    return (reasoningLevel?.get("values") as? JsonArray)
        .orEmpty()
        .mapNotNull { it.asString() }
}
