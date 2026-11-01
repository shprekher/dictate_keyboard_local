# Dictate custom APK build

## Current cleanup-only source (not installed)

The current source removes the rewording/prompt-library workflow from keyboard, overlay and import
menus. Dictation runs only the optional automatic cleanup pass. Old auto-apply, formatting and spoken
command settings are no longer executed; saved prompt data is retained for backup/recovery.

**Dictate -> Cleanup** now contains the on/off control, access to the existing model picker, and a
default/personalized cleanup prompt selector. The active instruction and model default are visible.
Personalized text is used only when selected and nonblank; an empty field falls back to the model
default. The defaults remain unchanged (English for generic Qwen3, Russian for Qwen3.5 GEC). Preload
and inference resolve the same instruction. No model is selected or downloaded automatically.

The optimized, signed build **150 / local9** is ready in `deliverables`, with 1,268 app tests passing,
117 core tests remaining passing, v2/v3 signature verification and 16 KB ZIP alignment checks passed.
It has **not been installed**. The last installed APK is build 149 / local8.
Release notes below are historical and do not describe this new settings UI.

## Current source work (6.4.0-local3, installed)

The working tree is ahead of the installed v143 APK described below. Kotlin/unit tests now cover the
local-only refactor, model selection compatibility and the fixed-prefix cleanup cache. Keyboard opening
preloads the selected speech model first, then the selected cleanup model and its fixed instruction.
Only instruction KV is reused; each transcript and generated output is removed from reusable KV after
the request. Model choices and the shared 0–30 minute retention setting are not migrated or reset.

Arbitrary editing prompts remain uncached to preserve their existing full-prompt evaluation behavior.
See [`tools/local-cleanup/README.md`](tools/local-cleanup/README.md) for the cache design and reproducible
JNI regression tests. The optimized release APK was assembled, signed, verified and installed over v143 on
2026-10-29. Phone performance remains unmeasured for these changes and is left to the user. The older
release notes below describe v143, not the installed local3 build.

Current checks: **1,233 app tests + 117 core tests passed**, both checkpoints passed the native prefix
cache/safety harness, and the rebuilt ARM64 library retains 16 KB alignment. Host warm-cleanup medians
were **0.96 → 0.24 s** for 0.6B and **2.39 → 0.48 s** for 1.7B; these are not HONOR measurements.
Installed release: **version code 144, version name 6.4.0-local3**, signed with the same local key as
v143. Cold app launch succeeded; physical speech/cleanup testing remains to be done by the user.

## Historical installed build

Based on DictateKeyboard commit `39f33c96`. This build adds three optional features without
changing defaults, the keyboard, or any existing recognition engine:

- **Qwen3-ASR 0.6B INT8 (experimental)** — offline English/Russian speech recognition.
- **Whisper Large-v3-Turbo** — optional large Whisper model in the existing on-device picker.
- **On-device cleanup (offline)** — optional English/Russian text post-processing with
  Qwen3 1.7B Q4_K_M through llama.cpp.

FUTO remains the default keyboard. Existing cloud providers and cleanup options are kept.

## Release status

Version code **143**, version name **6.4.0-local2**, built and installed over USB on the
HONOR PTP-N49 on 2026-10-29. Android switched the default IME to Dictate during the
update; FUTO was selected again and is the current default. Dictate remains enabled as an
available keyboard. Model downloads and physical-phone speech/cleanup testing are left to
the user.

## Install

1. Install the supplied locally signed **release** APK (`net.devemperor.dictate`,
   version code 143). It is not signed by the original developer: do not uninstall an
   official installation with data you need, and do not expect Play Store updates to
   replace this custom build.
2. Enable this copy of Dictate in Android's keyboard settings. Dictate does not become
   the default IME unless you choose it.
3. Existing settings and downloaded models are kept when installing over the previous
   custom build signed with the same key.

## On-device cleanup

1. Open **Dictate → AI providers → On-device cleanup (offline)**.
2. Download **Qwen3 1.7B Q4_K_M (text cleanup)**. It is about **1.1 GB** and is not
   bundled in the APK.
3. Under **Active providers**, select it as the **Rewording** provider.
4. Enable **Rewording** in Dictate settings. A dictation then gets one cleanup pass for
   fillers, repetitions, false starts, grammar, punctuation and capitalization.

