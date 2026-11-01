# NCIE v0.9.0 — The Memory System

A quality-gated memory for NOVA's offline assistant: verified answers
become **graded facts** that confirm toward **graduation** into the
knowledge base — or decay and die.

## What changed

| File | Status | What |
|---|---|---|
| `learn/LearnedFact.kt` | new | The graded-memory data model + one-line serialization (`serialize()` / `parse()`), plus the package's shared `escapeLine`/`unescapeLine` (extracted verbatim from PersistentLearner so cache lines and fact lines share one implementation) |
| `learn/Learner.kt` | modified | Interface gains `record(fact)`, `forget(questionKey)`, `learnedFacts()`, `demoteStale(maxAgeDays)` — all with default no-ops, so `SimpleLearner` and any custom learner keep compiling. Everything below v0.9.0 is unchanged |
| `learn/PersistentLearner.kt` | split + modified | `PersistentLearner` and `LearningStore` moved out of `Learner.kt` into their own file (the memory outgrew one file; same package, so no import changes anywhere). Implements the four new methods; `recall`/`recallFuzzy`/`record(text, response)`/`clear` semantics unchanged |
| `learn/Distiller.kt` | new | The consolidation pass: promotes graduated facts into `KnowledgeStore` via `addDoc`, forgets them from the learner, returns a `DistillReport`. Includes a plain-JVM `fun main()` demo |
| `NovaKernel.kt` | modified | After ④ VERIFY, in the ⑤ LEARN block: an answer whose `verdict.qualityScore >= 0.5` (and whose route is LLM or TOOL_THEN_LLM — pure TOOL calculator turns are excluded) is recorded as a `LearnedFact.fromChat(...)`. Six lines of logic, one new import, one constant |
| `MEMORY_DESIGN.md` | new | This document |

Nothing else in the app changes: `NcieLearn` (the Android host) keeps
working untouched — its `PersistentLearner` reference compiles against
the same package, and the old snapshot format loads unchanged.

## How the phases interact

```
① ANALYZE → ② PLAN → ③ EXECUTE → ④ VERIFY → ⑤ LEARN
                                                  │
                verdict.qualityScore >= 0.5 ──────┤  (LLM / TOOL_THEN_LLM routes only;
                                                  │   pure TOOL = calculator = nothing to learn)
                                                  ▼
                                    LearnedFact.fromChat(question, answer, quality)
                                                  ▼
                                    PersistentLearner.record(fact)
                                    (merge by question: answer updates, interactions+1,
                                     score = 0.6·old + 0.4·new, lastConfirmed = now)
                                                  ▼
        later, host-triggered:  Distiller(learner, knowledge).distill()
                                                  ▼
                        KnowledgeStore.addDoc(question, question + "\n\n" + answer)
                        + learner.forget(question)   ← graduated, leaves the cache
```

From then on the graduated fact lives in the knowledge base and is
served the way every note is: rarity-weighted retrieval injected into
the LLM prompt (`knowledgeContext`), with the x2 document-name bonus
making a re-ask of the question hit first.

## The score lifecycle

**Birth.** A verified answer with quality ≥ 0.5 becomes a fact with
that score and `interactions = 1`. (Score semantics found in the code:
`verify()` returns 1.0 cross-checked math, 0.95 cache, 0.5 the neutral
"no deterministic check" floor for chat answers; `quality()` returns
0.6 on a clean pass; every actual failure is 0.0–0.4. So 0.5 admits
"nothing said otherwise" and excludes everything that failed a check.)

**Probation.** Every re-answer of the same question merges into the
existing fact: `score = 0.6·old + 0.4·new`, `interactions += 1`,
`lastConfirmed = now`. One bad re-answer cannot kill a good fact; one
good one cannot instantly graduate a bad one. A fact born at the
neutral 0.5 floor reaches 0.66 after one good re-answer, 0.756 after
two — the probation curve is deliberately multi-step.

**Graduation.** `Distiller.distill()` promotes facts with
`score >= 0.7 AND interactions >= 2 AND answer > 40 chars` (the store's
chunker silently drops ≤-40-char pieces, so shorter answers would
promote into an empty document) into `KnowledgeStore.addDoc(name =
question, text = question + "\n\n" + answer)`, then `forget`s them from
the learner. Graduated knowledge stops occupying the LRU and starts
participating in RAG.

**Death.** Two roads:
- `demoteStale(maxAgeDays = 180)`, run by the Distiller first: entries
  whose `lastConfirmed` is older than 180 days get `score *= 0.5` —
  time alone never graduates anything, and decay pushes the other way.
  A stale fact can still recover: re-answering re-confirms it and
  blends the score back up.
- Eviction: the fact map shares the learner's `maxEntries` LRU budget.

**Unlearning.** `forget(questionKey)` is the surgical scalpel (one
question, memory + disk), `clear()` remains the whole-world reset
(invalidation on knowledge-base change).

## The snapshot format (v0.9.0)

Same file, same escaping (`\\`, `\t`, `\n`, `\r`), one record per line.
Cache lines are exactly as in v0.8.1:

```
<escaped question>\t<escaped answer>
```

Graded facts are appended after them, one per line, behind a **leading
tab** — which a cache line can never start with (escaped keys contain
no raw tabs), so the two kinds can never collide:

```
\t<escaped question>\t<escaped answer>\t<tokens,comma-joined>\t<source>\t
  <learnedAtMillis>\t<lastConfirmedMillis>\t<score>\t<interactions>\t<escaped provenance>
```

Old snapshots (pure cache lines) load unchanged; corrupt lines are
skipped, never fatal — `LearnedFact.parse` returns null on anything
malformed (wrong field count, stray backslash, out-of-range score, …)
and the learner just carries on.

## What the Android memory screen (phase 2) will need to read

All of it is already behind the `Learner` interface, on the learner's
own thread (`NcieLearn.io`), same as today's `stats()`:

- `learner.learnedFacts(): List<LearnedFact>` — one row per fact with
  `question`, `answer`, `topicTokens`, `score`, `interactions`,
  `learnedAtMillis`, `lastConfirmedMillis`, `provenance` (e.g.
  "learned from chat, quality 0.82"). Enough for a list with
  score/age/confidence chips and a "graduates at 0.7 × 2" progress bar.
- `learner.forget(question)` — swipe-to-delete, one row at a time.
- `Distiller(learner, knowledge).distill(): DistillReport` — a
  "consolidate now" button; the report gives `promoted / skipped /
  demotedStale` counts for a toast or log line.
- `learner.demoteStale(days)` — if the screen wants a manual aging pass.
- Graduated facts are visible through `KnowledgeStore.docs()` /
  `search()` like any document — the screen may want to mark
  learned-origin docs (currently the doc name is the question; a
  "learned:" prefix is a phase-2 decision, kept out of this PR).

## Verified

The whole system is pure Kotlin/JVM (no Android imports, no new
dependencies) and was compiled and run against the real repo sources
(`Types.kt`, `KnowledgeStore.kt`, `Verifier.kt`, `Analyzer.kt`,
`Planner.kt`, `Tool.kt`, `CalculatorTool.kt`): the `Distiller` demo
main runs on a plain JVM, and a 34-check test suite passed covering
serialize/parse round-trips, malformed-line tolerance, old-snapshot
compatibility, merge math, surgical forget, stale demotion, LRU caps,
the fuzzy-recall covering guarantee, and kernel end-to-end (chat
answers become facts; calculator turns never do).
