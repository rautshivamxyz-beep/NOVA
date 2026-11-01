import json, os, time, urllib.parse, urllib.request, sys

API = "https://en.wikipedia.org/w/api.php"
UA = {"User-Agent": "NOVA-local-assistant/1.0 (offline study app; dataset build)",
      "Accept-Encoding": "identity"}
SEP = "\u241F"

def api(params):
    q = urllib.parse.urlencode(params)
    req = urllib.request.Request(API + "?" + q, headers=UA)
    last = None
    for attempt in range(8):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            last = e
            if e.code in (429, 503):        # rate limited - back off properly
                wait = 20 + 10 * attempt
                print("rate limited (%d), waiting %ds" % (e.code, wait), flush=True)
                time.sleep(wait)
            else:
                time.sleep(3)
        except Exception as e:
            last = e
            time.sleep(3)
    raise RuntimeError("api failed: %s (%s)" % (q[:120], last))

def links(page):
    data = api({"action": "parse", "prop": "links", "redirects": 1,
                "format": "json", "page": page})
    return [l["*"] for l in data["parse"]["links"] if l.get("ns") == 0]

def subpages(prefix="Vital articles/Level/4/"):
    titles = []
    cont = None
    while True:
        params = {"action": "query", "list": "allpages", "apprefix": prefix,
                  "apnamespace": 4, "aplimit": 500, "format": "json"}
        if cont:
            params["apcontinue"] = cont
        data = api(params)
        titles += [p["title"] for p in data["query"]["allpages"]]
        cont = data.get("query-continue", {}).get("allpages", {}).get("apfrom") \
            or data.get("continue", {}).get("apcontinue")
        if not cont:
            return titles

def batch_extract(titles, sentences=None, min_len=80):
    out = []
    for i in range(0, len(titles), 20):
        chunk = titles[i:i+20]
        params = {"action": "query", "prop": "extracts", "explaintext": 1,
                  "exintro": 1, "exlimit": 20, "redirects": 1, "format": "json",
                  "titles": "|".join(chunk)}
        if sentences:
            params["exsentences"] = sentences
        try:
            data = api(params)
        except Exception:
            continue
        pages = data.get("query", {}).get("pages", {})
        for p in pages.values():
            t = p.get("title", "")
            e = p.get("extract", "")
            if t and e and len(e) > min_len:
                out.append((t, e))
        if (i // 20) % 10 == 0:
            print("  extracted %d / %d" % (len(out), i + len(chunk)), flush=True)
        time.sleep(1.0)
    return out

def main(path, levels):
    out_dir = os.path.dirname(path)
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)
    seen = set()
    full = []
    if "2" in levels or "3" in levels:
        base = []
        for lv in ("2", "3"):
            if lv in levels:
                for t in links("Wikipedia:Vital articles/Level " + lv):
                    if t not in seen:
                        seen.add(t)
                        base.append(t)
        print("level 2+3 titles: %d" % len(base), flush=True)
        full = batch_extract(base)
        print("level 2+3 extracted: %d" % len(full), flush=True)
    if "4" in levels:
        try:
            l4 = []
            for page in subpages():
                for t in links(page):
                    if t not in seen:
                        seen.add(t)
                        l4.append(t)
                # pace the topic-list calls too - bursting them triggers
                # Wikipedia's rate limiter and the job crawls through backoffs
                time.sleep(1.2)
            print("level 4 titles: %d" % len(l4), flush=True)
            short = batch_extract(l4, sentences=3)
            print("level 4 extracted: %d" % len(short), flush=True)
            full += short
        except Exception as e:
            print("level 4 failed, continuing with 2+3 only:", e, flush=True)
    with open(path, "w", encoding="utf-8") as f:
        for t, e in full:
            paras = [p.strip() for p in e.split("\n") if p.strip()]
            f.write(t.replace("\n", " ") + SEP + SEP.join(paras) + "\n")
    print("wrote %d articles to %s" % (len(full), path), flush=True)

if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "wiki/articles-v1.txt",
         sys.argv[2].split(",") if len(sys.argv) > 2 else ["2", "3", "4"])
