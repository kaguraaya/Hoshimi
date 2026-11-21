"""Bangumi15M 评测报告：python3 report_b15m.py <eval-temporal.tsv 或 eval-random.tsv> [基准方案名，默认 legacy]

输入是 `TasteEvalHarnessTest` 逐条写出的预测（每个方案一组 pred_/base_/div_/score_/sat_ 列）。
所有指标先按用户分别计算再取平均（每位用户权重相同），并给出与基准方案的配对差和按用户抽样的 95% 区间。

- 排序一致性：用户内 Spearman。随机 5 折时各折的基线不同，排序用「社区分 + 个人分歧」这个跨折稳定的量；
  时间切分只有一个模型，直接用预计评分。
- 伤害率：排序一致性低于「只看社区分」的用户占比（另报低 0.05 以上的占比）。
- 个人偏离相关：模型给出的个人分歧与用户实际偏离（评分 − 社区分，减去该用户的平均差）的相关。
- 比常态更喜欢 AUC：上面的实际偏离 ≥ 1 的作品能否排在前面。高分 AUC：用户评分前 1/4。
- NDCG@10、前 10 命中（评分 ≥ max(8, 该用户 80 分位)）。
- 严重误推：预计 ≥ 9 而实际 ≤ 6 的占预计 ≥ 9 的比例；严重漏推：预计 ≤ 6 而实际 ≥ 9 的占实际 ≥ 9 的比例。
- 最差 10% 用户：排序一致性的第 10 百分位与最差一成用户的平均值。
- 匹配度 Spearman / 伤害率：首页大字的口味匹配度（score 列）和实际评分的用户内排序一致性；
- 匹配度与社区重合：匹配度和社区分的用户内 Spearman，越高越像社区榜；匹配度比常态更喜欢 AUC：同上面的定义，换成按匹配度排序。
- 分组：与社区的一致度（用测试集算，属于结果条件化分析）、训练评分数。
- 倾向加权敏感性（Schnabel 等 2016）：假设被评分的概率 ∝ 热度^α · 活跃度^β，报告 IPS / SNIPS 加权的平均误差，
  看方案间的结论在不同假设下是否翻转。只说明结论对假设的敏感程度，不代表无偏估计。
"""
import json
import sys

import numpy as np
import pandas as pd
from scipy.stats import rankdata

PATH = sys.argv[1]
BASE = sys.argv[2] if len(sys.argv) > 2 else "legacy"
RNG = np.random.default_rng(7)
BOOT = 1000

df = pd.read_csv(PATH, sep="\t")
variants = [c[5:] for c in df.columns if c.startswith("pred_")]
random_split = df["fold"].nunique() > 1
df = df[df.community > 0].copy()
df["dev"] = df.rating - df.community
df["t2"] = df.dev - df.groupby("user").dev.transform("mean")


def sp(a, b):
    ra, rb = rankdata(a), rankdata(b)
    if ra.std() == 0 or rb.std() == 0:
        return np.nan
    return float(np.corrcoef(ra, rb)[0, 1])


def auc(y, s):
    y = np.asarray(y, bool)
    n1, n0 = y.sum(), (~y).sum()
    if n1 == 0 or n0 == 0:
        return np.nan
    r = rankdata(s)
    return float((r[y].sum() - n1 * (n1 + 1) / 2) / (n1 * n0))


def ndcg10(score, rating):
    order = np.argsort(-np.asarray(score))[:10]
    gains = np.asarray(rating)[order]
    ideal = np.sort(np.asarray(rating))[::-1][:10]
    disc = 1 / np.log2(np.arange(2, 2 + len(gains)))
    return float((gains * disc).sum() / (ideal * disc[: len(ideal)]).sum())


def rank_score(g, v):
    """排序用的量：随机 5 折用 社区分 + 分歧，时间切分用预计评分。"""
    if random_split:
        return g.community + g[f"div_{v}"].fillna(0)
    return g[f"pred_{v}"].fillna(g.community)


users = [g for _, g in df.groupby("user") if g.rating.nunique() > 1 and len(g) >= 5]
comm_sp = np.array([sp(g.community, g.rating) for g in users])


def per_user(v):
    out = []
    for g, cs in zip(users, comm_sp):
        s = rank_score(g, v)
        pred = g[f"pred_{v}"]
        ok = pred.notna()
        div = g[f"div_{v}"].fillna(0)
        hi = g.rating >= max(8, g.rating.quantile(0.8))
        top = s.nlargest(10).index
        out.append({
            "sp": sp(s, g.rating), "comm": cs, "sp_match": sp(g[f"score_{v}"], g.rating),
            "match_ov": sp(g[f"score_{v}"], g.community), "match_auc_div": auc(g.t2 >= 1, g[f"score_{v}"]),
            "resid": np.corrcoef(div, g.t2)[0, 1] if div.std() > 1e-9 and g.t2.std() > 1e-9 else np.nan,
            "auc_div": auc(g.t2 >= 1, s), "auc_hi": auc(g.rating >= g.rating.quantile(0.75), s),
            "ndcg": ndcg10(s, g.rating), "top10": float(hi.loc[top].mean()),
            "mae": float(np.abs(pred[ok] - g.rating[ok]).mean()) if ok.any() else np.nan,
            "rmse": float(np.sqrt(((pred[ok] - g.rating[ok]) ** 2).mean())) if ok.any() else np.nan,
            "n_pred9": int((pred >= 9).sum()), "sev_over": int(((pred >= 9) & (g.rating <= 6)).sum()),
            "n_r9": int((g.rating >= 9).sum()), "sev_under": int(((pred <= 6) & (g.rating >= 9)).sum()),
            "n_train": int(g.n_train.iloc[0]),
        })
    return pd.DataFrame(out)


