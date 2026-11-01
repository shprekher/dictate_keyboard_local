# Offline bilingual vocabulary

Added by the local fork, November 2026. No new runtime dependencies or neural models.

## Data, provenance and licenses

`sources.json` pins each URL to a commit and records its **uncompressed SHA-256**.
`sources/*.gz` contains original, unmodified source snapshots. `build.py` expands the
simple-flag prefix/suffix rules in these specific Hunspell files and unions their word
forms with Danakt's Windows-1251 lexicon, lowercasing and sorting as UTF-8. The generated
index is a **modified derivative**, not a replacement Hunspell implementation: it does
not perform compound-word synthesis, contextual grammar checking or stemming at runtime.
It fails on unsupported continuation/complex affix directives rather than guessing.

| Source | License / notice |
| --- | --- |
| LibreOffice `en/en_US.aff`, `en/en_US.dic` | SCOWL and BSD-style Ispell; full SCOWL/WordNet/VarCon/Ispell notices in `en_US-license.txt` |
| LibreOffice `ru_RU/ru_RU.aff`, `ru_RU/ru_RU.dic` | Lebedev redistribution terms, including attribution, modified-version notice and non-endorsement; `ru_RU-license.txt` |
| `danakt/russian-words/russian.txt` | MIT, Copyright 2020 Danakt Frost; `danakt-license.txt` |

All full notices are shipped in `app/src/main/assets/vocabulary/` and referenced by
`NOTICE` and the app's third-party attribution screen. The English data's applicable
license comes from its **specific README**, not the unrelated directory-level GPL file.
The Hunspell engine itself is not linked or redistributed. No Hex source was copied.

Pinned input counts: 121,272 expanded English forms, 1,436,553 expanded Russian forms,
and 1,532,628 Danakt source rows (the upstream README still says 1,531,464).
Deduplicated union: **1,650,180 words**, 41,062,291 bytes; gzip: 8,925,531 bytes.

## Reproduce without network

```sh
python3 tools/vocabulary/build.py
```

Requires only Python's standard library. `--fetch` is an explicit maintainer-only option
for acquiring missing pinned source snapshots. Ordinary Gradle builds and app execution
do **not** download dictionary data. To update a source deliberately, review the new
license, change its revision, inspect its hash and regenerate the manifest/index.

The asset is installed into a content-addressed file in `noBackupFilesDir`, verified
against its manifest, atomically renamed and mapped read-only. Android's asset packager
expands a `.gz` asset and drops the suffix, so the APK contains `vocabulary/words.lex`
(41 MB, deflated to ~9 MB inside the APK); the loader reads that and still accepts a
`words.lex.gz` archive if a build keeps one. After a verified install, a marker file makes
a cold start a stat instead of another 41 MB hash. This avoids a 1.65M-entry
Kotlin hash table. One installation is shared by all requests. Preparation is off the UI
thread; existing model preload can prepare it before use without blocking the microphone.
Failure to load the index preserves transcription and personal vocabulary functionality.

## Policy and pipeline

Existing ASR (including Parakeet) -> personal exact aliases + ranked spelling candidates
-> existing optional Qwen cleanup -> deterministic numbers -> lexical safety guard -> text.
No streaming path, model replacement, model download, model-selection migration or extra
inference is introduced. Existing user-configured find/replace mappings still run at the
final insertion stage, as before; these are explicit user transformations outside this guard.

* **Personal vocabulary**, under **Dictate -> Cleanup**, is a multiline editor using the
  existing preference/backup architecture: `Preferred spelling | alias one; alias two`.
  A term without aliases is protected and keeps its preferred casing. Up to 500 terms,
  16 aliases per term, 96 characters and eight words per expression. Save validates
  duplicates, conflicts, quantity/negation changes and limits; Cancel discards edits.
  Explicit preferred terms also take priority over number normalization and filler deletion
  (for example, a product named `One`).
  Delete a line to remove an entry. No training and no silent migration from old prompts.
