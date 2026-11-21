# 口味算法模板格式（`hoshimi-taste-algorithm` / 版本 1）

这份格式描述 Hoshimi 内置口味算法的**全部可调参数**。仓库里的 `docs/algorithm-template.json` 就是内置算法本身导出的模板（单元测试保证它和代码一致）。

> 目前状态：格式、校验和导入接口（`TasteAlgorithmConfigSource.importReplacement`）已经预留；**导入替换还没有开放**，
> 导入时只做解析和校验，结果不生效。开放后，用户可以导出模板、修改后整份上传替换（全量替换，不支持只改一部分）。
> 现在已经由模板驱动的是 `branches`（设置里的常规 / 保守 / 激进）；其余各节记录内置算法的当前值，开放时按同一格式整份替换。

## 1. 顶层字段
| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `format` | 文本 | 固定为 `hoshimi-taste-algorithm` |
| `formatVersion` | 整数 | 模板格式版本，当前 1；不认识的版本会被拒绝 |
| `name` | 文本 | 算法名字（显示用） |
| `algorithmVersion` | 整数 | 算法版本号（画像缓存与回归判定用） |
| `defaultBranch` | 文本 | 默认分支：`standard` / `conservative` / `aggressive` |
| `branches` | 对象 | 三个分支的主分数公式，必须三个都有（见 §2） |
| `verdict` | 对象 | 判定措辞门槛（见 §3） |
| `analogy` | 对象 | 相似作品类比与「有条件地大胆」（见 §4） |
| `divergence` | 对象 | 个人分歧模型（见 §5） |
| `categoryWeights` | 对象 | 十二维画像各维度的融合权重（见 §6） |
| `scoring` | 对象 | 画像、校准、社区融合、今晚看什么等全部常量（见 §7） |

模板必须是完整的 JSON；缺字段或多出不认识的字段都会被拒绝。

## 2. `branches`：口味匹配度公式
App 默认的评分模型（按人权重，见 [taste-algorithm.md](taste-algorithm.md) §4.6）下，匹配度由圈子分、类比、题材相似和口碑组成
（§6，`TasteCircle`、`TasteFit`），分支决定口碑占多少和刻度松紧，下面这张表不参与。这张表只用于旧公式（对照模型，或评分模型没有按人权重时）。

每个分支一套参数：

```
z = ratingWeight·预计评分标准分 + analogyUpWeight·max(0, 类比标准分)
    + analogyDownWeight·min(0, 类比标准分) + similarityWeight·题材相似度标准分
z ≥ 0：匹配度 = center + (cap − center)·(1 − e^(−rise·z))     平滑接近上限
z < 0：匹配度 = center + fall·z                                  不低于 floor
```
- 预计评分标准分 = (预计评分 − 用户均分) / 用户评分标准差（至少 0.6）。
- 类比标准分：用户给题材最接近的几部作品打的分相对用户均分的高低（§4）。
- 题材相似度标准分 = (十二维画像匹配分 − 50) / 20，限制在 ±2。

| 分支 | ratingWeight | analogyUp | analogyDown | similarity | center | rise | fall | 含义 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `standard`（常规） | 0.5 | 1 | 1 | 0.25 | 70 | 0.81 | 12 | 相似作品类比为主，加一半预计评分和少量题材相似度 |
| `conservative`（保守） | 1 | 0 | 0 | 0 | 72 | 0.82 | 13 | 只跟预计评分走 |
| `aggressive`（激进） | 1 | 2 | 0.3 | 0.25 | 72 | 0.85 | 12 | 以预计评分为底，类比偏高时加倍上推，偏低时轻扣 |

校验规则：`floor ≤ center ≤ cap ≤ 100`，`rise`、`fall` 为正，权重不为负。

