package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import kotlin.uuid.Uuid

/** 列出所有世界书（Lorebook）——AI 直接读写设置，无需 UI 自动化 */
fun createLorebookListTool(settingsStore: SettingsStore): Tool = Tool(
    name = "lorebook_list",
    description = "列出所有世界书（Lorebook）：名称、描述、启用状态、条目数",
    parameters = { InputSchema.Obj(buildJsonObject { }) },
    needsApproval = false,
    execute = {
        val books = settingsStore.settingsFlow.first().lorebooks
        listOf(UIMessagePart.Text(buildJsonObject {
            put("total", books.size)
            put("lorebooks", org.json.JSONArray().also { arr ->
                books.forEach { b ->
                    arr.put(org.json.JSONObject().apply {
                        put("id", b.id.toString())
                        put("name", b.name)
                        put("description", b.description)
                        put("enabled", b.enabled)
                        put("entries", b.entries.size)
                    })
                }
            }.toString())
        }.toString()))
    },
)

/** 新建世界书 */
fun createLorebookCreateTool(settingsStore: SettingsStore): Tool = Tool(
    name = "lorebook_create",
    description = "新建世界书（Lorebook），传入名称和描述",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject { put("type", "string"); put("description", "世界书名称") })
                put("description", buildJsonObject { put("type", "string"); put("description", "世界书描述") })
            },
            required = listOf("name"),
        )
    },
    needsApproval = true,
    execute = { json ->
        val obj = json.jsonObject
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@Tool errorJson("missing_name", "name is required")
        val description = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
        val book = me.rerere.rikkahub.data.model.Lorebook(name = name, description = description)
        settingsStore.update { it.copy(lorebooks = it.lorebooks + book) }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("ok", true); put("id", book.id.toString()); put("name", name)
        }.toString()))
    },
)

/** 更新世界书（名称/描述/启用） */
fun createLorebookUpdateTool(settingsStore: SettingsStore): Tool = Tool(
    name = "lorebook_update",
    description = "更新世界书：按 id 修改名称/描述/启用状态",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "string"); put("description", "世界书 id") })
                put("name", buildJsonObject { put("type", "string"); put("description", "新名称（可选）") })
                put("description", buildJsonObject { put("type", "string"); put("description", "新描述（可选）") })
                put("enabled", buildJsonObject { put("type", "boolean"); put("description", "启用状态（可选）") })
            },
            required = listOf("id"),
        )
    },
    needsApproval = true,
    execute = { json ->
        val obj = json.jsonObject
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@Tool errorJson("missing_id", "id is required")
        val settings = settingsStore.settingsFlow.first()
        val book = settings.lorebooks.find { it.id.toString() == id }
            ?: return@Tool errorJson("not_found", "no lorebook with id=$id")
        val updated = book.copy(
            name = obj["name"]?.jsonPrimitive?.contentOrNull ?: book.name,
            description = obj["description"]?.jsonPrimitive?.contentOrNull ?: book.description,
            enabled = obj["enabled"]?.jsonPrimitive?.contentOrNull?.let { it == "true" } ?: book.enabled,
        )
        settingsStore.update { it.copy(lorebooks = it.lorebooks.map { if (it.id.toString() == id) updated else it }) }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("ok", true); put("id", id); put("name", updated.name)
        }.toString()))
    },
)

/** 删除世界书 */
fun createLorebookDeleteTool(settingsStore: SettingsStore): Tool = Tool(
    name = "lorebook_delete",
    description = "删除世界书：按 id 删除",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "string"); put("description", "世界书 id") })
            },
            required = listOf("id"),
        )
    },
    needsApproval = true,
    execute = { json ->
        val id = json.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: return@Tool errorJson("missing_id", "id is required")
        settingsStore.update { it.copy(lorebooks = it.lorebooks.filter { b -> b.id.toString() != id }) }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("ok", true); put("id", id); put("deleted", true)
        }.toString()))
    },
)

private fun errorJson(code: String, detail: String): List<UIMessagePart> =
    listOf(UIMessagePart.Text("{\"error\":\"$code\",\"detail\":\"$detail\"}"))
