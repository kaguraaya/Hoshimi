# 口味算法

本文说明 Hoshimi 口味算法的结构、公式和代码位置，供修改算法的开发者参考。
参数的当前值以代码为准，全部列在 [algorithm-template.md](algorithm-template.md)（`algorithm-template.json` 由代码导出，单元测试保证两者一致）。
评测方法和结果见 [algorithm-eval.md](algorithm-eval.md)。

算法代码都在 `domain/taste`、`domain/recommend`，是纯 Kotlin，没有 Android 与 IO 依赖；数据读取和缓存在 `data/taste/TasteEngine.kt`。

## 1. 总体流程

```text
收藏与评分 + 本地作品资料（work_features）
        │
        ▼
BuildTasteProfileUseCase ──► AdvancedTasteProfile
   ├─ 十二维画像（标签偏好）
   └─ PersonalDivergenceModel（评分模型）
        │
        ▼
ComputeTasteMatchUseCase（每部候选作品）
   ├─ 预计评分 = 大众基线 + 个人分歧（+ 类比修正）
   ├─ 相似作品类比 TasteAnalogy
   ├─ 题材相似度（画像匹配分）
   └─ 口味匹配度 = HeadlineFormula(所选分支)
        │
        ▼
详情页 / 口味画像页 / 补番优先级 / TonightRanker / ExploreRanker
```

| 输出 | 回答的问题 | 用在哪 |
| --- | --- | --- |
| 预计评分（1–10） | 看完大概会打几分 | 详情页预测块、今晚看什么与探索队列的排序、补番优先级 |
| 口味匹配度（5–97%） | 和用户的口味有多契合 | 详情页主分数、推荐门槛、补番优先级 |
| 题材相似度（0–100） | 标签上像不像用户常看的作品 | 匹配度的一个分量、推荐理由 |

## 2. 输入数据

- **作品资料**：`work_features` 表，主键是 Bangumi 条目 id。每条记录包含带标注人数的标签、社区评分与人数、集数、时长、平台、标题变体、封面、放送日期、Staff / 角色 / 声优。
  - 完整度分三级：`EMBEDDED`（收藏列表内嵌的精简条目，只有前 10 个标签）、`BASIC`（条目详情）、`EXTENDED`（含 Staff 与角色）。
  - 合并规则只升级不降级（`mergeLightweightFeature`）：已有完整资料时，精简数据只覆盖有效的数值字段，不覆盖标签和完整度。
  - 评分模型只用有社区评分的作品；缺社区分时用本地 `ratings` 表里的 Bangumi 评分补上（`TasteEngine.withCommunityFallback`）。
- **个人数据**：Bangumi 收藏（评分、状态、更新时间）、待补池、探索队列的左右滑记录。
- 本地作品库还包括浏览过的榜单、放送表、搜索结果里的作品，由 `WorkFeatureEnrichmentWorker` 在后台逐批补资料。
  它们不参与建模，用作匹配度参照和全量排序（`TasteEngine.rankLocalPool`）。

## 3. 十二维画像

`BuildTasteProfileUseCase` 把每部已评分作品表示成十二个维度（`TasteCategory`）上的标签向量：

| 维度 | 默认权重 | 维度 | 默认权重 |
| --- | --- | --- | --- |
| 题材 topic | 0.24 | 原作来源 source | 0.05 |
| 组合标签 combo | 0.18 | 角色 character | 0.05 |
| 萌点 xp | 0.12 | 声优 cv | 0.04 |
| 载体 device | 0.09 | 年代 time | 0.03 |
| 制作 staff | 0.08 | 社区梗 meme | 0.03 |
| 评论倾向 comment | 0.06 | 社区共识 community | 0.03 |

- 标签先经 `TagClassifier` 清洗、归类，年份、媒介格式、作品自身标题等归为噪声并剔除；AI 细分的结果作为覆盖（`tagOverrides`）。
- 评分做均值中心化（以用户自己的均分为中性点），高频通用标签按 IDF 降权。
- 收藏状态和探索队列的滑动作为隐式兴趣信号（`ImplicitSignal`），以很小的权重进入画像，不进入评分模型（评测中没有增益）。
- 候选作品与画像的加权匹配得到「题材相似度」，同时产出推荐理由。

