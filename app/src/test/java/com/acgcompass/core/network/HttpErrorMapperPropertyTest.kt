package com.acgcompass.core.network

import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppErrorException
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

//
//
// HttpErrorMapper 是纯函数、确定、且对全输入域有定义的映射器：
// - mapStatusCode(Int) 对任意 Int（含负数 / 极值）都返回正确的 AppError 子类型，绝不抛出。
// - mapThrowable(Throwable) 对网络类异常返回 Network、对未知异常兜底返回 Server，绝不抛出。
class HttpErrorMapperPropertyTest : StringSpec({

    val config = PropTestConfig(iterations = 100)

    /**
     * 期望的「状态码 → AppError 子类型」映射，作为测试侧的独立预言（oracle），
     * 不复用被测实现，从而能捕获实现回归。
     */
    fun expectedFor(code: Int): AppError = when (code) {
        401, 403 -> AppError.Unauthorized()
        404 -> AppError.NotFound()
        429 -> AppError.RateLimited()
        in 500..599 -> AppError.Server()
        in 200..299 -> AppError.FieldMissing()
        else -> AppError.Server()
    }

    "mapStatusCode is total and deterministic over the full Int range " {
        checkAll(config, Arb.int()) { code ->
            // 绝不抛出：直接调用即视为通过；类型断言进一步校验正确性。
            val actual = HttpErrorMapper.mapStatusCode(code)
            val expected = expectedFor(code)

            // 子类型必须与预言一致（用类名比较以忽略具体 data class 字段默认值差异）。
            actual::class shouldBe expected::class

            // 确定性：同一输入重复调用产出同类型。
            HttpErrorMapper.mapStatusCode(code)::class shouldBe actual::class

            // 对关键码做更强的具体断言。
            when (code) {
                401, 403 -> actual.shouldBeInstanceOf<AppError.Unauthorized>()
                404 -> actual.shouldBeInstanceOf<AppError.NotFound>()
                429 -> actual.shouldBeInstanceOf<AppError.RateLimited>()
                in 500..599 -> actual.shouldBeInstanceOf<AppError.Server>()
                in 200..299 -> actual.shouldBeInstanceOf<AppError.FieldMissing>()
                else -> actual.shouldBeInstanceOf<AppError.Server>()
            }
        }
    }

    /**
     * 代表性 Throwable 生成器，覆盖网络类异常、通用未知异常，以及已包装的 AppErrorException。
     * 每个样本带上「期望的 AppError 子类型」，作为独立预言。
     */
    val throwableArb: Arb<Pair<Throwable, kotlin.reflect.KClass<out AppError>>> = arbitrary { rs ->
        val candidates: List<Pair<Throwable, kotlin.reflect.KClass<out AppError>>> = listOf(
            SocketTimeoutException("timeout") to AppError.Network::class,
            UnknownHostException("no host") to AppError.Network::class,
            IOException("io") to AppError.Network::class,
            RuntimeException("boom") to AppError.Server::class,
            IllegalStateException("state") to AppError.Server::class,
            AppErrorException(AppError.RateLimited()) to AppError.RateLimited::class,
            AppErrorException(AppError.Unauthorized()) to AppError.Unauthorized::class,
        )
        candidates[rs.random.nextInt(candidates.size)]
    }

    "mapThrowable maps network-class to Network and unknown to Server, never throwing " {
        checkAll(config, throwableArb) { (throwable, expectedType) ->
            val actual = HttpErrorMapper.mapThrowable(throwable)
            actual::class shouldBe expectedType

            // 确定性：重复调用产出同类型。
            HttpErrorMapper.mapThrowable(throwable)::class shouldBe actual::class
        }
    }

    "mapEmptyOrMissing yields FieldMissing only when empty/missing " {
        checkAll(config, Arb.boolean()) { isEmptyOrMissing ->
            val actual = HttpErrorMapper.mapEmptyOrMissing(isEmptyOrMissing)
            if (isEmptyOrMissing) {
                actual.shouldBeInstanceOf<AppError.FieldMissing>()
            } else {
                actual shouldBe null
            }
        }
    }
})
