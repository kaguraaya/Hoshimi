# AI 服务商与无剧透保护

AI 只用于无剧透评价雷达、AI 口味匹配、标签细分等可选功能；没有配置时一律回退本地规则，页面照常可用。
请求只发往用户自己配置的服务商，凭据只从 `CredentialStore` 读取，不写日志。代码在 `data/remote/ai/`。

## 1. 各服务商的差异

OpenAI、DeepSeek、OpenRouter 和「自定义 OpenAI 兼容」共用 `OpenAiCompatibleProvider`（`POST {baseUrl}/chat/completions`，
`Authorization: Bearer`），差异用 `OpenAiProviderCapabilities` 声明：

| 服务商 | 默认地址 | `json_schema` 结构化输出 | `thinking` 扩展 |
| --- | --- | --- | --- |
| OpenAI | 官方地址 | 支持 | 不发送 |
| DeepSeek | 官方地址 | 不支持，降级为 `json_object` | 支持；默认发送 `{"type":"disabled"}` 关闭思考，用户在设置里打开时不发送 |
| OpenRouter | 官方地址 | 不支持，降级为 `json_object` | 不发送 |
| 自定义兼容端点 | 用户填写 | 不支持，降级为 `json_object` | 不发送 |
| Gemini | 官方地址（`GeminiProvider`，独立实现） | — | — |

结构化输出降级时，字段约束写在提示词里，收到结果后由 `AiEngine` 自己校验；不合规时用修复提示再请求一次，仍不合规就回退本地规则。

兼容端点之间还有一些不在能力表里的差异，按服务端的 HTTP 400 说明处理：

- 点名拒绝 `response_format`（或 `json_schema` / `json_object`）时去掉它再试；
- 点名拒绝 `max_tokens` 时改用 `max_completion_tokens`（较新的推理模型只认后者）；
- 点名拒绝 `temperature`（有的模型只允许默认值）或 `thinking` 时去掉它再试。

一次请求最多这样改写两次；仍然失败时，错误说明里写明服务端拒绝的是哪个参数、已经自动去掉了哪些。
其它错误（401 / 403、429、5xx、网络失败）不改写参数，直接按错误类型提示。
推理型模型只返回思维链（`reasoning_content`）而 `content` 为空时，用思维链作为回复交给上层校验。

测试：`OpenAiCompatibleEndpointsTest`（模拟标准返回、只有思维链、各类参数被拒绝、服务端错误）、`OpenAiCompatibleProviderThinkingTest`。

## 2. 无剧透保护

两道防线，见 `prompt/SpoilerGuard.kt`：

1. **事前**：每个任务的系统提示词都带剧透保护规则——不泄露关键剧情、角色死亡、真实身份、结局、反转和感情结果，
   只写风格、节奏、题材氛围和适合人群；需要提到转折时只能抽象表述；资料不足时给低置信度，不编造。
2. **事后**：模型返回的 JSON 在解析前逐个字符串字段过滤，命中禁用词（结局、死亡 / 去世 / 被杀、真实身份 / 真凶、
   反转 / 黑化 / 背叛、在一起 / 结婚 / 开始交往等）就替换成「（涉及关键情节，已隐去）」；本地规则版雷达的文本也过一遍。

禁用词只收「明确指向剧情结果」的说法，不收「中后期有转折」「节奏」这类风格描述，避免误伤正常内容。
过滤是兜底，不能保证拦住所有改写和暗示，所以提示词约束仍是主要手段。

回归测试 `SpoilerGuardRegressionTest` 用一组模拟的高剧透句子（死亡、身份揭露、结局、反转、感情结果）检查过滤后不再出现具体表述，
并检查只谈风格和人群的句子不被改动；新增禁用词或调整提示词时先跑这组测试。