* Exact, longest-span personal aliases authorize replacements; aliases do not cascade.
  They are explicit instructions and can be linguistically ambiguous, so users should
  select them carefully. They do not automatically generate inflections: list a preferred
  inflected form and its alternatives when needed.
* Candidate ranking uses one-edit insertion/deletion/substitution/transposition neighbors,
  bilingual phonetic keys, existing English typing-frequency data, the existing compact
  `NgramIndex` for left/right English bigram context, and exact repetition in the current
  transcript. Work is bounded to eight unique unknown words of 4–24 letters.
  Russian morphology is recognized by indexed **surface forms**, not by stripping endings.
  Russian corpus frequencies/bigrams are reused **only if already installed** through the
  existing typing dictionaries; otherwise Russian ranking uses phonetics/edit distance and
  transcript repetition. No downloads are triggered. These immutable corpus snapshots refresh
  at app-process restart, while personal vocabulary edits apply to the very next request.
* Fuzzy candidates are available through `VocabularyAnalysis`, but are **not automatically
  inserted or added to the Qwen prompt**. Unknownness, phonetics and frequency alone cannot
  distinguish an ASR mistake from a new slang term/name. Only exact personal alternatives
  meet the automatic-substitution threshold. This deliberately favors precision over recall;
  it is not unrestricted contextual ASR rescoring or an automatic grammar corrector.
* Slang roots (кринж, вайб, рофл, имб, etc.) suppress candidate generation across inflections.
  **All** other unknown terms still remain unchanged. No forced translation/transliteration.
* The guard rejects reordered/new words, deleted negations/quantities, altered acronyms,
  names/technical spellings and slang substitutions. It allows punctuation, capitalization,
  known hesitation sounds and immediate nonnumeric/nonnegative repetition removal. A rejection
  retains the pre-Qwen text. Thus some legitimate grammatical corrections will also be rejected.
  It is a lexical safeguard, **not proof that punctuation cannot change meaning**.
* Number normalization has its own visible switch and works with Qwen off. It recognizes
  strictly composed English/Russian cardinals/scales, negative values, digit-by-digit decimals,
  Russian целых + десятых/сотых/тысячных, explicit percentages, currency/measurement amounts,
  supported ordinals, day + named month and cue + hour + tens/minutes.
  Examples: `twenty five percent` -> `25%`; `ноль целых пять сотых` -> `0,05`;
  `в девять сорок пять` -> `в 9:45`; `пятое мая` -> `5 мая`.
  Currency names stay as spoken; no guessed currency symbol, year, locale date order, AM/PM,
  time zone, telephone grouping or arithmetic. Isolated ambiguous numbers/ordinals, unsupported
  fractions, malformed sequences, numeric identifiers, quotes and URLs are retained.
* The final guard compares normalized output with deterministic normalization of the safe
  pre-Qwen source. Exceptions preserve safe source text; cancellation propagates. Inputs over
  100,000 characters bypass refinement to bound work.

## Tests and benchmarks

```sh
./gradlew :lib:dictate-core:test :app:testDebugUnitTest
```

Core tests include English, Russian, mixed scripts, slang inflections, names, aliases,
malformed preferences, quantities, currency/percent changes, cancellation, index corruption,
packaged dictionary hashes/membership and conservative abstention.

`PackagedVocabularyTest` runs against the **actual packaged index**, producing
`lib/dictate-core/build/reports/vocabulary-benchmark.json`: 20 warmups then 100 samples per
workload, including eight unknown words and a 5,000-character transcript. Setup includes
decompression, checksum verification, mapping and validation. Host JVM results are **not**
phone latency or end-to-end ASR/Qwen benchmarks; the cleanup callback is an identity stub.

`VocabularyBenchmarkTest` is a compiled Android instrumentation benchmark for explicit,
user-authorized device testing. It acquires the real bundled index, performs 50 warmups and
500 refinement runs, checks output, and logs timing only under `VocabularyBenchmark`.
Do not infer Honor Magic7 Pro performance without running it on that device. No phone
installation, app launch or device benchmark is part of this implementation task.