The cleanup model preserves facts, names, numbers, negations, language and tone. It does
not translate, summarize or answer questions in the transcript. If the model fails,
times out, returns empty text or exceeds its context, the original transcript is kept.
There is **no cloud fallback**. Cleanup runs on the CPU and may take several seconds.
When this provider is selected, the cloud auto-formatting and auto-apply prompt chain is
not run; manual rewording prompts still use the local model.

Resident-model and prefix-cache improvements are in the newer source work, not in installed v143.
That source uses the user's shared retention slider, not a hard-coded five-minute timer.
`adb logcat -s DictateCleanup` in a future APK will show `phase=cleanupWarm`, `phase=cleanupComplete`
and `phase=cleanupUnload`, with separate load, prompt-evaluation and generation timings.

## Qwen3-ASR

1. Open the on-device provider, show all models, and download
   **Qwen3-ASR 0.6B INT8 (experimental)**.
2. Select it and choose English or Russian as the transcription language for a hint, or
   auto-detect. Recognition runs after recording stops, not live.

The download is about **879 MB**, installed size about **988 MB**. It uses the existing
sherpa-onnx 1.13.3 CPU runtime, with no NPU/GPU/QNN acceleration. Audio is segmented
around speech pauses at about 15 seconds because of the model's 512-token shared context.

## Whisper Large-v3-Turbo

The Turbo entry uses the pre-converted sherpa-onnx ONNX INT8 export: a 675 MB encoder,
a 361 MB four-layer decoder and an 817 KB tokenizer, about **1.04 GB** total. It plugs into
the existing `LocalTranscriptionProvider` and `OfflineRecognizer.fromWhisper` path, so no
new inference runtime is used. It is optional and is not an onboarding/default model.

## Limits

- CPU only. Large models use substantial RAM, storage, battery and time.
- The cleanup native library is bundled for **arm64-v8a** only; cleanup requires an ARM64
  device. Qwen3-ASR and the other recognition engines keep their existing ABI support.
- Recognition and cleanup accuracy are not benchmarked; both can make mistakes.
- The cleanup model has a 4096-token context and a 1536-token output cap. Very long
  transcripts keep the original text instead of being truncated.
- Emulator/desktop results do not establish HONOR Magic7 Pro speed or stability.
  Physical-phone speech testing is left to the user.
- This is an optimized, non-debug, custom-signed sideload build. Future updates need the
  same key. Keep the local `DictateKeyboard-signing/` directory private and backed up.

## Checks performed

- Full `:app:testDebugUnitTest`: **1261 tests, 0 failures, 0 errors, 0 skipped**.
- `:app:assembleRelease`: **BUILD SUCCESSFUL** (minified release).
- Final APK: v2 and v3 signatures verified, `zipalign -c -P 16 4` passed, version code
  143 / version name `6.4.0-local2`.
- `libdictate_cleanup.so`: 4,670,584 bytes, 16 KB ELF segment alignment, only `libm`,
  `libdl` and `libc` dependencies.
- Host cleanup smoke test: English and Russian fillers/repetitions removed; question mark,
  negation/date, meaningful “like”, and prompt-injection text preserved; cancellation OK.

## Rebuild and checks

Use JDK 21 and the SDK/NDK/CMake versions pinned in the repository. Set `ANDROID_HOME`.

```sh
bash tools/fetch-sherpa-onnx.sh
bash tools/bergamot/build-android.sh
bash tools/local-cleanup/build-android.sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:assembleRelease
./gradlew :app:testDebugUnitTest
```

On this build machine macOS had a stale local HTTP proxy. The build used
`--no-configuration-cache --no-daemon` and the JVM proxy-bypass flags recorded in the
previous build notes.

The release output is unsigned unless `keystore.properties` is configured. This build was
signed after assembly with SDK `apksigner`, using a newly generated local RSA key; the
original developer's key is neither available nor needed for a fresh sideload.
Signature verification and `zipalign -c -P 16 4` are run on the final signed APK.

For the cleanup native smoke test, build the host library and run
`tools/local-cleanup/LocalCleanupNative.java` with the GGUF path and a prompt file; it
exercises the same JNI bridge, cancellation and Qwen3 ChatML prompt used on Android.

The ASR archives and extracted model/tokenizer files have pinned sizes and SHA-256
hashes. The downloader extracts only allowlisted regular files into staging, checks every
file, and uses the existing install/cancellation flow. Attribution is in `NOTICE` and the
in-app data attributions.