## 3. `verdict`：判定措辞
| 字段 | 当前值 | 说明 |
| --- | --- | --- |
| `likelyLove` | 84 | 匹配度达到它显示「很可能喜欢」 |
| `likelyGood` | 72 | 达到它显示「应该不错」 |
| `average` | 60 | 达到它显示「中规中矩」，再低是「对你来说可能偏一般」 |
| `passLine` | 6 | 预计评分低于它时一律显示「可能不太合你口味」 |

要求 `average < likelyGood < likelyLove`。

## 4. `analogy`：相似作品类比
| 字段 | 当前值 | 说明 |
| --- | --- | --- |
| `neighbors` | 8 | 取标签最接近的几部已评分作品 |
| `shrink` | 0.3 | 相似度总量小时向 0 收缩的强度 |
| `minStd` | 0.6 | 评分标准差的下限 |
| `boldFrom` / `boldFull` | 0.4 / 0.7 | 最高相似度从多少开始、到多少满额地把预计评分往类比方向推 |
| `boldWeight` | 0.25 | 最多推多少个标准差 × 类比标准分 |

## 5. `divergence`：个人分歧模型（预计评分）
| 字段 | 当前值 | 说明 |
| --- | --- | --- |
| `minSamples` | 8 | 至少多少部有社区评分的已评分作品才建模 |
| `maxSamples` | 300 | 回归最多用最近多少部 |
| `maxAnalogyExamples` | 2000 | 类比最多参考多少部 |
| `tagRidgeLambda` | 1 | 标签岭回归的正则强度 |
| `personalGain` | 1.3 | 个人分歧整体放大倍数 |
| `franchiseWeight` | 0.32 | 同系列评分习惯的权重 |
| `axisWeight` | 0.33 | 热门 / 年代 / 篇幅取向的权重 |
| `franchiseShrink` | 0.5 | 同系列收缩 |
| `axisRidgeLambda` | 8 | 取向回归正则 |
| `slopePriorStrength` | 15 | 社区分换算斜率的先验强度 |
| `maxSlope` | 2.5 | 换算斜率上限 |
| `maxTags` | 30 | 每部作品最多用多少个标签 |

## 6. `categoryWeights`：十二维画像权重
| 维度 | 权重 |
| --- | --- |
| `topic` | 0.24 |
| `combo` | 0.18 |
| `device` | 0.09 |
| `xp` | 0.12 |
| `character` | 0.05 |
| `staff` | 0.08 |
| `cv` | 0.04 |
| `source` | 0.05 |
| `time` | 0.03 |
| `meme` | 0.03 |
| `comment` | 0.06 |
| `community` | 0.03 |
| `noise` | 0.005 |

## 7. `scoring`：其余全部常量
名称与代码 `TasteScoringParams` 中的常量一一对应（说明取自代码注释的第一句）。

