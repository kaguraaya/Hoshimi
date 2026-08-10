package com.acgcompass.data.credential

import com.acgcompass.core.common.logging.LogRedactor
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.stringPattern
import io.kotest.property.checkAll

/**
 * 全称量化命题：
 *   *For any* 凭据集合与任意业务数据集，当执行默认备份序列化或日志脱敏时，输出文本中**绝不**包含
 *   任何凭据明文（完整 key/token/secret），且默认备份的 `credentials` 字段为 `null`。
 * 本测试在「脱敏 / 日志重写」层面验证关键不变式：
 *  - Property A（日志脱敏）：任意敏感字符串嵌入日志行后，[LogRedactor.redact] 的输出**绝不**包含完整原始密钥。
 *  - Property B（脱敏导出）：[CredentialMasking.mask] 最多保留末尾 4 字符，**绝不**还原完整明文。
 * 默认备份零凭据由 `BackupSerializerTest` 单独覆盖。
 */
@OptIn(io.kotest.common.ExperimentalKotest::class)
class CredentialIsolationPropertyTest : StringSpec({

    // iterations >= 100，覆盖足够的随机输入空间。
    val config = PropTestConfig(iterations = 200)

    ".A 日志脱敏绝不泄露完整密钥" {
        checkAll(config, Arb.secretBundle(), credentialFieldValues) { bundle, fieldName ->
            // 取出 bundle 中所有「可视为凭据」的敏感字段值（length >= 8 才算可能的凭据）。
            val secrets = bundle.sensitiveValues().filter { it.length >= MIN_CREDENTIAL_LENGTH }

            secrets.forEach { secret ->
                // 将密钥嵌入一条真实风格的日志行（query 参数 / JSON 字段 / Bearer 头 / 裸值）。
                val logLines = listOf(
                    "GET https://api.example.com/v1/data?$fieldName=$secret&page=1",
                    """{"$fieldName":"$secret","status":"ok"}""",
                    "Authorization: Bearer $secret",
                    "request failed token=$secret retrying",
                    secret,
                )
                logLines.forEach { line ->
                    val redacted = LogRedactor.redact(line)
                    // 不变式：脱敏输出绝不包含完整原始密钥。
                    redacted shouldNotContain secret
                }
            }
        }
    }

    ".B 脱敏掩码最多保留末尾4字符且绝不还原完整值" {
        checkAll(config, Arb.secretBundle()) { bundle ->
            val redacted = bundle.toRedacted()
            // 对每个敏感字段：掩码值绝不等于原值，且不包含完整原值（length >= 8 的可信凭据）。
            val pairs = listOf(
                bundle.token to redacted.token,
                bundle.apiKey to redacted.apiKey,
                bundle.clientId to redacted.clientId,
                bundle.clientSecret to redacted.clientSecret,
            )
            pairs.forEach { (raw, masked) ->
                if (raw != null && raw.length >= MIN_CREDENTIAL_LENGTH) {
                    masked shouldNotBe raw
                    requireNotNull(masked) shouldNotContain raw
                    // 最多暴露末尾 4 字符：掩码可见尾部长度不超过 4。
                    val visibleTail = masked.substringAfter("****…", missingDelimiterValue = "")
                    (visibleTail.length <= MAX_VISIBLE_TAIL).shouldBeTrue()
                    // 末尾片段必须是原值的真实后缀，且长度严格小于原值（绝不完整）。
                    if (visibleTail.isNotEmpty()) {
                        raw.endsWith(visibleTail).shouldBeTrue()
                        (visibleTail.length < raw.length).shouldBeTrue()
                    }
                }
            }
        }
    }
}) {
    private companion object {
        const val MIN_CREDENTIAL_LENGTH = 8
        const val MAX_VISIBLE_TAIL = 4
    }
}

/** 可能作为敏感字段名出现在日志中的键名（用于构造真实风格日志行）。 */
private val credentialFieldValues: Arb<String> =
    Arb.choice(
        Arb.constant("api_key"),
        Arb.constant("apiKey"),
        Arb.constant("access_token"),
        Arb.constant("token"),
        Arb.constant("client_secret"),
        Arb.constant("secret"),
        Arb.constant("password"),
    )

/**
 * 生成贴近真实形态的「类密钥」字符串：
 *  - OpenAI 风格 `sk-` 前缀
 *  - JWT 风格三段式
 *  - 长字母数字混合（>=20，触发通用长密钥规则）
 *  - 普通随机短/长串
 */
private val keyLikeArb: Arb<String> =
    Arb.choice(
        Arb.stringPattern("[A-Za-z0-9]{20,40}").map { "sk-$it" },
        Arb.bind(
            Arb.stringPattern("[A-Za-z0-9]{12,20}"),
            Arb.stringPattern("[A-Za-z0-9]{12,24}"),
            Arb.stringPattern("[A-Za-z0-9]{12,20}"),
        ) { h, p, s -> "$h.$p.$s" },
        Arb.stringPattern("[A-Za-z]{4,10}[0-9]{4,10}[A-Za-z0-9]{12,30}"),
        // 通用长密钥（字母+数字混合，≥20 字符，可被通用密钥规则识别）。
        // 注：凭据天然具备足够长度/熵；裸文本中的任意低熵串无字段上下文时无法被识别为密钥，
        // 故此处生成贴近真实凭据形态的值，使「凭据绝不泄露」这一安全性质可被有效检验。
        Arb.stringPattern("[A-Za-z]{2,8}[0-9]{2,8}[A-Za-z0-9]{16,30}"),
    )

/** baseUrl / model 等非敏感字段的生成器。 */
private val baseUrlArb: Arb<String> =
    Arb.stringPattern("https://api\\.[a-z]{3,8}\\.com/v1")
private val modelArb: Arb<String> =
    Arb.choice(Arb.constant("gpt-4o"), Arb.constant("gpt-3.5-turbo"), Arb.constant("claude-3"))

/** 自定义 [Arb]：随机生成 [SecretBundle]，各字段可为 null，敏感字段为类密钥串。 */
private fun Arb.Companion.secretBundle(): Arb<SecretBundle> = Arb.bind(
    keyLikeArb.orNull(0.2),
    keyLikeArb.orNull(0.2),
    keyLikeArb.orNull(0.3),
    keyLikeArb.orNull(0.3),
    baseUrlArb.orNull(0.4),
    modelArb.orNull(0.4),
) { token, apiKey, clientId, clientSecret, baseUrl, model ->
    SecretBundle(
        token = token,
        apiKey = apiKey,
        clientId = clientId,
        clientSecret = clientSecret,
        baseUrl = baseUrl,
        model = model,
    )
}

/** 提取 [SecretBundle] 中的敏感字段值（不含非敏感的 baseUrl / model）。 */
private fun SecretBundle.sensitiveValues(): List<String> =
    listOfNotNull(token, apiKey, clientId, clientSecret).filter { it.isNotBlank() }
