"""从 Bangumi15M 抽样评测子集：python3 extract_b15m.py <解压目录> <大样本用户数>，输出 <解压目录>/eval.json"""
import pandas as pd, numpy as np, json, ast, sys, hashlib
D=sys.argv[1]; NUSERS=int(sys.argv[2])
df=pd.read_csv(f"{D}/bangumi15M/raw_data/AnonymousUserCollection.csv",usecols=['user_id','subject_id','rating','type','updated_at'],dtype={'user_id':'category','type':'category'})
df=df[(df.rating>0)&(df.type=='collect')]
cnt=df.groupby('user_id',observed=True).size()
elig=cnt[(cnt>=40)&(cnt<=300)].index
small=cnt[(cnt>=12)&(cnt<40)].index
print('eligible users',len(elig), 'median rated among all raters', cnt[cnt>0].median())
k=lambda u: hashlib.md5(u.encode()).hexdigest()
elig=sorted(elig,key=k)[:NUSERS]+sorted(small,key=k)[:NUSERS//2]
sub=df[df.user_id.isin(elig)].copy()
sub['ts']=pd.to_datetime(sub.updated_at,utc=True,errors='coerce').astype('int64')//10**6
items=pd.read_csv(f"{D}/bangumi15M/raw_data/Subjects.csv",index_col=0,usecols=[0,1,2,3,4,5,6,7,11,13,14,15,16,19])
items=items[items.type==2].drop_duplicates('id').set_index('id')
need=set(sub.subject_id.unique())
# calibration pool: popular anime (by collect count)
pool=[int(i) for i in items.sort_values('collect',ascending=False).index[:1500]]
need|=set(pool)
subjects={}
for sid in need:
    if sid not in items.index: continue
    r=items.loc[sid]
    try: tags=ast.literal_eval(r.tags) if isinstance(r.tags,str) else []
    except Exception: tags=[]
    try: rating=ast.literal_eval(r.rating) if isinstance(r.rating,str) else {}
    except Exception: rating={}
    subjects[int(sid)]={'name':(r.name_cn if isinstance(r.name_cn,str) and r.name_cn else r['name']) if True else '',
        'name_ja': r['name'] if isinstance(r['name'],str) else '',
        'tags':[[t['name'],int(t['count'])] for t in tags if isinstance(t,dict) and t.get('name')],
        'score':float(r.score) if r.score==r.score else 0.0,'votes':int(rating.get('total',0) or 0),
        'eps':int(r.eps) if r.eps==r.eps else 0,'platform':r.platform if isinstance(r.platform,str) else '', 'date': r.date if isinstance(r.date,str) else ''}
users=[]
for u,g in sub.groupby('user_id',observed=True):
    its=[[int(a),int(b),int(c)] for a,b,c in zip(g.subject_id,g.rating,g.ts) if int(a) in subjects]
    if len(its)>=12: users.append({'id':u,'items':its})
json.dump({'subjects':subjects,'users':users,'pool':[p for p in pool if p in subjects]},open(f"{D}/eval.json",'w'),ensure_ascii=False)
print('users',len(users),'subjects',len(subjects),'ratings',sum(len(u['items']) for u in users))