tables = {v: per_user(v) for v in variants}


def summarize(t):
    d = t.sp - t.comm
    worst = np.sort(t.sp.dropna())[: max(1, len(t) // 10)]
    return {
        "Spearman": t.sp.mean(), "中位": t.sp.median(), "伤害率": (d < 0).mean(), "差0.05+": (d < -0.05).mean(),
        "个人偏离相关": t.resid.mean(), "比常态更喜欢AUC": t.auc_div.mean(), "高分AUC": t.auc_hi.mean(),
        "NDCG@10": t.ndcg.mean(), "前10命中": t.top10.mean(), "MAE": t.mae.mean(), "RMSE": t.rmse.mean(),
        "严重误推": t.sev_over.sum() / max(1, t.n_pred9.sum()), "严重漏推": t.sev_under.sum() / max(1, t.n_r9.sum()),
        "最差10%均值": worst.mean(), "p10": np.nanpercentile(t.sp, 10),
        "匹配度Spearman": t.sp_match.mean(), "匹配度伤害率": (t.sp_match < t.comm).mean(),
        "匹配度与社区重合": t.match_ov.mean(), "匹配度比常态更喜欢AUC": t.match_auc_div.mean(),
    }


def boot_diff(a, b):
    m = ~(np.isnan(a) | np.isnan(b))
    a, b = a[m], b[m]
    idx = RNG.integers(0, len(a), (BOOT, len(a)))
    diffs = (a[idx] - b[idx]).mean(axis=1)
    return float((a - b).mean()), float(np.percentile(diffs, 2.5)), float(np.percentile(diffs, 97.5))


print(f"# {PATH}  用户 {len(users)}  条目 {len(df)}  {'随机 5 折' if random_split else '时间切分'}  基准 {BASE}\n")
comm = {"Spearman": comm_sp.mean(), "中位": np.median(comm_sp)}
rows = [{"方案": "只看社区分", **comm}] + [{"方案": v, **summarize(t)} for v, t in tables.items()]
summary = pd.DataFrame(rows).set_index("方案")
pd.set_option("display.width", 250)
pd.set_option("display.max_columns", 30)
print(summary.round(4).to_string())

print("\n## 与基准的配对差（按用户抽样 95% 区间）")
for v in variants:
    if v == BASE or BASE not in tables:
        continue
    a, b = tables[v], tables[BASE]
    for key, label in [("sp", "Spearman"), ("resid", "个人偏离相关"), ("mae", "MAE"), ("auc_div", "比常态更喜欢AUC")]:
        mean, lo, hi = boot_diff(a[key].to_numpy(float), b[key].to_numpy(float))
        print(f"  {v} − {BASE} {label}: {mean:+.4f} [{lo:+.4f}, {hi:+.4f}]")
    harm_a = (a.sp < a.comm).to_numpy(float)
    harm_b = (b.sp < b.comm).to_numpy(float)
    mean, lo, hi = boot_diff(harm_a, harm_b)
    print(f"  {v} − {BASE} 伤害率: {mean:+.4f} [{lo:+.4f}, {hi:+.4f}]")

print("\n## 分组（与社区一致度：测试集上「只看社区分」的 Spearman，结果条件化分析）")
bins = [-1, 0.3, 0.45, 0.6, 1.01]
for v, t in tables.items():
    t = t.assign(桶=pd.cut(t.comm, bins))
    g = t.groupby("桶", observed=True).apply(
        lambda x: pd.Series({"用户": len(x), "社区": x.comm.mean(), "方案": x.sp.mean(), "伤害率": (x.sp < x.comm).mean()}),
        include_groups=False)
    print(f"[{v}]\n{g.round(3).to_string()}")
print("\n## 分组（训练评分数）")
nb = [0, 19, 39, 99, 10_000]
for v, t in tables.items():
    t = t.assign(桶=pd.cut(t.n_train, nb))
    g = t.groupby("桶", observed=True).apply(
        lambda x: pd.Series({"用户": len(x), "社区": x.comm.mean(), "方案": x.sp.mean(), "伤害率": (x.sp < x.comm).mean()}),
        include_groups=False)
    print(f"[{v}]\n{g.round(3).to_string()}")

print("\n## 倾向加权敏感性（MAE；P ∝ 热度^α · 活跃度^β）")
pop = df.votes.clip(lower=1).astype(float)
act = df.n_train.clip(lower=1).astype(float)
flip_rows = []
for a_ in (0.0, 0.25, 0.5, 1.0):
    for b_ in (0.0, 0.5, 1.0):
        p = pop ** a_ * act ** b_
        p = p / p.mean()
        w = (1 / p).clip(upper=np.quantile(1 / p, 0.99))
        ess = w.sum() ** 2 / (w ** 2).sum()
        row = {"α": a_, "β": b_, "有效样本": int(ess)}
        for v in variants:
            ok = df[f"pred_{v}"].notna()
            err = (df.loc[ok, f"pred_{v}"] - df.loc[ok, "rating"]).abs()
            row[f"SNIPS_{v}"] = float((err * w[ok]).sum() / w[ok].sum())
        flip_rows.append(row)
print(pd.DataFrame(flip_rows).round(4).to_string(index=False))

json.dump({v: {k: (None if pd.isna(x) else float(x)) for k, x in summarize(t).items()} for v, t in tables.items()},
          open(PATH.rsplit(".", 1)[0] + "-summary.json", "w"), ensure_ascii=False, indent=1)
