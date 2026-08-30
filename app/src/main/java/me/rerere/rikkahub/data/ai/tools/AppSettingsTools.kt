package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.SystemToolsSetting
import kotlin.uuid.Uuid

/**
 * 系统感知（System Awareness）工具。
 * - get_app_settings：读取橘瓣几乎所有设置（敏感项只感知状态、打码，不明文）。
 * - set_app_settings：修改非敏感设置；安全批准 + API密钥/密码 只读感知、拒绝修改。
 * - 世界书(lorebooks)：隔离，用 lorebook_* 独立工具，本工具不读不改。
 * 规则（宝宝定）：安全批准 + API密码只能感知不能操作；其他都能改。
 */

// 🔵 可修改白名单（Settings 主构造简单字段 type：B=Boolean,S=String,I=Int,U=Uuid）
private val EDITABLE = setOf(
    // 外观
    "dynamicColor", "themeId",
    // 模型
    "chatModelId", "titleModelId", "imageGenerationModelId", "translateModeId",
    "suggestionModelId", "ocrModelId", "compressModelId",
    // 提示词
    "titlePrompt", "translatePrompt", "suggestionPrompt", "ocrPrompt", "compressPrompt",
    // 功能开关
    "enableWebSearch", "keepAliveEnabled", "developerMode",
    // Web 服务器（不含密码）
    "webServerEnabled", "webServerPort", "webServerLocalhostOnly", "webServerJwtEnabled",
    // 语音/搜索
    "selectedTTSProviderId", "selectedASRProviderId", "searchServiceSelected", "translateThinkingBudget",
)

// 🔴 只读感知（能读状态但不能改）：安全批准 + 各类 API 密钥/密码/云存储/世界书
private val READONLY = setOf(
    // 安全批准（AI 不可自行关闭安全审计）
    "forceConfirmToolCalls", "autoApproveAllTools", "workflowHeadlessBlockSensitive",
    // API 密钥/密码/云存储
    "providers", "webServerAccessPassword", "webDavConfig", "s3Config",
    // 系统工具里的敏感 key（下面的 STS_ keys）
)

// 系统工具敏感 key（感知状态，不明文、不可改）
private val STS_LIST = setOf(
    "notificationAccess", "cameraAccess", "locationAccess", "appUsageAccess",
    "locationExploreEnabled", "locationExploreRadius", "notificationQueryEnabled",
    "appUsageEnabled", "cameraOcrEnabled", "proactiveMessagingEnabled",
    "proactiveMessagingMinInterval", "proactiveMessagingMaxInterval",
    "ocrProvider", "ocrModel", "supabaseEnabled", "supabaseTableName",
    "appSettingsEnabled", "lorebookEnabled",
)
private val STS_SENSITIVE = setOf("amapApiKey", "ocrApiKey", "ocrApiUrl", "supabaseUrl", "supabaseApiKey")

// SystemToolsSetting 里可编辑的布尔/数值 key（含咱们加的 appSettingsEnabled/lorebookEnabled）
private val STS_EDITABLE = STS_LIST  // 可改；敏感 key 单独拒绝

private fun parseUuid(raw: String, fallback: Uuid?): Uuid? = try { Uuid.parse(raw) } catch (e: Exception) { fallback }
private fun prim(v: Any?): JsonPrimitive = when (v) {
    null -> JsonNull
    is Boolean -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    else -> JsonPrimitive(v.toString())
}

