import json, time, urllib.parse, urllib.request, sys

API = "https://en.wikipedia.org/w/api.php"
UA = {"User-Agent": "NOVA-local-assistant/1.0 (dataset build)"}
SEP = "\u241F"

def api(params):
    q = urllib.parse.urlencode(params)
    req = urllib.request.Request(API + "?" + q, headers=UA)
    last = None
    for _ in range(4):
        try:
            with urllib.request.urlopen(req, timeout=40) as r:
                return json.load(r)
        except Exception as e:
            last = e
            time.sleep(3)
    raise RuntimeError("api failed: %s (%s)" % (q[:120], last))

def links(page):
    data = api({"action": "parse", "prop": "links", "redirects": 1,
                "format": "json", "page": page})
    return [l["*"] for l in data["parse"]["links"] if l.get("ns") == 0]

def subpages(prefix="Vital articles/Level/4/"):
    data = api({"action": "query", "list": "allpages", "apprefix": prefix,
                "apnamespace": 4, "aplimit": 500, "format": "json"})
    return [p["title"] for p in data["query"]["allpages"]]

def batch_extract(titles, sentences=None):
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
            if t and e and len(e) > 150:
                out.append((t, e))
        time.sleep(0.25)
    return out

def main(path, levels):
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
        print("level 2+3 titles: %d" % len(base))
        full = batch_extract(base)
        print("level 2+3 extracted: %d" % len(full))
    if "4" in levels:
        try:
            l4 = []
            for page in subpages():
                for t in links(page):
                    if t not in seen:
                        seen.add(t)
                        l4.append(t)
            print("level 4 titles: %d" % len(l4))
            short = batch_extract(l4, sentences=3)
            print("level 4 extracted: %d" % len(short))
            full += short
        except Exception as e:
            print("level 4 failed, continuing with 2+3 only:", e)
    with open(path, "w", encoding="utf-8") as f:
        for t, e in full:
            paras = [p.strip() for p in e.split("\n") if p.strip()]
            f.write(t.replace("\n", " ") + SEP + SEP.join(paras) + "\n")
    print("wrote %d articles to %s" % (len(full), path))

if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "wiki/articles-v1.txt",
         sys.argv[2].split(",") if len(sys.argv) > 2 else ["2", "3", "4"])
