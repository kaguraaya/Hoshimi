<div align="center">

<img src="docs/icon.svg" alt="Hoshimi app icon" width="120" height="120">

# Hoshimi · 多源 ACG 口味罗盘

**跨源聚合 · 口味匹配 · 预计评分 · 无剧透 AI 评价**

[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white)](#运行环境)
[![Language](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)](#技术栈)
[![UI](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)](#技术栈)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

</div>

**Hoshimi**（星見）是一款 Android 上的 ACG（动画 / 视觉小说）发现与口味匹配应用。它以 Bangumi 为主数据源，
同时聚合 AniList、MyAnimeList（经 Tenrai 或官方 API）与 VNDB 的评分，根据你自己的评分历史给出「口味匹配度」和「预计你会打几分」，
并用这两个数字驱动推荐。

> **English** — Hoshimi is an Android app for discovering anime and visual novels. It uses Bangumi as the primary source,
> aggregates ratings from AniList, MyAnimeList (via Tenrai or the official API) and VNDB, and predicts how well an
> unseen title fits your taste from your own rating history. Credentials stay encrypted on the device; there is no
> first-party server.

---

## 目录

- [功能](#功能)
- [口味算法概览](#口味算法概览)
- [运行环境](#运行环境)
- [从源码构建](#从源码构建)
- [配置 Bangumi 与 AI](#配置-bangumi-与-ai)
- [项目结构](#项目结构)
- [开发约定](#开发约定)
- [文档索引](#文档索引)
- [技术栈](#技术栈)
- [隐私与安全](#隐私与安全)
- [致谢](#致谢)
- [许可证](#许可证)

---

## 功能

- **跨源聚合** — 同一作品在 Bangumi / AniList / MAL / VNDB 的条目按标题与媒介类型聚类，详情页并排展示各平台评分；
  「评分差异」页列出各平台分歧较大的作品。后台会自动补齐只有一个平台评分的作品（默认开启，可以在设置里关闭）。
- **口味匹配度与预计评分** — 详情页给出这部作品和你口味的契合度（百分比）以及预计你会打的分数，并列出理由和你评过的相近作品。
  匹配度有三种算法分支（常规 / 保守 / 激进），在「设置 → 推荐与口味」中切换。
- **口味画像** — 汇总你的打分习惯、偏好与回避的题材、与大众的分歧；可选用 AI 把社区标签细分到更精确的维度。
- **今晚看什么** — 按可用时间、心情标签和接受程度选片，给出稳妥 / 探索 / 随机三档推荐。
- **探索队列** — 逐张卡片浏览没接触过的作品，右滑加入待补池、左滑略过，排序会根据滑动反馈调整。
- **待补池与补番优先级** — 综合口味匹配、预计评分和社区口碑给出优先级与理由；加入待补池的作品可以同步为 Bangumi「想看」（可关闭）。
- **无剧透 AI 评价** — 聚合社区短评，输出亮点、雷点、制作与节奏；需要自行配置 AI 服务商。
- **今日补番签** — 首页每天一句「宜 / 忌」；配置 AI 后可以在「设置 → 推荐与口味」打开 AI 生成，结合你的待补池写签（每天一次）。
- **收藏同步与时光机** — 与 Bangumi 双向同步收藏与评分，以较新的一方为准，网页上删除的收藏进回收站；按时间回看自己的评分记录。
- **本地作品库** — 浏览过的作品（标签、评分、封面、放送日期、Staff）长期保存在本地，清理缓存不会删除，用作匹配度参照。
- **备份与导出** — 导出 / 导入完整备份，导出 CSV；默认每周自动备份，数据库结构升级前自动保留一份原始数据库。
- **口味码与口味相性** — 把口味压缩成一段码或二维码发给朋友，对方打开后看契合度、雷达图对比、两人的异同与互相推荐（不含短评和账号信息）。
- **口味排行** — 本地作品库里的动画按你的口味匹配度排序，带预计评分和理由，可按形式、年代、是否完结筛选；作品库可从 Bangumi 排行一键补充。
- **番剧回顾** — 本季 / 年度看完了哪些、看了多久、最爱哪部，生成封面墙或前十榜单分享图。
- **回收站与撤销** — 移出待补池、清空记录先进回收站，30 天内可撤销或恢复，原来的日期和评分保持不变。

## 口味算法概览

口味算法全部是纯 Kotlin 领域逻辑（`domain/taste`、`domain/recommend`），不依赖 Android，可以离线单元测试。详细说明见
[docs/taste-algorithm.md](docs/taste-algorithm.md)，评测方法与结果见 [docs/algorithm-eval.md](docs/algorithm-eval.md)。

1. **预计评分**（`PersonalDivergenceModel`）：`预计评分 = 大众基线 + 个人分歧`。大众基线是社区评分按你的打分尺度换算的结果；
   个人分歧学习「你的评分 − 基线」的残差，由标签岭回归、同系列前作、取向（热门程度 / 年代 / 篇幅）三部分组成，每一部分都可以还原成理由。
   在 Bangumi15M 抽样的 750 位用户上做 5 折盲测，Spearman 0.603、Top10 命中率 0.740（只用社区评分为 0.547 / 0.693）。
2. **相似作品类比**（`TasteAnalogy`）：找出你评过的、标签最相近的 8 部作品，用它们的标准化评分估计你对新作品的态度。
3. **口味匹配度**（`HeadlineFormula`）：把预计评分、类比结果和题材相似度按所选分支的权重合成原始分 z，再用固定刻度映射到 5–97%：
   z ≥ 0 时平滑接近上限，z < 0 时线性下降。三个分支只在权重上不同：
   - **常规**：以类比为主（高低对等），预计评分占一半；
   - **保守**：只看预计评分；
   - **激进**：以预计评分为底，类比偏高时加倍上推、偏低时只轻扣。
4. **推荐排序**：今晚看什么（`TonightRanker`）按预计满意度、当晚意图匹配、时间拟合、社区口碑、新鲜度等加权，再用 MMR 去同质化；
   探索队列（`ExploreRanker`）在满意度上叠加滑动反馈，并留约三成名额给口碑好但你不熟悉的跨界作品。

算法的全部可调参数有统一的模板格式（`hoshimi-taste-algorithm`），见 [docs/algorithm-template.md](docs/algorithm-template.md)。

## 运行环境

| 项目 | 要求 |
| --- | --- |
| 最低系统 | Android 8.0（API 26） |
| 目标系统 | Android 14（API 34） |
| 架构 | 通用（纯 Kotlin / Compose，无 NDK） |

## 从源码构建

**前置要求**：JDK 17 或更高（构建脚本以 Java 17 为目标，JDK 21 也可以）、Android SDK（compileSdk 34、build-tools 34）。
使用仓库内置的 Gradle Wrapper，不需要单独安装 Gradle。

```bash
git clone https://github.com/kaguraaya/Hoshimi.git
cd Hoshimi
echo "sdk.dir=/path/to/android-sdk" > local.properties

./gradlew :app:assembleDebug            # Debug 包：app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:compileDebugKotlin       # 只编译，最快
./gradlew :app:testDebugUnitTest        # 单元测试（含 Robolectric 界面走查与截图测试）
./gradlew :app:ktlintCheck :app:detekt  # 代码风格与静态检查
```

- Windows 用 `.\gradlew.bat`；工程路径含非 ASCII 字符时，`gradle.properties` 里要保留 `android.overridePathCheck=true`。
- 截图测试的输出在 `app/build/outputs/roborazzi/`。

### Release 构建

Release 必须签名。签名配置放在仓库根目录的 `keystore.properties`（已被 `.gitignore` 忽略），密钥文件放在仓库外：

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

缺少有效的签名配置时，Release 任务会直接失败，避免产出无法覆盖升级的安装包。

```bash
./gradlew :app:assembleRelease                         # 不压缩
./gradlew :app:assembleRelease -Phoshimi.minify=true   # 开启 R8 与资源压缩，体积约为五分之一
```

可选参数：

| 参数 | 作用 |
| --- | --- |
| `-Phoshimi.minify=true` | 开启代码与资源压缩。压缩包发布前要在真机上完整回归 |
| `-Phoshimi.applicationIdSuffix=.preview` | 给包名加后缀，可以和正式版同时安装 |
| `-Phoshimi.versionNameSuffix=-preview` | 给版本名加后缀 |

覆盖安装要求包名相同、签名证书相同、versionCode 不低于已安装版本。

## 配置 Bangumi 与 AI

**Bangumi 一键登录**需要构建时注入 OAuth 应用凭据。它们不进入仓库，按以下顺序读取（取第一个非空值）：

1. 根目录 `local.properties`：
   ```properties
   bangumi.clientId=
   bangumi.clientSecret=
   bangumi.oauthProxyBaseUrl=
   ```
2. Gradle 属性：`bangumiClientId`、`bangumiClientSecret`、`bangumiOauthProxyBaseUrl`；
3. 环境变量：`BANGUMI_CLIENT_ID`、`BANGUMI_CLIENT_SECRET`、`BANGUMI_OAUTH_PROXY_BASE_URL`。

- 回调地址固定为 `hoshimi://oauth/bangumi/callback`。
- 配置了 `oauthProxyBaseUrl` 时，令牌的换取和刷新由代理完成，`client_secret` 不会打进安装包；
  没有代理时 `client_secret` 会进入 BuildConfig，纯客户端应用里的密钥可能被逆向提取，发布时应使用专用 OAuth 应用并定期轮换。
- 没有凭据也能构建，一键登录入口保持禁用，用户可以在设置里手动填写 Access Token。

**AI 服务商**在应用内「设置」配置：OpenAI、Google Gemini、DeepSeek、OpenRouter，或任意 OpenAI 兼容接口（填 Base URL、API Key 与模型名）。
**MyAnimeList 官方 API** 需要用户自己的 Client ID；不填时 MAL 评分由 Tenrai 提供。

## 项目结构

```
app/src/main/java/com/acgcompass/
├── app/       # Application、MainActivity、导航与底栏
├── core/      # 设计系统（core/designsystem）、网络（限流、DNS、错误映射）、通用工具
├── domain/    # 纯 Kotlin：模型、仓库接口、用例、口味算法（taste）、推荐排序（recommend）、跨源匹配（matching）、AI 抽象
├── data/      # Room（local）、Retrofit 远端（remote：bangumi / anilist / tenrai / mal / vndb / jikan / ai）、
│              # 仓库实现、DataStore、后台任务（sync）、口味引擎（taste）、备份
└── feature/   # 页面：home / discover / explore / recommender / detail / library / backlog / taste /
               # timemachine / visualnovel / imports / settings / mine 等，每个页面是 Compose Screen + ViewModel + Contract
```

- 数据流：`Remote / Local → Repository → UseCase → ViewModel（StateFlow）→ Compose Screen`。
- 依赖方向由 `DependencyRulesTest` 检查：feature 层不能新增对 `data.local` 与 `data.remote` 的引用（已有的少数例外记录在 `app/config/architecture-baseline.txt`）。
- 数据库是 Room，结构文件导出到 `app/schemas/`；每次升级版本都要写迁移（`data/local/migration/AcgMigrations.kt`），不使用破坏性迁移。
- 后台任务用 WorkManager：作品资料补齐（`WorkFeatureEnrichmentWorker`）、补充其它平台评分（`RatingBackfillWorker`）、
  「想看」同步（`BangumiWishSyncWorker`）、口味画像自动更新（`TasteProfileAutoUpdater`）。

## 开发约定

- 提交说明使用 [Conventional Commits](https://www.conventionalcommits.org/)，例如 `fix(画像): ...`、`feat(口味): ...`。
- 每个版本同时修改 `app/build.gradle.kts` 的 `versionName` / `versionCode`，并在 `CHANGELOG.md` 顶部写 `## x.y.z - YYYY-MM-DD` 段落。
- 提交前运行 `./gradlew :app:testDebugUnitTest :app:ktlintCheck :app:detekt`。
  - ktlint：行宽 120，import 按字母序；
  - detekt：方法长度 60 行、复杂度 15，基线在 `app/config/detekt-baseline.xml`。
- 单元测试方法名使用 ASCII。
- 界面遵循 [docs/design-system.md](docs/design-system.md)；各页面的间距互相独立，改一个页面不要影响其它页面。
- 网络请求遵守 [docs/rate-limits.md](docs/rate-limits.md) 中的限流；后台请求使用 `RequestPriority.BACKGROUND`，给前台让路。
- 改动口味算法前先跑 [docs/algorithm-eval.md](docs/algorithm-eval.md) 中的评测。

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [docs/taste-algorithm.md](docs/taste-algorithm.md) | 口味算法：预计评分、相似作品类比、匹配度分支、推荐排序、代码位置 |
| [docs/taste-code.md](docs/taste-code.md) | 口味码：包含的内容、文本编码格式、口味相性的计算方式和对比页的结论 |
| [docs/algorithm-eval.md](docs/algorithm-eval.md) | 离线评测：Bangumi15M 盲测、个人数据 LOO、合成用户 LOO，以及试过但没有采用的方案 |
| [docs/algorithm-template.md](docs/algorithm-template.md) | 算法模板格式与每个参数的说明（`docs/algorithm-template.json` 由代码导出） |
| [docs/data-sources.md](docs/data-sources.md) | 各数据源的用途、回退与缓存策略 |
| [docs/rate-limits.md](docs/rate-limits.md) | 各数据源的官方速率上限与本应用的限流设置 |
| [docs/design-system.md](docs/design-system.md) | 界面设计规范（表面层级、描边、选中态、尺寸） |
| [CHANGELOG.md](CHANGELOG.md) | 版本记录 |

## 技术栈

- **语言 / UI**：Kotlin 2.0 · Jetpack Compose · Material 3 · Haze（底栏毛玻璃）
- **异步**：Coroutines / Flow
- **依赖注入**：Hilt
- **持久化**：Room · DataStore · EncryptedSharedPreferences
- **网络**：Retrofit / OkHttp · kotlinx.serialization
- **后台**：WorkManager
- **测试**：JUnit · Robolectric · Compose UI Test · Roborazzi
- **构建**：Gradle（Kotlin DSL）· KSP · ktlint · detekt

## 隐私与安全

- 没有自有服务器，不收集用户数据。API Key 与 Token 只保存在设备本地加密存储（`EncryptedSharedPreferences`）。
- AI 请求只发往你配置的服务商；是否把短评发给 AI 可以在「设置 → 隐私」关闭。
- 不要在 Issue / PR 里粘贴任何密钥。签名文件、`keystore.properties`、`local.properties` 都在 `.gitignore` 中，不要提交。

## 致谢

数据与商标归各自平台所有；本项目仅用于个人 / 学习用途，请遵守各平台的使用条款。

- **数据源**：[Bangumi](https://bgm.tv/) · [AniList](https://anilist.co/) · [Tenrai](https://api.tenrai.org/documentation) ·
  [MyAnimeList](https://myanimelist.net/) · [VNDB](https://vndb.org/) · [Jikan](https://jikan.moe/)（只用于连接测试）
- **离线评测数据**：[Bangumi15M](https://www.kaggle.com/datasets/klion23/bangumi15m)（不随应用分发）
- **界面设计参考**：[FlClash](https://github.com/chen08209/FlClash)（GPL-3.0，未包含其源代码）
- **借鉴过设计思路的开源项目**（源码未包含在本仓库，代码为独立实现）：

| 项目 | 借鉴点 |
| --- | --- |
| [xiaoyvyv/bangumi](https://github.com/xiaoyvyv/bangumi) | Bangumi 原生客户端的条目 / 搜索 / 收藏同步页面结构 |
| [animeko (Ani)](https://github.com/open-ani/animeko) | 数据层、Room 缓存与 Repository / UseCase 分层 |
| [czy0729/Bangumi](https://github.com/czy0729/Bangumi) | 时光机、条目详情、排行与每日放送等页面的功能设计 |
| [MZZB Score](https://github.com/kisekinoumi/mzzbscore) | 跨站评分聚合与差异对比 |
| [Kotatsu](https://github.com/KotatsuApp/Kotatsu) | 自适应图标与胶囊搜索栏的设计语言 |

应用内「我的 → 关于」中的开源许可清单由构建时的实际依赖自动生成。

## 许可证

本项目以 [MIT License](LICENSE) 开源。第三方数据与商标归各自平台所有。