fun createGetAppSettingsTool(settingsStore: SettingsStore): Tool = Tool(
    name = "get_app_settings",
    description = "读取橘瓣（App）当前设置。section 可过滤：appearance/display/model/prompt/switch/voice/search/server/tools。敏感项（API配置、Web密码、世界书）只能感知到是否已配置/启用，不返回明文。世界书修改请用 lorebook_* 工具。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("section", buildJsonObject { put("type", "string"); put("description", "可选：appearance/model/prompt/switch/voice/search/server/tools") })
            },
        )
    },
    execute = { args ->
        val section = args?.jsonObject?.get("section")?.jsonPrimitive?.contentOrNull
        val s = settingsStore.settingsFlow.first()
        val out = buildJsonObject {
            if (section == null || section == "appearance") {
                put("dynamic_color", prim(s.dynamicColor))
                put("theme_id", prim(s.themeId))
            }
            if (section == null || section == "model") {
                put("chat_model_id", prim(s.chatModelId.toString()))
                put("title_model_id", prim(s.titleModelId.toString()))
                put("image_model_id", prim(s.imageGenerationModelId.toString()))
                put("translate_model_id", prim(s.translateModeId.toString()))
                put("suggestion_model_id", prim(s.suggestionModelId.toString()))
                put("ocr_model_id", prim(s.ocrModelId.toString()))
                put("compress_model_id", prim(s.compressModelId.toString()))
            }
            if (section == null || section == "prompt") {
                put("title_prompt", prim(s.titlePrompt))
                put("translate_prompt", prim(s.translatePrompt))
                put("suggestion_prompt", prim(s.suggestionPrompt))
                put("ocr_prompt", prim(s.ocrPrompt))
                put("compress_prompt", prim(s.compressPrompt))
            }
            if (section == null || section == "switch") {
                put("enable_web_search", prim(s.enableWebSearch))
                put("keep_alive", prim(s.keepAliveEnabled))
                put("developer_mode", prim(s.developerMode))
                // 安全批准——只感知状态（能读），不打明文值（就是布尔）
                put("force_confirm_tool_calls", prim(s.forceConfirmToolCalls))
                put("auto_approve_all_tools", prim(s.autoApproveAllTools))
                put("workflow_headless_block_sensitive", prim(s.workflowHeadlessBlockSensitive))
            }
            if (section == null || section == "voice") {
                put("selected_tts_provider", prim(s.selectedTTSProviderId?.toString() ?: ""))
                put("selected_asr_provider", prim(s.selectedASRProviderId?.toString() ?: ""))
            }
            if (section == null || section == "search") {
                put("search_service_selected", prim(s.searchServiceSelected))
                put("translate_thinking_budget", prim(s.translateThinkingBudget))
            }
            if (section == null || section == "server") {
                put("web_server_enabled", prim(s.webServerEnabled))
                put("web_server_port", prim(s.webServerPort))
                put("web_server_localhost_only", prim(s.webServerLocalhostOnly))
                put("web_server_jwt_enabled", prim(s.webServerJwtEnabled))
                // Web 密码：只感知是否已设置
                put("web_server_has_password", prim(s.webServerAccessPassword.isNotBlank()))
            }
            if (section == null || section == "tools") {
                val st = s.systemToolsSetting
                // 系统工具功能开关（能改的）
                put("st_app_settings_enabled", prim(st.appSettingsEnabled))
                put("st_lorebook_enabled", prim(st.lorebookEnabled))
                put("st_notification_access", prim(st.notificationAccess))
                put("st_camera_access", prim(st.cameraAccess))
                put("st_location_access", prim(st.locationAccess))
                put("st_app_usage_access", prim(st.appUsageAccess))
                put("st_location_explore", prim(st.locationExploreEnabled))
                put("st_notification_query", prim(st.notificationQueryEnabled))
                put("st_app_usage", prim(st.appUsageEnabled))
                put("st_camera_ocr", prim(st.cameraOcrEnabled))
                put("st_proactive_msg", prim(st.proactiveMessagingEnabled))
                put("st_supabase", prim(st.supabaseEnabled))
                // 系统工具敏感 key：只感知是否已配置，不明文
                put("st_amap_key_set", prim(st.amapApiKey.isNotBlank()))
                put("st_ocr_key_set", prim(st.ocrApiKey.isNotBlank()))
                put("st_supabase_url_set", prim(st.supabaseUrl.isNotBlank()))
            }
        }
        listOf(UIMessagePart.Text(out.toString()))
    },
)

