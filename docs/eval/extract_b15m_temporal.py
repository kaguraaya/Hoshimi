"""从 Bangumi15M 生成按时间切分的评测集。

用法：python3 extract_b15m_temporal.py <解压目录> [切点 YYYY-MM-DD，默认 2022-01-01] [用户数，默认 600]
输出：<解压目录>/eval-temporal.json

- 社区分「按当时」重算：只用切点之前的评分（所有用户、所有收藏类型里有分的记录）计算每部作品的
  均分、人数和 1–10 分各档人数，代替数据集里 2023-05 的快照，避免用到切点之后的评分。
- 评测用户：切点前至少 15 条、切点后至少 5 条「看过且打分」的记录，按用户 ID 的 MD5 排序取前 N 位（可复现）。
- 校准池：按切点前的收藏人数取前 1500 部动画。
- 标签只有快照版本（无法按时间回溯），报告里需注明。
- `updated_at` 是最后修改时间而不是首次评分时间，切点附近有少量记录会被归错一侧。
同一份输出还带快照社区分（score / votes / hist），便于对比两种口径。
"""
import ast
import hashlib
import json
import sys

import numpy as np
import pandas as pd

D = sys.argv[1]
CUT = pd.Timestamp(sys.argv[2] if len(sys.argv) > 2 else "2022-01-01", tz="UTC")
N = int(sys.argv[3]) if len(sys.argv) > 3 else 600
cut_ms = int(CUT.value // 10**6)

df = pd.read_csv(f"{D}/bangumi15M/raw_data/AnonymousUserCollection.csv",
                 usecols=["user_id", "subject_id", "rating", "type", "updated_at"],
                 dtype={"user_id": "category", "type": "category", "subject_id": "int32", "rating": "int8"})
# 明确换算到毫秒：pandas 新版本默认的时间精度不是纳秒，不能靠「整除 10^6」换算。
df["ts"] = pd.to_datetime(df.updated_at, utc=True, errors="coerce").dt.as_unit("ms").astype("int64")
df = df[df.rating > 0]
before = df[df.ts < cut_ms]

# 切点前的社区统计（所有有分的收藏）
grp = before.groupby("subject_id")
asof_votes = grp.size()
asof_mean = grp.rating.mean()
hist = before.groupby(["subject_id", "rating"]).size().unstack(fill_value=0).reindex(columns=range(1, 11), fill_value=0)

col = df[df.type == "collect"]
pre = col[col.ts < cut_ms].groupby("user_id", observed=True).size()
post = col[col.ts >= cut_ms].groupby("user_id", observed=True).size()
pre.index = pre.index.astype(str)
post.index = post.index.astype(str)
both = pd.DataFrame({"pre": pre, "post": post}).fillna(0)
elig = both[(both.pre >= 15) & (both.post >= 5) & (both.pre + both.post <= 400)].index
key = lambda u: hashlib.md5(u.encode()).hexdigest()
chosen = sorted(elig, key=key)[:N]
sub = col[col.user_id.isin(chosen)]

items = pd.read_csv(f"{D}/bangumi15M/raw_data/Subjects.csv", index_col=0,
                    usecols=[0, 1, 2, 3, 4, 5, 6, 7, 11, 13, 14, 15, 16, 19])
items = items[items.type == 2].drop_duplicates("id").set_index("id")
pool_rank = before[before.subject_id.isin(items.index)].groupby("subject_id").size().sort_values(ascending=False)
pool = [int(i) for i in pool_rank.index[:1500]]
need = set(int(s) for s in sub.subject_id.unique()) | set(pool)

subjects = {}
for sid in need:
    if sid not in items.index:
        continue
    r = items.loc[sid]
    try:
        tags = ast.literal_eval(r.tags) if isinstance(r.tags, str) else []
    except Exception:
        tags = []
    try:
        rating = ast.literal_eval(r.rating) if isinstance(r.rating, str) else {}
    except Exception:
        rating = {}
    snap_hist = [int((rating.get("count") or {}).get(str(k), 0) or 0) for k in range(1, 11)]
    v = int(asof_votes.get(sid, 0))
    subjects[int(sid)] = {
        "name": r.name_cn if isinstance(r.name_cn, str) and r.name_cn else (r["name"] if isinstance(r["name"], str) else ""),
        "name_ja": r["name"] if isinstance(r["name"], str) else "",
        "tags": [[t["name"], int(t["count"])] for t in tags if isinstance(t, dict) and t.get("name")],
        "score": round(float(asof_mean.get(sid, 0.0)), 2) if v > 0 else 0.0,
        "votes": v,
        "hist": [int(x) for x in hist.loc[sid].tolist()] if sid in hist.index else [0] * 10,
        "snapshot_score": float(r.score) if r.score == r.score else 0.0,
        "snapshot_votes": int(rating.get("total", 0) or 0),
        "snapshot_hist": snap_hist,
        "eps": int(r.eps) if r.eps == r.eps else 0,
        "platform": r.platform if isinstance(r.platform, str) else "",
        "date": r.date if isinstance(r.date, str) else "",
    }

users = []
for u, g in sub.groupby("user_id", observed=True):
    its = [[int(a), int(b), int(c)] for a, b, c in zip(g.subject_id, g.rating, g.ts) if int(a) in subjects]
    n_pre = sum(1 for x in its if x[2] < cut_ms)
    if n_pre >= 15 and len(its) - n_pre >= 5:
        users.append({"id": u, "items": its})
out = {"cutoff_ms": cut_ms, "subjects": subjects, "users": users, "pool": [p for p in pool if p in subjects]}
json.dump(out, open(f"{D}/eval-temporal.json", "w"), ensure_ascii=False)
n_train = sum(1 for u in users for x in u["items"] if x[2] < cut_ms)
n_test = sum(1 for u in users for x in u["items"] if x[2] >= cut_ms)
print("cutoff", CUT.date(), "users", len(users), "subjects", len(subjects), "train", n_train, "test", n_test,
      "eligible", len(elig))