## 4. 预计评分：个人分歧模型

`PersonalDivergenceModel`：

```text
预计评分 = clip(大众基线 + PERSONAL_GAIN · (标签分歧 + 系列分歧 + 取向分歧), 1, 10)
```

带社区评分的已评分作品少于 `MIN_SAMPLES`（8）部时不建模，这时匹配度退回画像匹配分。

### 4.1 大众基线

```text
baseline(x) = 用户均分 + slope · (社区分(x) − 用户看过作品的平均社区分)
```

`slope` 由用户评分对社区评分做最小二乘，向先验 1.0 收缩（先验强度 15 部，上限 2.5）。它同时给出两个可读特征：
打分宽严 = 用户均分 − 这些作品的社区均分；跟随口碑 = slope。

### 4.2 标签分歧

作品表示成标签向量：去噪、同义归一，权重 `sqrt(标注人数份额) · idf`，每部最多 30 个标签，L2 归一。对残差（评分 − 基线）做核形式的岭回归：

```text
(K + λI) · a = residual        K = X·Xᵀ，λ = 1
w = Σ aᵢ · xᵢ                   还原成每个标签的权重
标签分歧(x) = w · x
```

样本数（最近 300 部，`MAX_SAMPLES`）远小于标签数，所以用核形式求解（一次 Cholesky 分解）。`w` 本身就是解释：
权重 +0.4 表示「带这个标签的作品，用户通常比基线多给 0.4 分」。支持数少于 3 部的标签不展示。

### 4.3 系列分歧

```text
系列分歧(x) = 0.32 · Σ 同系列已评作品的残差 / (n + 0.5)
```

系列键剥掉「第二季 / Season 2 / 剧场版」等标记后归并。

### 4.4 取向分歧

残差对三个属性做岭回归（λ = 8），以 0.33 的系数并入：

| 维度 | 特征 |
| --- | --- |
| 热门程度 | ln(1 + 评分人数) − 8 |
| 年代 | (年份 − 2012) / 8 |
| 篇幅 | ln(1 + 集数) − 2.5 |

### 4.5 归因

每一项偏离都保存为 `DivergenceFactor`（「悬疑 +0.4」「同系列的评分习惯 −0.3」），偏离小于 0.15 的不展示，最多 6 项。
详情页的预测块和口味画像页的「你和大众的分歧」直接使用这些数据。

## 5. 相似作品类比

`PersonalDivergenceModel.analogy` → `TasteAnalogy`：

- 用标签向量的余弦相似度，从全部已评分作品（最多 2000 部，`MAX_ANALOGY_EXAMPLES`）中取最相近的 8 部；
- 按 sim² 加权平均它们的标准化评分，并按相似度总量收缩，得到类比标准分 `z`：

  ```text
  z = Σ simᵢ² · (评分ᵢ − 用户均分) / max(评分标准差, 0.6)  ÷  (Σ simᵢ² + 0.3)
  ```

  相似作品越多、越像，越敢给出偏离平均的结论；没有相近作品时返回空，匹配度里类比项按 0 计；
- 同时输出最高相似度 `topSimilarity`。

**类比修正**（`RatingPrediction.withAnalogy`）：有很像的已评分作品时，预计评分向类比方向修正：

```text
strength = clamp((topSimilarity − 0.4) / (0.7 − 0.4), 0, 1)
shift    = 0.25 · strength · z · ratingStd
```

修正量小于 0.05 分时忽略。修正作为一项归因（「相似作品你的评分」）展示。

## 6. 口味匹配度：三个分支

`HeadlineFormula`（`domain/taste/TasteAlgorithmConfig.kt`）把三个分量合成原始分 z：

```text
ratingZ     = (预计评分 − 用户均分) / max(评分标准差, 0.6)
similarityZ = clamp((题材相似度 − 50) / 20, −2, 2)

z = ratingWeight · ratingZ
  + analogyUpWeight   · max(0, 类比 z)
  + analogyDownWeight · min(0, 类比 z)
  + similarityWeight  · similarityZ
```