fun createSetAppSettingsTool(settingsStore: SettingsStore): Tool = Tool(
    name = "set_app_settings",
    description = "修改橘瓣（App）设置（能改的字段），一次一个：field + value。可改：dynamicColor/themeId/模型(chat/title/image/translate/suggestion/ocr/compress)ModelId/提示词(title/translate/suggestion/ocr/compress)Prompt/enableWebSearch/keepAliveEnabled/developerMode/webServerEnabled/webServerPort/webServerLocalhostOnly/webServerJwtEnabled/selectedTTSProviderId/selectedASRProviderId/searchServiceSelected/translateThinkingBudget，以及系统工具开关(用 st_ 前缀，如 st_app_settings_enabled/st_lorebook_enabled/st_notification_query 等)。安全批准(forceConfirmToolCalls/autoApproveAllTools/workflowHeadlessBlockSensitive)和API密钥/密码(providers/amapApiKey/ocrApiKey/supabaseUrl等）只读感知，拒绝修改。世界书用 lorebook_* 工具。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("field", buildJsonObject { put("type", "string"); put("description", "设置字段名或 st_ 前缀+系统工具开关名") })
                put("value", buildJsonObject { put("type", "string"); put("description", "新值（布尔 true/false、整数、字符串、UUID）") })
            },
            required = listOf("field"),
        )
    },
    execute = { args ->
        val field = args?.jsonObject?.get("field")?.jsonPrimitive?.contentOrNull ?: return@Tool badArgs("field is required")
        val raw = args?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull ?: ""
        // 安全批准 + API密钥（只读感知）→ 拒绝
        if (field in READONLY || field in STS_SENSITIVE) {
            return@Tool deny("只读感知项，$field 不能修改")
        }
        if (field.startsWith("st_")) {
            val stKey = field.removePrefix("st_")
            if (stKey in STS_SENSITIVE) return@Tool deny("系统工具敏感项 $stKey 不能修改")
            if (stKey !in STS_EDITABLE) return@Tool deny("系统工具字段 $stKey 不可改")
            settingsStore.update { it ->
                it.copy(systemToolsSetting = applySts(it.systemToolsSetting, stKey, raw))
            }
            return@Tool okResult(field)
        }
        if (field !in EDITABLE) return@Tool deny("字段 $field 不在可修改白名单")
        settingsStore.update { it -> applyField(it, field, raw) }
        okResult(field)
    },
)

private fun applyField(s: Settings, field: String, raw: String): Settings = when (field) {
    "dynamicColor" -> s.copy(dynamicColor = raw.toBooleanStrictOrNull() ?: s.dynamicColor)
    "themeId" -> s.copy(themeId = raw)
    "chatModelId" -> s.copy(chatModelId = parseUuid(raw, s.chatModelId) ?: s.chatModelId)
    "titleModelId" -> s.copy(titleModelId = parseUuid(raw, s.titleModelId) ?: s.titleModelId)
    "imageGenerationModelId" -> s.copy(imageGenerationModelId = parseUuid(raw, s.imageGenerationModelId) ?: s.imageGenerationModelId)
    "translateModeId" -> s.copy(translateModeId = parseUuid(raw, s.translateModeId) ?: s.translateModeId)
    "suggestionModelId" -> s.copy(suggestionModelId = parseUuid(raw, s.suggestionModelId) ?: s.suggestionModelId)
    "ocrModelId" -> s.copy(ocrModelId = parseUuid(raw, s.ocrModelId) ?: s.ocrModelId)
    "compressModelId" -> s.copy(compressModelId = parseUuid(raw, s.compressModelId) ?: s.compressModelId)
    "titlePrompt" -> s.copy(titlePrompt = raw)
    "translatePrompt" -> s.copy(translatePrompt = raw)
    "suggestionPrompt" -> s.copy(suggestionPrompt = raw)
    "ocrPrompt" -> s.copy(ocrPrompt = raw)
    "compressPrompt" -> s.copy(compressPrompt = raw)
    "enableWebSearch" -> s.copy(enableWebSearch = raw.toBooleanStrictOrNull() ?: s.enableWebSearch)
    "keepAliveEnabled" -> s.copy(keepAliveEnabled = raw.toBooleanStrictOrNull() ?: s.keepAliveEnabled)
    "developerMode" -> s.copy(developerMode = raw.toBooleanStrictOrNull() ?: s.developerMode)
    "webServerEnabled" -> s.copy(webServerEnabled = raw.toBooleanStrictOrNull() ?: s.webServerEnabled)
    "webServerPort" -> s.copy(webServerPort = raw.toIntOrNull() ?: s.webServerPort)
    "webServerLocalhostOnly" -> s.copy(webServerLocalhostOnly = raw.toBooleanStrictOrNull() ?: s.webServerLocalhostOnly)
    "webServerJwtEnabled" -> s.copy(webServerJwtEnabled = raw.toBooleanStrictOrNull() ?: s.webServerJwtEnabled)
    "selectedTTSProviderId" -> s.copy(selectedTTSProviderId = parseUuid(raw, s.selectedTTSProviderId) ?: s.selectedTTSProviderId)
    "selectedASRProviderId" -> s.copy(selectedASRProviderId = parseUuid(raw, s.selectedASRProviderId) ?: s.selectedASRProviderId)
    "searchServiceSelected" -> s.copy(searchServiceSelected = raw.toIntOrNull() ?: s.searchServiceSelected)
    "translateThinkingBudget" -> s.copy(translateThinkingBudget = raw.toIntOrNull() ?: s.translateThinkingBudget)
    else -> s
}

