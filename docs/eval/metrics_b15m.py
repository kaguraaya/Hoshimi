"""Bangumi15M 盲测指标：python3 metrics_b15m.py app/build/loo/b15m-components.tsv"""
import pandas as pd, numpy as np, sys
from scipy.stats import spearmanr

def per_user(df,col):
    out=[]
    for u,g in df.groupby('user'):
        if g.rating.nunique()<2 or g[col].nunique()<2: out.append(0.0); continue
        out.append(spearmanr(g[col],g.rating).correlation)
    return np.array(out)
def hit10(df,col):
    hs=[]
    for u,g in df.groupby('user'):
        top=g.nlargest(10,col); thr=max(8,g.rating.quantile(0.8))
        hs.append((top.rating>=thr).mean())
    return np.mean(hs)
def report(df,name):
    print(f"--- {name}: users={df.user.nunique()} rows={len(df)}")
    for col in ['community','profile','score','satisfaction']:
        s=per_user(df,col); print(f"  {col:28s} spearman mean={s.mean():.4f} median={np.median(s):.4f}  top10-precision={hit10(df,col):.3f}")
if __name__=='__main__':
    df=pd.read_csv(sys.argv[1],sep='\t')
    report(df,'all'); report(df[df.n>=40],'n>=40'); report(df[df.n<40],'n<40')