再用固定刻度映射成百分比（单调，所以作品之间的排序只由 z 决定）：

```text
z ≥ 0：center + (97 − center) · (1 − e^(−rise · z))     平滑接近上限
z < 0：center + fall · z                                 不低于 5
```

| 分支 | ratingWeight | analogyUp | analogyDown | similarity | center | rise | fall |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 常规 `standard`（默认） | 0.5 | 1 | 1 | 0.25 | 70 | 0.81 | 12 |
| 保守 `conservative` | 1 | 0 | 0 | 0 | 72 | 0.82 | 13 |
| 激进 `aggressive` | 1 | 2 | 0.3 | 0.25 | 72 | 0.85 | 12 |

- **常规**：以类比为主，高低对等；预计评分占一半。
- **保守**：只看预计评分，不因题材相近额外加减分。「保守」指不额外冒险，分数区间不一定更窄。
- **激进**：以预计评分为底，类比偏高时加倍上推、偏低时只轻扣，更容易把冷门但对口味的作品推上来。

分支保存在设置 `taste_match_branch`，`TasteEngine.withMatchBranch` 把所选公式写进画像；切换后 `TasteProfileAutoUpdater` 用缓存立即重算。

**判定措辞**（`TasteVerdict`）：预计评分低于及格线 6.0 时一律说「可能不太合你口味」；否则 ≥ 84% 很可能喜欢，≥ 72% 应该不错，≥ 60% 中规中矩。
没有评分模型时按画像匹配分使用另一组门槛（80 / 65 / 45）。

**不按作品池定刻度**：按本地作品池的分布定刻度时，池里多是口碑好的热门作品，会出现「预计评分高于用户均分、匹配度却低于 65%」的矛盾
（离线评测中 12–14% 的作品）；固定刻度下这一比例为 0–3%。

**已评分作品**：以用户自己的评分为主锚（`TasteEngineAnchorTest`），不走上面的公式。

## 7. 推荐排序

排序逻辑在 `domain/recommend`。

### 7.1 当晚意图匹配（`intent/IntentMatcher`）

用户选的心情标签先映射成意图包（等价 / 近义 / 沾边 / 冲突标签），再按标签在作品里的代表性（标注人数份额与名次，长篇作品的情绪类标签会被稀释）判断作品是不是「这一类」。
多个标签按 soft-AND 处理：算术与几何覆盖、命中数不足与关键意图缺失的惩罚、组合模板、主气质冲突。意图基本不符的作品，今晚分有上限。

### 7.2 今晚看什么（`TonightRanker`）

```text
今晚分 = 0.34·预计满意度 + 0.32·意图契合 + 0.10·时间拟合 + 0.07·社区口碑
       + 0.06·未曝光 + 0.06·新鲜度 + 0.05·补完轻松度
       − 重复推荐惩罚（0.18，半衰期 5 天） − 主气质冲突惩罚（0.18）
```

- 时间拟合由 `SessionFit` 计算：按可用时间判断能看一集、看完一季还是周末连看。
- 三档：稳妥 = 最高分；探索 = MMR（λ = 0.78）与稳妥的题材错开；随机 = 高分段 softmax 抽样。同系列最多出一部。

### 7.3 探索队列（`ExploreRanker`）

- 效用 = 预计满意度 + 滑动反馈修正：按标签做 Beta 平滑的加入率，「暂不」按半次计，修正量不超过 ±12 分。
- MMR 去同质化；约三成名额给社区 ≥ 7.2、评分人数 ≥ 300、画像熟悉度低的跨界作品。
- 每次滑动后重排尚未看到的卡片。

### 7.4 补番优先级

详情页的补番优先级（`DetailContract.computeBacklogPriority`）综合三项：口味匹配度与预计评分、社区共识、补完成本（集数与时长）。
社区共识（`AggregateRatingsUseCase`）取各平台均分在 Bangumi 动画评分分布中的分位，再按评分人数与平台一致性打折。
理由跟着等级写：高只列拉高的因素，低列拉低的因素（有加分项时写成「社区评价较高，但口味匹配一般」），中两边都写。

