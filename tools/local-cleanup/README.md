# Local cleanup JNI

CPU-only llama.cpp, pinned by `build-android.sh`. No HTTP client, server, cloud fallback or GPU backend is built.

## Cleanup-only workflow and prompt selection

`LocalCleanupPipeline` applies task checks, optional hesitation filtering, prompt construction and output
validation before a result can be committed. There is one automatic pass when Cleanup is enabled;
manual rewording, spoken editing commands, saved/queued/auto-apply prompts and the separate AI formatting
pass have been removed. Retired editing requests are rejected before inference for every checkpoint.
Saved prompt data and legacy preference values remain on disk but do not participate in dictation.
Model selection and the existing cleanup on/off preference are not reset.

**Dictate -> Cleanup** provides the toggle, the existing model picker and a default/personalized prompt
selector. The active prompt is visible and selectable. The selected model's default is also visible
while a personalized prompt is active. Personalized text replaces, rather than appends to, the default
only when explicitly selected and nonblank. Blank text falls back to that model's default. New cleanup
prompt preferences are independent of the retired rewording instruction, so old custom text is not
silently activated. The former prompt-panel keyboard action now opens cleanup settings.

Preloading and inference use the same resolved instruction. Changing that instruction changes the
native prefix, invalidating stale prefix state. Default prompts are unchanged. Personalized prompts
can increase token cost and reduce GEC accuracy; the UI warns that GEC was trained with its Russian
default. Personalized mode bypasses the default hesitation filter so it cannot contradict the user's
explicit instruction about preserving words.

With the model default selected, GEC's English hesitation pre-pass preserves quoted text, uppercase acronyms, word/compound boundaries
and paragraph breaks. Explicit non-English language choices and mixed-script text bypass it. No-match
input is returned exactly as supplied; only whitespace and punctuation adjacent to removed sounds are
repaired. Automatic language detection is not a semantic classifier, so ambiguous forms may remain.
If filtering leaves no words, the original input is retained without invoking inference, rather than
generating from an empty turn or allowing an empty result to overwrite a selection. Neither instruction
was lengthened by these changes.

`EnglishHesitationFilterTest`, `LocalCleanupPromptTest` and `LocalCleanupPipelineTest` cover these rules,
the public provider's pre-JNI rejections, and the controller's keep-original path. Pipeline integration tests substitute the
inference callback; they are not model-quality or physical-phone benchmarks.

## Resident model and fixed-prefix cache

Keyboard opening starts the selected speech-model preload, then the selected cleanup-model preload.
The cleanup preload maps the weights, allocates a 4096-token context and evaluates the fixed cleanup
instruction. The default generic prefix has **90 reusable tokens**, and the GEC default has **67**. The model selection and
shared 0–30 minute retention preference are read, not changed.

`LocalCleanupText.prefix(true)` ends immediately before the transcript. Native code tokenizes the complete
prompt on every request and verifies the reusable prefix against it. The final standalone prefix token
is left out of the cache to handle tokenizer boundary merges. No transcript or generated tokens are
eligible for reuse.

After every request, including errors and cancellation, sequence positions at or after the fixed prefix
are removed. Only the fixed instruction remains addressable in the KV cache. No state is saved to disk.
If partial sequence removal is unsupported, the cache is cleared and recomputed instead. Model-file
changes, model/thread/context switches and instruction changes invalidate reuse.

GEC's recurrent state cannot retain this prefix after a completed request in the tested configuration;
the safe partial-removal fallback clears it. Its weights stay resident, but a subsequent request must
rebuild the instruction unless another preload prepared it. No prefix snapshot optimization is added here.

Native access is serialized. Each request installs its own abort callback; the callback is detached before
the request can be released. Decode work is synchronized before KV removal. An aborted prefix is not
published, and the next request rebuilds it. Idle unloading frees both the model and prefix; stale timer
tasks are invalidated when work resumes. Android memory pressure also requests unloading.

## Checks

Cleanup-only release **150 / local9**: optimized release assembled successfully; 1,268 app tests passed
and 117 core tests remain passing. The signed APK passed v2/v3 verification and 16 KB ZIP alignment.
It was not installed or physically tested on the phone. These checks do not establish the fidelity of
an arbitrary personalized instruction, and no further 1.7B tuning was performed.

Run Android/Kotlin unit tests without assembling an APK:

```sh
./gradlew :app:testDebugUnitTest :lib:dictate-core:test
```

Build the Android library (not an APK):

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" bash tools/local-cleanup/build-android.sh
```

Run the exact JNI bridge on the host, with both already downloaded checkpoints:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" bash tools/local-cleanup/test-host.sh \
  /path/to/Qwen3-1.7B-Q4_K_M.gguf /path/to/Qwen3-0.6B-Q4_K_M.gguf
```

The harness compares cached/uncached cleanup outputs, checks English/Russian meaning, punctuation,
negation, dates and meaningful “like”, and exercises tokenizer boundaries, cancellation, timeouts,
partial-prefix recovery, context limits, prompt changes, manual edits, model switches and unload/reload.
It asserts that addressable KV positions equal the fixed-prefix length after success and failure.
Role-delimiter tests check cached/uncached equivalence, not general resistance to semantic prompt injection.

Log timings are host measurements, not predictions of phone speed. Android `DictateCleanup` logs contain
model IDs, timings and token counts, never prompt or transcript content:

- `loadMs`, `promptMs`, `generationMs`
- `promptTokens`, `outputTokens`, `reusedTokens`, `evaluatedTokens`, `prefixTokens`

The test-only `performance()` array additionally reports the addressable KV sequence length for isolation
assertions. Model-quality errors remain possible; the controller keeps the original transcript on cleanup
failure. Cold runs and the first run after unloading must still pay model load and prefix preparation costs.

## Verification on 2026-10-29

- App unit tests: **1,233 passed**; core unit tests: **117 passed**, no failures or skips.
- The native cache/safety harness passed on the 0.6B checkpoint before the final prompt shortening;
  the final 90-token prompt was not quality-benchmarked further, as requested.
- The 1.7B checkpoint was not revalidated after the compact-prompt change and may retain some Russian
  filler words. It is not being further tuned for this release. Manual edits remain on the uncached path.
- Android ARM64 library retains 16 KB load-segment alignment and only `libm`, `libdl` and `libc`
  shared dependencies.

The bundled tokenizer counts the current instruction prefix as **90 tokens**. Preparing it is part of
preload and is paid again after cache invalidation. Host measurements are not phone benchmarks or a
general accuracy evaluation.

The optimized release containing this native library was assembled, signed, verified and installed over
the previous local build as version **147 / `6.4.0-local6`**. Physical phone speed and cleanup quality
remain for the user to test.
