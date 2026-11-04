package app.zemote.ui.chat

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    private val view = buildJsonObject {
        put(
            "providers",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("providerId", "glm")
                        put("providerName", "GLM")
                        put(
                            "models",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("modelId", "glm-4.6")
                                        put(
                                            "config",
                                            buildJsonObject {
                                                put(
                                                    "optionSpecs",
                                                    buildJsonObject {
                                                        put(
                                                            "reasoningLevel",
                                                            buildJsonObject {
                                                                put(
                                                                    "values",
                                                                    buildJsonArray {
                                                                        add(kotlinx.serialization.json.JsonPrimitive("low"))
                                                                        add(kotlinx.serialization.json.JsonPrimitive("high"))
                                                                    },
                                                                )
                                                            },
                                                        )
                                                    },
                                                )
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
                add(
                    buildJsonObject {
                        put("providerId", "zai")
                        put("providerName", "Z.ai")
                        put(
                            "models",
                            buildJsonArray {
                                add(buildJsonObject { put("modelId", "glm-5") })
                            },
                        )
                    },
                )
            },
        )
    }

    @Test
    fun `按 provider 分组解析模型目录`() {
        val groups = providerGroups(view)
        assertEquals(listOf("glm", "zai"), groups.map { it.providerId })
        assertEquals("GLM", groups[0].providerName)
        assertEquals("glm-4.6", groups[0].models.single().modelId)
        assertEquals(listOf("low", "high"), groups[0].models.single().reasoningLevels)
        assertTrue(groups[1].models.single().reasoningLevels.isEmpty())
    }

    @Test
    fun `思考档位按 provider 和模型查找`() {
        assertEquals(
            listOf("low", "high"),
            modelViewReasoningLevels(view, "glm", "glm-4.6"),
        )
        assertTrue(modelViewReasoningLevels(view, "glm", "missing").isEmpty())
        assertTrue(modelViewReasoningLevels(null, "glm", "glm-4.6").isEmpty())
    }

    @Test
    fun `目录缺失时当前档位为空`() {
        assertTrue(currentModelReasoningLevels(view, null).isEmpty())
        assertTrue(currentModelReasoningLevels(null, buildJsonObject {
            put("provider", "glm")
            put("model", "glm-4.6")
        }).isEmpty())
    }
}
