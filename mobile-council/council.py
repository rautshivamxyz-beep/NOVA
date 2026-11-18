#!/usr/bin/env python3
"""Phone-first multi-provider NOVA development council; Python standard library only."""
import argparse, concurrent.futures, datetime as dt, json, os, pathlib, re, subprocess, urllib.error, urllib.request
SCRIPT_DIR=pathlib.Path(__file__).resolve().parent; ROOT=SCRIPT_DIR.parent; RUNS=ROOT/".council"/"runs"; PATCHES=ROOT/".council"/"patches"
def envload():
 p=ROOT/".env"
 if p.exists():
  for line in p.read_text().splitlines():
   line=line.strip()
   if line and not line.startswith("#") and "=" in line:
    k,v=line.split("=",1); os.environ.setdefault(k.strip(),v.strip().strip('"').strip("'"))
def post(url,headers,data):
 req=urllib.request.Request(url,data=json.dumps(data).encode(),headers={**headers,"Content-Type":"application/json"},method="POST")
 try:
  with urllib.request.urlopen(req,timeout=int(os.getenv("COUNCIL_TIMEOUT","90"))) as r:return json.loads(r.read())
 except urllib.error.HTTPError as e:raise RuntimeError(f"HTTP {e.code}: {e.read().decode('utf-8','replace')[:800]}")
def providers():
 p=[]
 if os.getenv("GEMINI_API_KEY"):p.append(("Gemini","gemini",os.getenv("GEMINI_MODEL","gemini-2.5-flash")))
 if os.getenv("ANTHROPIC_API_KEY"):p.append(("Claude","claude",os.getenv("CLAUDE_MODEL","claude-3-5-haiku-latest")))
 for n,k,b,m in [("Grok","XAI_API_KEY","https://api.x.ai/v1","grok-3-mini"),("Groq","GROQ_API_KEY","https://api.groq.com/openai/v1","llama-3.3-70b-versatile"),("OpenAI","OPENAI_API_KEY","https://api.openai.com/v1","gpt-4.1-mini")]:
  if os.getenv(k):
   x={"Grok":"XAI","Groq":"GROQ","OpenAI":"OPENAI"}[n]; p.append((n,"openai",os.getenv(x+"_BASE_URL",b).rstrip("/"),os.getenv(x+"_MODEL",m),os.getenv(k)))
 if all(os.getenv(x) for x in ("INDUS_API_KEY","INDUS_BASE_URL","INDUS_MODEL")):p.append(("Indus","openai",os.getenv("INDUS_BASE_URL").rstrip("/"),os.getenv("INDUS_MODEL"),os.getenv("INDUS_API_KEY")))
 return p
def ask(p,system,prompt,tokens=800):
 n,kind,*c=p
 if kind=="gemini":
  d=post(f"https://generativelanguage.googleapis.com/v1beta/models/{c[0]}:generateContent?key={os.environ['GEMINI_API_KEY']}",{},{"systemInstruction":{"parts":[{"text":system}]},"contents":[{"role":"user","parts":[{"text":prompt}]}],"generationConfig":{"temperature":0.2,"maxOutputTokens":tokens}})
  return d["candidates"][0]["content"]["parts"][0]["text"]
 if kind=="claude":
  d=post("https://api.anthropic.com/v1/messages",{"x-api-key":os.environ["ANTHROPIC_API_KEY"],"anthropic-version":"2023-06-01"},{"model":c[0],"max_tokens":tokens,"system":system,"messages":[{"role":"user","content":prompt}]})
  return "\n".join(x.get("text","") for x in d.get("content",[]) if x.get("type")=="text")
 base,model,key=c
 d=post(base+"/chat/completions",{"Authorization":"Bearer "+key},{"model":model,"temperature":0.2,"max_tokens":tokens,"messages":[{"role":"system","content":system},{"role":"user","content":prompt}]})
 return d["choices"][0]["message"]["content"]