## 8. 画像的构建与更新

`TasteProfileAutoUpdater`：

- 收藏或评分变化后自动重建，只读本地缓存（网络预算 0），几秒内完成；
- 缺资料的作品由后台每批 40 部补齐（后台请求优先级），每批之后静默重建一次；
- 手动「重新分析画像」会在前台联网补齐；
- 画像连同评分模型一起缓存，输入指纹（收藏与待补池的内容摘要）不变时不重建。

## 9. AI 的角色

本地模型负责「偏多少」，AI 负责「为什么」：本地算法只看标签与评分，读不懂短评里的扣分原因。

- AI 口味匹配：`DetailViewModel.buildDivergenceEvidence` 把预计评分、归因和相近作品作为证据交给 `AiTaskSpecs.tasteMatchPrompt`，AI 结果旁保留内置算法的数值。
- AI 标签细分：`AiTagClassifier` 把本地规则归为笼统「题材」的社区标签细分到更精确的维度，结果作为画像的标签覆盖。
- 是否把短评发给 AI 由「设置 → 隐私」控制。

## 10. 代码位置

| 内容 | 位置 |
| --- | --- |
| 画像构建 | `domain/taste/BuildTasteProfileUseCase.kt`、`TasteRawScorer.kt`、`TagClassifier.kt` |
| 评分模型 | `domain/taste/PersonalDivergenceModel.kt` |
| 相似作品类比与类比修正 | `domain/taste/TasteAnalogy.kt` |
| 单部作品的匹配计算 | `domain/taste/ComputeTasteMatchUseCase.kt`（`withReasons = false` 时只算分） |
| 分支公式与算法模板 | `domain/taste/TasteAlgorithmConfig.kt`、`TasteAlgorithmConfigSource.kt` |
| 全部常量 | `domain/taste/TasteScoringParams.kt` |
| 判定措辞 | `domain/taste/TasteVerdict.kt` |
| 引擎（读库、缓存、分支、全量排序） | `data/taste/TasteEngine.kt` |
| 自动更新 | `data/sync/TasteProfileAutoUpdater.kt` |
| 意图匹配 / 今晚看什么 / 探索队列 | `domain/recommend/intent/`、`TonightRanker.kt`、`ExploreRanker.kt` |
| 详情页预测块 / 口味画像页 | `feature/detail/DetailScreen.kt`、`feature/taste/TasteProfileScreen.kt` |

## 11. 测试

| 测试 | 作用 |
| --- | --- |
| `PersonalDivergenceModelTest` | 评分模型的拟合、预测与归因 |
| `TasteVerdictCoherenceTest` | 匹配度、判定措辞与预计评分互相不矛盾 |
| `TasteAlgorithmConfigTest` | 模板编码、校验，以及 `docs/algorithm-template.json` 与代码一致 |
| `TasteEngineAnchorTest` | 已评分作品以用户评分为锚 |
| `TasteV4GuardTest` | 关键阈值与关系型断言，防止回归 |
| `TasteBangumi15mEvalTest` / `TasteLooValidationTest` / `TasteSyntheticLooTest` | 离线评测，见 [algorithm-eval.md](algorithm-eval.md) |
| `ScreenWalkthroughTest` | 在 JVM 里渲染页面并按操作顺序走查（今晚看什么、探索队列、预测块） |

修改分支参数后要重新导出 `docs/algorithm-template.json`（`TasteAlgorithmConfig.encode(TasteAlgorithmConfig.DEFAULT)`），否则 `TasteAlgorithmConfigTest` 会失败。

## 12. 局限

- 只靠标签，「在社区评价一般的作品里分出用户喜欢的」能力上限约为 AUC 0.72–0.73；换类比写法（近邻数、加权方式、逻辑回归）都提不上去。
  需要评论里的原因这类信息，只有 AI 分析能看到。
- 评测集是「用户已经选择去看的作品」，真实候选池的整体质量更低。
- 隐式兴趣信号的权重、Staff 与声优在评分模型里的作用，都没有公开数据可以验证。