| 名称 | 当前值 | 说明 |
| --- | --- | --- |
| `MAX_PROFILE_SAMPLES` | 120 | 画像真实样本上限 120：评分多的用户也能把中间分作品纳入画像（上限太小时只剩「极端分 + 最近几部」， 负向 / 中性证据会缺失）。 |
| `COMBO_MAX_TAGS_PER_WORK` | 16 | 单部作品参与题材组合挖掘的标签数上限（按标注强度取前 N）。 |
| `TIME_HALF_LIFE_DAYS` | 540 | 时间半衰期（天）：约 18 个月，最近两年评分更重要但老作品不被抹掉。 |
| `TIME_WEIGHT_FLOOR` | 0.3 | 时间权重地板：再旧的评分也保留此最低话语权。 |
| `TIME_FLOOR_RATING_10` | 0.85 | 高分例外地板：10 分作品时间权重不低于此（防「人生锚点」被近因冲淡）。 |
| `TIME_FLOOR_RATING_9` | 0.7 | 高分例外地板：9 分作品时间权重不低于此。 |
| `EXTREME_WEIGHT` | 1.15 | 极端分加权：评分 ∈ {1,2,9,10} 时样本权重乘此系数（其余 1.0）。 |
| `COMMENT_LEN_FULL` | 80 | 评论长度归一上限（字符）：超过即拿满评论长度加成。 |
| `PREF_SLOPE` | 0.35 | 评分中心化斜率（每偏离中性点 1 分的偏好增量）。 |
| `PREF_NEUTRAL_BIAS` | 0.5 | 评分中心化**中性点下移量**：`neutral = userMean − PREF_NEUTRAL_BIAS`。 |
| `PREF_MAX_ABS` | 1.25 | 评分中心化偏好绝对值上限（对齐绝对 [pref] 的 10 分强度）。 |
| `COMBO_MIN_SUPPORT` | 1.8 | 题材组合挖掘：最小支持度（低于此视为偶然命中，丢弃）。 |
| `COMBO_MAX_ORDER` | 3 | 题材组合挖掘：最大阶数（只做二元 / 三元，不做四元以上）。 |
| `COMBO_MIN_POSITIVE_WORKS` | 2 | 题材组合挖掘：组合至少出现在多少部正向作品中才保留。 |
| `COMBO_ORDER_PENALTY_EXP` | 0.3 | 组合强度 `strength(C) = signed_support(C) / /C/^0.3` 的阶数惩罚指数。 |
| `LAMBDA_NEGATIVE` | 0.75 | 各类相似度负向惩罚系数 λ_c（正向减负向时的负向权重）。 |
| `MIN_NEGATIVE_SUPPORT` | 2 | 负向量最小支持度：标签至少需要在指定数量的已评分作品中出现， 才有资格进入负向偏好向量。 |
| `PROFILE_IDF_STRENGTH` | 0.85 | 画像标签 **IDF 逆频权重强度** γ（修 Spearman 区分度差）。 |
| `EPSILON` | 1e-06 | 防 0 除小量。 |
| `COMMUNITY_BLEND_MAX` | 0.8 | 社区融合最大权重 `w_c^max`。 |
| `COMMUNITY_GATE_EXP` | 1.5 | 门控陡度指数：`gate = (1-/score-50//50)^EXP`，>1 使门更快关闭、更保护强立场口味。 |
| `COMMUNITY_NEUTRAL_SCORE` | 6.6 | 社区分映射的中性点：Bangumi 动画有排名条目的评分中位约 6.6，此分对应匹配度 50（不加不减）。 |
| `COMMUNITY_SCORE_SCALE` | 1.3 | 社区分映射的尺度：`50 + 45·tanh((score − 6.6)/1.3)`，8.5→≈90、7.5→≈77、7.0→≈64、6.0→≈30。 |
| `SATISFACTION_COMMUNITY_STD` | 0.9 | 社区分标准化的中心与尺度（Bangumi 动画评分的站点水平）。 |
| `SATISFACTION_PERSONAL_MIN` | 0.2 | 个人画像在满意度里的权重：评分少时 0.20，评分达到 [SATISFACTION_FULL_SAMPLES] 时 0.30。 |
| `SATISFACTION_PERSONAL_MAX` | 0.3 |  |
| `SATISFACTION_MIN_SAMPLES` | 20 |  |
| `SATISFACTION_FULL_SAMPLES` | 60 |  |
| `SATISFACTION_VOTE_PRIOR` | 30 | 社区分的贝叶斯票数收缩：`votes/(votes+30)`，只压制「十几个人打出的极端分」。 |
| `SATISFACTION_Z_CLIP` | 3 | 画像 z 分截断与满意度的 tanh 尺度。 |
| `SATISFACTION_Z_SCALE` | 1.5 |  |
| `SATISFACTION_PROFILE_ONLY_GAIN` | 0.6 | 没有社区评分时，只凭画像给出的满意度要保守：画像 z 分乘此系数。 |
| `EVIDENCE_FULL_TAGS` | 2 | 候选作品拿满证据量所需的有效内容标签数（题材 / 情节 / 角色类型 / 来源 / 梗 / 年代）。 |
| `ISOTONIC_MIN_SAMPLES` | 25 | 拟合 isotonic 校准所需的最小显式评分样本数； |
| `LOGISTIC_TAU_FLOOR` | 0.18 | 温度化 logistic 的温度地板 `τ_u = max(0.18, std(z_train))`（冷启动 / logistic 兜底用）。 |
| `CALIBRATION_TAU_FLOOR` | 0.05 | 校准温度地板：候选池校准下 combo 已归一化，rawZ 落在 ~[-0.3, 1] 的小尺度， [LOGISTIC_TAU_FLOOR]=0.18 会过大而压平区分度； |
| `SPREAD_BASE` | 50 | 分数拉开：基准分。 |
| `SPREAD_MAX_OFFSET` | 45 | 分数拉开：最大偏移（最终分落在 [5,95]）。 |
| `SPREAD_GAIN` | 1.18 | 分数拉开：放大系数（>1，敢拉开）。 |
| `RATED_BIAS_COEFF` | 0.12 | 已评分同作偏置：`bias = 0.12·pref(r_x)`，加在校准前 raw 分上。 |
| `RATED_ANCHOR_WEIGHT` | 0.88 | 已评分作品的显示锚定权重。 |
| `TONIGHT_MIN_BANGUMI_SCORE` | 6 | 今晚推荐默认最低 Bangumi 分（硬过滤）。 |
| `TONIGHT_MIN_TASTE_SCORE` | 55 | 今晚推荐默认最低口味匹配度（硬过滤，0–100）。 |
| `TONIGHT_MIN_TIME_FIT` | 0.55 | 时间拟合度硬过滤下限。 |
| `TONIGHT_REPEAT_COOLDOWN_DAYS` | 14 | 重复推荐冷却天数。 |
| `TONIGHT_MAX_PER_FRANCHISE` | 1 | 单系列默认最多保留进入末轮的部数。 |
| `RUNTIME_TV_PER_EP` | 24 | runtime 估计兜底（分钟 / 集 或 单部）：TV 每集、SP、OVA、剧场版。 |
| `RUNTIME_SP` | 45 |  |
| `RUNTIME_OVA` | 30 |  |
| `RUNTIME_MOVIE` | 90 |  |
| `MMR_LAMBDA` | 0.78 | 多样性重排 MMR 系数 λ（越大越偏相关度、越小越偏多样性）。 |
| `TONIGHT_W_TASTE` | 0.34 | 今晚分各项权重（`tonightRaw` 线性融合）。 |
| `TONIGHT_W_INTENT_TAG` | 0.32 |  |
| `TONIGHT_W_TIME_FIT` | 0.1 |  |
| `TONIGHT_W_COMMUNITY` | 0.07 |  |
| `TONIGHT_W_NOVELTY` | 0.06 |  |
| `TONIGHT_W_COMPLETION_EASE` | 0.05 |  |
| `TONIGHT_W_FRESHNESS` | 0.06 |  |
| `TONIGHT_W_REPEAT_PENALTY` | 0.18 |  |
| `TONIGHT_W_FRANCHISE_PENALTY` | 0.14 |  |
| `TONIGHT_W_EXAM_RISK` | 0.12 |  |
| `TONIGHT_W_DOMINANT_CONFLICT` | 0.18 | 主气质冲突惩罚：作品的主气质与所选意图相冲（选校园恋爱日常，作品主打超能力战斗）。 |

## 8. 以后开放导入时的流程（预留）
1. 设置里导出当前算法（`exportTemplate()`），得到一份完整模板。
2. 修改后上传；应用先解析、再按上面的规则校验（`validate()`），有问题时列出每一条，不做任何改动。
3. 通过后整份替换当前算法，口味画像用缓存重算一次；可以随时恢复内置算法。
