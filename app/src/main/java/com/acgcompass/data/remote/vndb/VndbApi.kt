package com.acgcompass.data.remote.vndb

import com.acgcompass.core.network.interceptor.SourceAuth
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Tag

/**
 * VNDB API v2「Kana」接口（Base URL：
 * [com.acgcompass.core.network.NetworkConstants.VNDB_BASE_URL]）。
 *
 * VNDB 是 **POST-JSON 查询型 API**：查询 VN 通过 `POST /vn` 携带 [VndbQueryRequestDto] 体
 * （`filters` + `fields` + 分页）。鉴权可选——每个方法接受一个 [SourceAuth] 的 `@Tag` 参数
 * （调用方传 `SourceAuths.vndb`，即 `TokenHeaderAuth(VNDB, scheme="Token")`），由
 * [com.acgcompass.core.network.interceptor.AuthInterceptor] 据此从 `CredentialStore` 读取可选
 * token 并注入 `Authorization: Token <token>`（无凭据时透传匿名公共查询）。
 *
 * 所有方法返回 [Response]，以便 [VndbRemoteDataSource] 检视 HTTP 状态码并经
 * [com.acgcompass.core.network.HttpErrorMapper] 映射为领域 `AppError`（不依赖异常路径）。
 */
interface VndbApi {

    /** 查询 VN（按 id 精确取详情，或按 search 关键字搜索）：请求体携带 filters/fields/分页。 */
    @POST("vn")
    suspend fun queryVn(@Body request: VndbQueryRequestDto, @Tag auth: SourceAuth): Response<VndbVnResponseDto>
}
