# 发布与兼容性

## 1. 构建

| 项 | 值 |
| --- | --- |
| `compileSdk` / `targetSdk` / `minSdk` | 34 / 34 / 26（Android 8.0 起） |
| 工具链 | AGP 8.5、Kotlin 2.0、JDK 17 字节码（构建用 JDK 21） |
| 正式包 | `./gradlew :app:assembleRelease`；加 `-Phoshimi.minify=true` 开启 R8 与资源压缩（体积约为原来的五分之一） |
| 签名 | 读仓库根目录被 Git 忽略的 `keystore.properties`；没有时正式包构建直接失败，不会生成未签名或用调试证书签名的包 |
| Bangumi 登录 | `bangumi.clientId` 与 `bangumi.oauthProxyBaseUrl` 来自 `local.properties`、Gradle 属性或同名环境变量；配置了令牌代理时，包里不写入 client secret |

## 2. 发版前核对

1. 单元测试、ktlint、detekt 全部通过（CI 在每个 PR 上运行）。
2. 打开 R8 打一次正式包，装到真机上走一遍：首页、详情、搜索、同步、备份导出与导入、Bangumi 登录。
   R8 规则在 `app/proguard-rules.pro`；序列化、Room、Retrofit 依赖库自带的规则已覆盖，新增反射用法时要补规则。
3. `apksigner verify --print-certs` 确认证书与以往版本一致（否则无法覆盖安装）；确认 `BuildConfig` 里的 client secret 为空。
4. 数据库版本变化时，确认有迁移和升级前自动备份（见 [data-sources.md](data-sources.md) §4b）。

## 3. Android 版本兼容（2026-11 核对）

| 方面 | 现状 |
| --- | --- |
| 权限 | 只有联网和网络状态，不申请通知、存储、定位等运行时权限；导出、导入走系统文件选择器 |
| 后台任务 | 全部用 WorkManager，没有前台服务，不受 Android 14 前台服务类型限制 |
| 全面屏 | 已调用 `enableEdgeToEdge()`，页面自己处理系统栏内边距 |
| 返回手势 | 已声明 `enableOnBackInvokedCallback`；预测性返回动画可在设置里开关 |
| 导出组件 | 只有启动页 `exported=true` |

Google Play 自 2025 年 8 月起要求新应用和更新以 Android 15（API 35）为目标版本。本应用目前通过 GitHub Releases 分发，不受此限制；
以后要升到 `targetSdk 35` 时需要重点回归：强制全面屏下各页面的顶部 / 底部内边距、输入法弹出时的布局、预测性返回动画。

## 4. 自动发布（GitHub Actions）

`.github/workflows/release.yml`：合并到 `main` 后，如果 `versionName` 对应的标签 `v<版本>` 还不存在，就打开 R8 打正式包、
用仓库 Secrets 签名，校验证书指纹与以往版本一致、包里没有 client secret、有 Bangumi client id，再从 `CHANGELOG.md`
取出这个版本的段落作为说明，发布 `v<版本>` 的 Release 并附上 `Hoshimi-<版本>.apk`。

- 需要的仓库 Secrets：`HOSHIMI_KEYSTORE_BASE64`（签名密钥文件的 base64）、`HOSHIMI_KEYSTORE_PASSWORD`、`HOSHIMI_KEY_ALIAS`、
  `HOSHIMI_KEY_PASSWORD`、`BANGUMI_CLIENT_ID`、`BANGUMI_OAUTH_PROXY_BASE_URL`。Bangumi 的 client secret 只放在令牌代理上，不放进仓库或 CI。
- 推到 `release-check/**` 分支只构建并校验签名，不发布，用来检查 Secrets 是否填对。
- 手动运行（workflow_dispatch）可以指定提交，用来补发历史版本。
- 只响应 `push` 和手动运行，不在 pull request 事件里读取 Secrets；签名文件在运行结束时删除。

