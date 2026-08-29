#!/usr/bin/env python3
"""Rank .feature files by the lines a Background / Scenario Outline refactor would save.

Usage: SCENARI_CORPUS=/path/to/features script/gherkin-refactor-candidates.py [file-list]
       (file-list: one corpus-relative .feature path per line; defaults to walking the corpus)
"""
import os, re, sys
ROOT = os.environ.get("SCENARI_CORPUS") or "/Users/hmadelaine/devel/electre/refonte/diffusion/domain/resources/scenarios"
files = ([l.strip() for l in open(sys.argv[1]) if l.strip()] if len(sys.argv) > 1
         else sorted(os.path.relpath(os.path.join(d, f), ROOT)
                     for d, _, fs in os.walk(ROOT) for f in fs if f.endswith('.feature')))
KW=r'(Etant donn[ée]+|Soit|Quand|Lorsque|Alors|Et|Mais|Given|When|Then|And|But)\b'
def parse(p):
    txt=open(os.path.join(ROOT,p),encoding='utf-8').read()
    lines=txt.split('\n')
    scen=[]   # (name, [step-blocks])
    cur=None; bg=False; rule=False; outline=False
    for ln in lines:
        s=ln.strip()
        if re.match(r'^(Background|Contexte)\s*:',s): bg=True; cur=None; continue
        if re.match(r'^(Rule|R[èe]gle)\s*:',s): rule=True; cur=None; continue
        if re.match(r'^(Scenario Outline|Plan du sc[ée]nario|Sc[ée]nario Outline)\s*:',s,re.I):
            outline=True; cur=None; continue
        if re.match(r'^(Scenario|Sc[ée]nario|Example|Exemple)\s*:',s):
            cur=[s.split(':',1)[1].strip(),[]]; scen.append(cur); continue
        if re.match(r'^(Examples|Exemples)\s*:',s): cur=None; continue
        if cur is None: continue
        if re.match('^'+KW,s): cur[1].append([s])
        elif (s.startswith('|') or s.startswith('"""')) and cur[1]: cur[1][-1].append(s)
    return scen,bg,rule,outline

def norm(step):
    t=' '.join(step)
    t=re.sub(r'\s+',' ',t)
    return t
def skel(step):
    t=norm(step)
    t=re.sub(r'"[^"]*"','"~"',t)
    t=re.sub(r'\d+','~',t)
    t=re.sub(r'\|[^|]*','|~',t)   # collapse table cells
    return t

out=[]
for p in files:
    try: scen,bg,rule,outline=parse(p)
    except Exception as e: continue
    if len(scen)<2: 
        continue
    notes=[]
    # background candidate: common leading identical steps
    pref=0
    first=[norm(s) for s in scen[0][1]]
    while pref<len(first) and all(len(sc[1])>pref and norm(sc[1][pref])==first[pref] for sc in scen):
        pref+=1
    if pref>0 and not bg:
        nlines=sum(len(s) for s in scen[0][1][:pref])
        notes.append(("BACKGROUND",pref,nlines*(len(scen)-1)))
    # outline candidate: groups of scenarios with identical skeleton sequence
    groups={}
    for i,sc in enumerate(scen):
        k=tuple(skel(s) for s in sc[1][pref:])
        groups.setdefault(k,[]).append(i)
    dup=[(k,v) for k,v in groups.items() if len(v)>1 and len(k)>0]
    for k,v in dup:
        nlines=sum(sum(len(s) for s in scen[i][1][pref:])+1 for i in v[1:])
        notes.append(("OUTLINE",len(v),nlines))
    if notes:
        gain=sum(n[2] for n in notes)
        out.append((gain,p,len(scen),bg,rule,outline,notes))
out.sort(reverse=True)
for gain,p,n,bg,rule,outline,notes in out:
    flags=''.join(c for c,b in (('B',bg),('R',rule),('O',outline)) if b) or '-'
    print(f"{gain:5d}  {p}  [{n} scen, has:{flags}]")
    for t,a,g in notes:
        print(f"         {t} x{a} -> ~{g} lines")