private fun applySts(st: SystemToolsSetting, key: String, raw: String): SystemToolsSetting = when (key) {
    "notificationAccess" -> st.copy(notificationAccess = raw.toBooleanStrictOrNull() ?: st.notificationAccess)
    "cameraAccess" -> st.copy(cameraAccess = raw.toBooleanStrictOrNull() ?: st.cameraAccess)
    "locationAccess" -> st.copy(locationAccess = raw.toBooleanStrictOrNull() ?: st.locationAccess)
    "appUsageAccess" -> st.copy(appUsageAccess = raw.toBooleanStrictOrNull() ?: st.appUsageAccess)
    "locationExploreEnabled" -> st.copy(locationExploreEnabled = raw.toBooleanStrictOrNull() ?: st.locationExploreEnabled)
    "locationExploreRadius" -> st.copy(locationExploreRadius = raw.toIntOrNull() ?: st.locationExploreRadius)
    "notificationQueryEnabled" -> st.copy(notificationQueryEnabled = raw.toBooleanStrictOrNull() ?: st.notificationQueryEnabled)
    "appUsageEnabled" -> st.copy(appUsageEnabled = raw.toBooleanStrictOrNull() ?: st.appUsageEnabled)
    "cameraOcrEnabled" -> st.copy(cameraOcrEnabled = raw.toBooleanStrictOrNull() ?: st.cameraOcrEnabled)
    "proactiveMessagingEnabled" -> st.copy(proactiveMessagingEnabled = raw.toBooleanStrictOrNull() ?: st.proactiveMessagingEnabled)
    "proactiveMessagingMinInterval" -> st.copy(proactiveMessagingMinInterval = raw.toIntOrNull() ?: st.proactiveMessagingMinInterval)
    "proactiveMessagingMaxInterval" -> st.copy(proactiveMessagingMaxInterval = raw.toIntOrNull() ?: st.proactiveMessagingMaxInterval)
    "ocrProvider" -> st.copy(ocrProvider = raw)
    "ocrModel" -> st.copy(ocrModel = raw)
    "supabaseEnabled" -> st.copy(supabaseEnabled = raw.toBooleanStrictOrNull() ?: st.supabaseEnabled)
    "supabaseTableName" -> st.copy(supabaseTableName = raw)
    "appSettingsEnabled" -> st.copy(appSettingsEnabled = raw.toBooleanStrictOrNull() ?: st.appSettingsEnabled)
    "lorebookEnabled" -> st.copy(lorebookEnabled = raw.toBooleanStrictOrNull() ?: st.lorebookEnabled)
    else -> st
}

private fun deny(msg: String): List<UIMessagePart.Text> = listOf(UIMessagePart.Text(buildJsonObject {
    put("error", prim("denied")); put("message", prim(msg))
}.toString()))
private fun badArgs(msg: String): List<UIMessagePart.Text> = listOf(UIMessagePart.Text(buildJsonObject {
    put("error", prim("bad_args")); put("message", prim(msg))
}.toString()))
private fun okResult(field: String): List<UIMessagePart.Text> = listOf(UIMessagePart.Text(buildJsonObject {
    put("ok", prim(true)); put("field", prim(field))
}.toString()))
