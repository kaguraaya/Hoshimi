<div align="center">

<img src="docs/icon.svg" alt="Hoshimi app icon" width="120" height="120">

# Hoshimi · 多源 ACG 口味罗盘

**跨源聚合 · 口味匹配 · 个性化推荐 · 无剧透 AI 评价**

[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white)](#environment)
[![Language](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)](#tech-stack)
[![UI](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)](#tech-stack)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

</div>

**Hoshimi**（星見）是一款多源 ACG（动画 / 游戏 / 视觉小说）发现与「口味匹配」Android 应用。聚合 Bangumi、AniList、Jikan(MyAnimeList)、VNDB 等数据源，跨源匹配同一作品，并基于用户长期口味提供个性化推荐与无剧透 AI 评价。

> **English** — Hoshimi is a multi-source anime / visual-novel discovery app for Android. It aggregates Bangumi, AniList, Jikan (MyAnimeList) and VNDB, performs cross-source matching, and produces personalized, taste-aware recommendations with spoiler-free AI analysis. All credentials stay encrypted on-device; nothing is uploaded to any first-party server.

---

## 目录

- [功能特性](#功能特性)
- [核心算法：口味匹配](#核心算法口味匹配)
- [快速开始（用户）](#快速开始用户)
- [运行环境](#environment)
- [从源码构建（开发者）](#从源码构建开发者)
- [配置 AI 与数据源](#配置-ai-与数据源)
- [项目架构](#项目架构)
- [技术栈](#tech-stack)
- [隐私与安全](#隐私与安全)
- [参与贡献](#参与贡献)
- [致谢与参考项目](#致谢与参考项目)
- [许可证](#许可证)

---

## 功能特性

- **多源聚合与跨源匹配** — 同一作品在 Bangumi / AniList / MAL / VNDB 的条目自动聚类；按媒介类型（动画 / 漫画 / 游戏）、可信度与评分人数选取代表条目，避免「同名小说 / 视觉小说」抢占动画评分。
- **口味画像** — 从你评价过的作品构建长期正 / 负向偏好，**仅采用作品自身的 Bangumi 社区标签**并过滤年份 / 媒介格式等噪声标签。
- **口味匹配度** — 详情页给出「这部作品与我的口味有多契合」，以长期口味为主、平台评分为辅，可解释，数据不足时显式标注低置信。
- **今晚看什么** — 按当前心情 / 接受程度进行**硬性标签筛选**（排除冲突题材），再结合长期口味与社区口碑排序；无合适候选时引导放宽条件。
- **探索队列** — 卡片式逐部探索「还没接触」的新作品：单击卡片翻面看简介，左滑略过 / 右滑加入待补池，冷却机制避免重复推荐。
- **无剧透 AI 评价雷达 / 评论摘要** — 聚合社区评论，输出亮点 / 雷点 / 制作 / 节奏，全程剧透过滤。
- **榜单与发现** — 公共榜单、跨平台评分差异、题材筛选。
- **收藏与进度同步** — 与 Bangumi 收藏 / 观看状态双向同步。

## 核心算法：口味匹配

「口味匹配度」是 Hoshimi 的核心。它回答一个问题：**这部我还没看的作品，和我的长期口味有多契合？** 与「直接展示社区高分」不同，它以**你个人的评分历史**为主构建画像，社区口碑仅作辅助。整条管线为纯 Kotlin 领域逻辑，无 Android / IO 依赖，可离线单元测试与回归验证。

**1. 十二维口味画像**
从你评价过的作品中，按十二个维度提取正 / 负向偏好：题材、组合标签、XP（萌点）、载体、制作方、评论倾向、角色、原作来源、声优、年代、梗、社区共识。仅采用作品自身的社区标签，并过滤年份 / 媒介格式等噪声。评分经**均值中心化**（以你个人均分为中性点）——「略低于自己平均线」不等于「讨厌」，避免高均分用户的常看题材被误判为负向。高频通用标签按 **IDF 逆频降权**，稀有的、有区分度的标签获得更高话语权。

**2. rawZ 融合与温度校准**
候选作品在各维度上与画像做加权点积，得到原始契合度 `rawZ`；再经**温度化 logistic 校准**映射到 0–100 的匹配分。温度随样本量自适应（样本足够时用保序回归 isotonic，不足时回退 logistic 并设温度地板），保证冷启动不崩、样本充足时分数拉得开。

**3. 评分锚定（已评分作品）**
对你已经打过分的作品，以你的**显式评分**为主锚（如 10 分→稳定 90+、8 分→80+），长期画像仅做小幅修正，避免「小众挚爱与画像整体重合少」时被拉低。

**4. 口味门控社区融合**
纯内容 / 标签信号有天花板——对「品质 / 共识驱动 + 跨题材」型用户尤其明显（例：同一制作组的两部催泪番，你可能一部打 10、一部打 5，纯标签无从区分）。为此引入**口味门控**：当画像对某作**有明确立场**（匹配分远离中性 50）时，几乎完全由口味主导；仅当画像**没什么话说**（分数接近中性）时，才按社区口碑与其**票数置信度**补位。门控公式 `w_c = w_c^max · (1 − |p − 50| / 50)^γ · votesConf`，既补齐了口味无法判断的盲区，又保证强命中 / 强反口味不会被社区分带偏。

**5. 离线验证（LOO）**
用**留一交叉验证**（Leave-One-Out）在真实评分数据上度量预测分与真实评分的 **Spearman 排序相关**（`TasteLooValidationTest`，读本地 `app/build/loo/dataset.json`）；无真实数据的环境另有合成用户 LOO（`TasteSyntheticLooTest`）做方向性防退化。门控融合上线后，真实数据集的 LOO Spearman 从基线 0.29 提升到 **0.54**，高 / 中 / 低分档的预测分呈健康单调分布。所有阈值与关系型断言由守卫测试固化，防止回归。

**6. 匹配度与满意度是两个问题（v4 / v5）**
- **口味匹配度**（显示用）回答「这部像不像你的口味」：画像分为主，门控社区融合只在画像没立场时补位。社区分经 `50 + 45·tanh((score − 6.6)/1.3)` 映射到同一量纲；没有标签的作品向中性收缩并下调置信度（「没有信息」不等于「不喜欢」）；画像样本上限 120，并为中间分作品保留名额。
- **预计满意度**（推荐排序用）回答「看完大概会有多满意」：`z = (1−w)·z_社区 + w·z_画像`，`w` 随评分数从 0.20 升到 0.30。一部口碑极好的陌生题材，匹配度不高，满意度可以很高。
- 权重来自真实数据而非直觉：在公开数据集 Bangumi15M 抽样的 750 位用户上做 5 折盲测，满意度的 Spearman 为 **0.561**、Top10 命中率 **0.708**（只用社区分 0.547 / 0.693，原门控融合 0.465–0.487 / 0.669）。评测方法、脚本与「试过但没采用的方案」见 [docs/algorithm-eval.md](docs/algorithm-eval.md)。

**7. 推荐排序（今晚看什么 / 探索队列）**
排序逻辑为纯 Kotlin 领域对象（`domain/recommend`），可离线单测：
- **当晚意图匹配**（`intent/IntentMatcher`）：用户选的标签先映射成意图包（等价 / 近义 / 沾边 / 冲突标签），再按标签在作品里的**代表性**（标注人数份额 + 名次，长篇作品的情绪类标签稀释）判断是不是「这一类」；多个标签按 soft-AND 处理（算术 + 几何覆盖、命中数不足与关键意图缺失惩罚、组合模板、主气质冲突），意图基本不符的作品今晚分封顶。回归用例用的是 Bangumi 真实标签：选「催泪」不出《航海王》，选「校园 + 恋爱 + 日常 + 搞笑」出《樱花庄》而不出《灵能百分百》。
- **今晚看什么**（`TonightRanker`）：今晚分 = 满意度 0.34 + 意图契合 0.32 + 时间拟合 0.10 + 社区口碑 0.07 + 未曝光 0.06 + 新鲜度 0.06 + 补完轻松度 0.05 − 重复推荐惩罚 − 主气质冲突惩罚。三档分工：稳妥 = 最高分；探索 = MMR（λ=0.78）与稳妥题材错开；随机 = 高分段 softmax 抽样。同系列至多一部。
- **探索队列**（`ExploreRanker`）：效用 = 满意度 + 左右滑反馈修正（标签级 Beta 平滑加入率，「暂不」按半次计，±12 分封顶）；MMR 去同质化；约三成名额给「社区 ≥ 7.2、画像熟悉度低」的跨界候选；每次滑动后重排尚未看到的卡片。

> 设计原则：**可解释、不伪造、不过拟合**。每个匹配分都能追溯到具体维度的正负贡献；数据不足时显式标注低置信而非编造；门控设计确保社区分只在口味无法判断处起作用，避免退化成「社区高分复读机」。

## 快速开始（用户）

1. 在 [Releases](../../releases) 下载最新 `app-release.apk`（或参考下文自行构建 Debug 包）。
2. 在设备上允许「安装未知来源应用」，安装 APK。
3. 首次启动可直接浏览公共榜单；如需个性化功能：
   - **口味画像 / 收藏同步**：在「设置 → Bangumi」登录并同步收藏。
   - **AI 评价 / 口味分析**：在「设置 → AI」填入你自己的 API Key（见 [配置](#配置-ai-与数据源)）。

> 说明：本项目为个人 / 学习用途，所有第三方数据归各平台所有，请遵守对应平台的使用条款。

## <a id="environment"></a>运行环境

| 项目 | 要求 |
| --- | --- |
| 最低系统 | Android 8.0（API 26） |
| 目标系统 | Android 14（API 34） |
| 架构 | 通用（纯 Kotlin / Compose，无 NDK） |

## 从源码构建（开发者）

**前置要求**：JDK 17、Android SDK（compileSdk 34）。无需单独安装 Gradle，使用仓库内置 Wrapper。

```bash
# 克隆
git clone https://github.com/kaguraaya/Hoshimi.git
cd Hoshimi

# 构建 Debug APK
./gradlew assembleDebug          # Windows: .\gradlew.bat assembleDebug

# 仅编译校验（更快）
./gradlew :app:compileDebugKotlin

# 运行单元测试
./gradlew testDebugUnitTest
```

- 产物：`app/build/outputs/apk/debug/app-debug.apk`
- 若工程路径包含非 ASCII 字符（如中文目录），`gradle.properties` 中需保留：`android.overridePathCheck=true`

## 配置 AI 与数据源

应用内「设置」页支持配置：

- **AI 服务商**：OpenAI 兼容 / Gemini / DeepSeek / OpenRouter 等。填写 `baseUrl` + `API Key` + 模型名即可；支持推理模型（Kimi、DeepSeek-R 等）。
- **Bangumi**：用于登录、收藏同步、口味画像。

Bangumi OAuth 应用凭据不会提交到仓库。Debug 构建可在根目录 `local.properties` 配置：

```properties
bangumi.clientId=
bangumi.clientSecret=
bangumi.oauthProxyBaseUrl=
```

Bangumi OAuth 回调地址固定为 `hoshimi://oauth/bangumi/callback`。OAuth 配置仅从未跟踪的本地文件或 CI 安全变量读取；缺少配置时按钮会保持禁用。
私有 OAuth 代理地址为可选构建注入项，只用于换取和刷新令牌；不代理公共 Bangumi API。未配置应用凭据时源码构建仍可完成，一键登录入口保持禁用。
被 Git 忽略的 `keystore.properties`，其 `storeFile` 指向仓库外的正式密钥；缺少有效签名配置时 Release 任务会直接失败，避免误发不可升级的安装包。

CI/发布构建也可使用 `BANGUMI_CLIENT_ID`、`BANGUMI_CLIENT_SECRET` 与 `BANGUMI_OAUTH_PROXY_BASE_URL` 环境变量。纯客户端构建中的 `client_secret` 仍可能被逆向提取，发布前应使用专用 OAuth 应用并定期轮换。

> **安全提示**：所有 API Key 与 Token 仅通过 `EncryptedSharedPreferences` 保存在**设备本地加密存储**中，绝不上传、绝不写入仓库。请勿在 issue / PR 中粘贴你的密钥。

## 项目架构

分层（Clean Architecture 思路）+ 按功能划分模块：

```
app/src/main/java/com/acgcompass/
├── core/         # 通用基础：AppResult/AppError、设计系统(Compose 主题/组件)、UiState
├── domain/       # 领域层（纯 Kotlin）：model、repository 接口、usecase、ai 抽象、matching 跨源匹配
├── data/         # 数据层：Room(local)、Retrofit(remote: bangumi/anilist/jikan/mal/vndb/ai)、
│                 #         repository 实现、sync 同步、mapper 映射
└── feature/      # 表现层：按功能分包（detail / recommender / taste / settings / ranking ...），
                  #         每个功能含 Compose Screen + ViewModel + Contract（MVVM + StateFlow）
```

- **数据流**：`Remote/Local → Repository → UseCase → ViewModel(StateFlow) → Compose Screen`
- **跨源匹配**：`domain/matching` 按规范化标题聚类，按媒介类型 / 可信度 / 评分人数选代表条目。
- **AI 管线**：`AiEngine` 组装提示词 + schema → Provider 调用 → 校验 / 修复 / 低置信兜底 → 剧透过滤；失败时回退本地规则，页面绝不无响应。
- **绝不伪造**：数据缺失统一以「暂无数据」占位，不编造评分 / 评价 / 观看顺序。

## <a id="tech-stack"></a>技术栈

- **语言 / UI**：Kotlin · Jetpack Compose · Material 3
- **异步**：Coroutines / Flow
- **依赖注入**：Hilt
- **持久化**：Room（含 schema 导出与迁移测试）· DataStore
- **网络**：Retrofit / OkHttp · kotlinx.serialization
- **安全**：EncryptedSharedPreferences（凭据本地加密）
- **构建**：Gradle（Kotlin DSL）· KSP · JDK 17

## 隐私与安全

- 不收集用户数据；所有凭据与同步 Token 仅存于本地加密存储。
- AI 请求仅发往你自行配置的服务商。

## 参与贡献

欢迎 Issue 与 PR：

1. Fork 并基于 `main` 创建特性分支：`git checkout -b feat/xxx`
2. 提交信息建议遵循 [Conventional Commits](https://www.conventionalcommits.org/)（如 `fix(rating): ...`）。
3. 提交前请确保 `./gradlew :app:compileDebugKotlin` 与单元测试通过。
4. 发起 PR 并简要描述动机与改动点。

## 致谢与参考项目

数据、商标归各自平台所有；本项目仅用于个人 / 学习用途。

### 数据源

[Bangumi](https://bgm.tv/) · [AniList](https://anilist.co/) · [Jikan](https://jikan.moe/)（非官方 MyAnimeList API）· [MyAnimeList](https://myanimelist.net/) 官方 API · [VNDB](https://vndb.org/)。

### 核心开源技术

Jetpack Compose · Material 3 · Hilt · Room · Retrofit / OkHttp · kotlinx.serialization · Kotlin Coroutines / Flow。

### 学习参考的开源项目

开发过程中研读了以下开源项目并借鉴其设计思路。**这些项目的源码仅在本地作研究用途，未包含在本仓库**（未包含在公开仓库中）；其著作权归各自作者所有，在此致谢。

| 项目 | 类型 | 本项目主要借鉴点 |
| --- | --- | --- |
| [xiaoyvyv/bangumi](https://github.com/xiaoyvyv/bangumi) | Kotlin 安卓原生 Bangumi 客户端 | Bangumi 原生客户端结构、条目 / 搜索 / 收藏同步的页面信息架构 |
| [animeko (Ani)](https://github.com/open-ani/animeko) | Kotlin / Compose Multiplatform 追番客户端 | 数据层 / Room 缓存 / Repository·UseCase 分层、榜单与 Compose 表现层架构 |
| [czy0729/Bangumi](https://github.com/czy0729/Bangumi) | React Native Bangumi 客户端（页面覆盖完整） | 时光机、条目详情、排行 / 索引 / 每日放送等完整页面的功能设计 |
| [MZZB Score](https://github.com/kisekinoumi/mzzbscore) | 多站动画评分聚合工具（Bangumi / MAL / AniList / Filmarks） | 跨源评分聚合、标准化与差异对比的数据流程 |
| [Kotatsu](https://github.com/KotatsuApp/Kotatsu) | 开源 Android 漫画阅读器（Material 3） | 自适应桌面图标随系统明暗切换的实现思路、圆角胶囊搜索栏的设计语言 |

> 借鉴均限于**公开的设计思路与接口用法**，Hoshimi 的代码为独立实现（Kotlin / Jetpack Compose）。若有作者认为引用不当，欢迎通过 Issue 联系修正。

## 许可证

本项目以 [MIT License](LICENSE) 开源，可自由使用、修改与分发，但需保留版权与许可声明。第三方数据 / 商标归各自平台所有。