def slug(s):return re.sub(r"[^a-zA-Z0-9_-]+","-",s).strip("-_")[:45] or "task"
def debate(a):
 ps=providers()
 if len(ps)<2:raise SystemExit("Debate requires at least two configured providers. See .env.example; unconfigured services cannot participate.")
 ps=ps[:max(2,min(5,int(os.getenv("COUNCIL_MAX_AGENTS","5"))))]
 base=f"NOVA is a native Kotlin Android app. Task: {a.task}\nContext:\n{a.context}\nGive a proposal with assumptions, affected files, risks, steps and tests. Do not claim to inspect files not supplied."
 print("Independent proposal round:",", ".join(p[0] for p in ps)); answers={}
 with concurrent.futures.ThreadPoolExecutor(max_workers=len(ps)) as ex:
  fs={ex.submit(ask,p,"Act as an independent senior software engineer. Be precise and critical.",base):p[0] for p in ps}
  for f in concurrent.futures.as_completed(fs):
   n=fs[f]
   try:answers[n]=f.result();print(" received",n)
   except Exception as e:answers[n]="PROVIDER ERROR: "+str(e);print(" failed",n,e)
 good={k:v for k,v in answers.items() if not v.startswith("PROVIDER ERROR:")}
 if len(good)<2:raise SystemExit("Fewer than two providers responded successfully; no debate report.")
 material="\n\n".join("### "+k+"\n"+v[:3000] for k,v in good.items())
 review="Task: "+a.task+"\nProposals:\n"+material+"\nCritique the other proposals. Find disagreements, false assumptions, risks and missing tests. Do not force agreement."
 print("Cross-review round"); critiques={}
 with concurrent.futures.ThreadPoolExecutor(max_workers=len(good)) as ex:
  fs={ex.submit(ask,next(p for p in ps if p[0]==n),"Act as a skeptical technical reviewer.",review,550):n for n in good}
  for f in concurrent.futures.as_completed(fs):
   n=fs[f]
   try:critiques[n]=f.result()
   except Exception as e:critiques[n]="REVIEW ERROR: "+str(e)
 chair=next((p for p in ps if p[0]=="Gemini" and p[0] in good),next(p for p in ps if p[0] in good))
 final=ask(chair,"You are the council chair. Decide based on evidence, not majority vote. Label uncertainty.","Task: "+a.task+"\nPROPOSALS:\n"+material+"\nREVIEWS:\n"+"\n\n".join("### "+k+"\n"+v[:1500] for k,v in critiques.items())+"\nProduce consensus, unresolved disagreements, implementation plan, files to inspect, tests and rollback.",1200)
 RUNS.mkdir(parents=True,exist_ok=True); path=RUNS/(dt.datetime.now().strftime("%Y%m%d-%H%M%S")+"-"+slug(a.task)+".md")
 path.write_text("# NOVA AI Council: "+a.task+"\n\nResponders: "+", ".join(good)+"\n\n## Chair decision\n"+final+"\n\n## Proposals\n"+material+"\n\n## Cross-reviews\n"+"\n\n".join("### "+k+"\n"+v for k,v in critiques.items()),encoding="utf-8")
 print("\nSaved report:",path.relative_to(ROOT));print(final)
def git(*args):
 r=subprocess.run(["git",*args],capture_output=True,text=True)
 if r.returncode:raise RuntimeError(r.stderr.strip())
 return r.stdout.strip()
def branch(a):
 if git("branch","--show-current") not in ("main","master"):raise SystemExit("Switch to main before creating a council feature branch.")
 name="ai-council/"+slug(a.name);git("switch","-c",name);print("Created",name)
def patch(a):
 if git("branch","--show-current") in ("main","master"):raise SystemExit("Safety stop: create a feature branch first.")
 report=pathlib.Path(a.report)
 if not report.is_file():raise SystemExit("Report not found: "+str(report))
 files=[]
 for x in a.files.split(","):
  f=pathlib.Path(x.strip())
  if f.is_absolute() or ".." in f.parts or not f.is_file() or f.stat().st_size>25000:raise SystemExit("Invalid, missing or oversized file: "+x)
  files.append((str(f),f.read_text(encoding="utf-8",errors="replace")))
 prompt="COUNCIL REPORT:\n"+report.read_text()[:9000]+"\nSOURCE FILES:\n"+"\n\n".join("FILE "+n+"\n"+v for n,v in files)+"\nReturn ONLY a unified git diff starting with diff --git. Change only supplied files. No markdown. If unsafe, return NO_SAFE_PATCH."
 ps=providers()
 if not ps:raise SystemExit("No configured provider.")
 name=os.getenv("COUNCIL_CODER","Gemini"); p=next((x for x in ps if x[0].lower()==name.lower()),ps[0])
 diff=ask(p,"Generate minimal, safe, reviewable code patches. Do not invent context.",prompt,2000).strip()
 fence=chr(96)*3; diff=diff.removeprefix(fence+"diff").removeprefix(fence).removesuffix(fence).strip()
 if not diff.startswith("diff --git ") or "NO_SAFE_PATCH" in diff:raise SystemExit("No valid patch returned; no files changed.")
 PATCHES.mkdir(parents=True,exist_ok=True); out=PATCHES/(dt.datetime.now().strftime("%Y%m%d-%H%M%S")+"-"+slug(a.name)+".patch");out.write_text(diff+"\n")
 check=subprocess.run(["git","apply","--check",str(out)],capture_output=True,text=True)
 if check.returncode:raise SystemExit("Patch saved but validation failed; nothing changed:\n"+check.stderr)
 print("Validated patch:",out)
 if a.apply:git("apply",str(out));print("Applied on feature branch. Inspect git diff.")
 else:print("Not applied. Inspect, then manually run git apply",out)
def status(_):
 print("Branch:",git("branch","--show-current"));print("Working tree:\n"+(git("status","--short") or "(clean)"));print("Configured:",", ".join(x[0] for x in providers()) or "no providers")
def main():
 envload();ap=argparse.ArgumentParser();sp=ap.add_subparsers(dest="cmd",required=True)
 q=sp.add_parser("debate");q.add_argument("task");q.add_argument("--context",default="");q.set_defaults(fn=debate)
 q=sp.add_parser("branch");q.add_argument("name");q.set_defaults(fn=branch)
 q=sp.add_parser("patch");q.add_argument("--name",required=True);q.add_argument("--report",required=True);q.add_argument("--files",required=True);q.add_argument("--apply",action="store_true");q.set_defaults(fn=patch)
 q=sp.add_parser("status");q.set_defaults(fn=status);a=ap.parse_args()
 try:a.fn(a)
 except KeyboardInterrupt:raise SystemExit("Cancelled")
 except Exception as e:raise SystemExit("ERROR: "+str(e))
if __name__=="__main__":main()
