# Dictate Keyboard — local fork

Local-focused build of the Dictate keyboard, forked from
[DevEmperor/DictateKeyboard](https://github.com/DevEmperor/DictateKeyboard), which is itself a
rebuild of Dictate on top of [FlorisBoard](https://github.com/florisboard/florisboard).

**Speech recognition and cleanup run on the device**, with no cloud inference or API keys.
An internet connection is needed to download optional models and language data.

## Why this build exists

This fork focuses on everyday English/Russian dictation on a phone, with accuracy and low
latency as priorities. Recognition and cleanup work offline after the selected models have
been downloaded, without a provider account, subscription or remote inference server.

The goal is to keep what the speaker said, including names, slang and mixed-language phrases.
Cleanup is optional and conservative. Unfamiliar words are not automatically treated as errors,
and personal vocabulary lets users specify preferred spellings without retraining a model.
Ambiguous number expressions stay as spoken.

The narrower scope keeps the settings focused on dictation and avoids extra rewriting passes.
Users choose their models, cleanup instruction and memory-retention period. This does not
guarantee error-free transcription or instant results; performance depends on the phone and
the selected models.

## What this fork does

- **On-device dictation** through sherpa-onnx: Parakeet, Whisper, Qwen3-ASR, SenseVoice, GigaAM,
  Dolphin and others, downloaded explicitly by the user.
- **Realtime and batch transcription**, floating-button dictation into any app, transcription
  history with optional retained audio, file/voice-note transcription from the share sheet.
- **Optional on-device cleanup** with llama.cpp and a Qwen3 checkpoint: one pass can remove
  hesitation sounds and correct punctuation and casing. A conservative guard may reject changes;
  cleanup failure retains the original transcript. Model output can still contain mistakes.
- **Visible cleanup prompt** — the model's default instruction is shown, and a personalized prompt
  can replace it (blank falls back to the default).
- **Bilingual vocabulary and number normalization** — English/Russian dictionaries, a personal
  vocabulary, ranked spelling candidates and deterministic spoken-number conversion. Fuzzy
  candidates are not applied automatically; explicit personal alternatives can be substituted.
  See [tools/vocabulary/README.md](tools/vocabulary/README.md).
- A full typing keyboard inherited from FlorisBoard: layouts, glide typing, autocorrect,
  suggestions, emoji, clipboard, themes.

## Differences from upstream

This Android build omits the following upstream workflows to keep its focus on local dictation:

- Cloud and self-hosted inference configuration, provider API keys, and Dictate Cloud billing.
- Manual rewording, saved/queued/auto-apply prompts, the community prompt library, spoken
  editing commands and the separate AI auto-formatting pass.
- Typed snippet expansion from saved prompts.
- GIF search, Wear OS integration, the add-on store, update checks and promotional screens.

Some legacy source modules remain in the repository; their presence does not mean those
features are available in this Android build. Upstream remains the separate project for its
broader feature set.

Saved prompt data and legacy preference values stay on disk for backup/recovery but no longer
participate in dictation. Old prompt-menu links open **Dictate → Cleanup**.

## Building

Requires the Android SDK (compile SDK 36, min SDK 26) and JDK 21.

```sh
ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :app:assembleRelease
```

Release signing reads an untracked `keystore.properties` at the repo root
(see [keystore.properties.template](keystore.properties.template)); without it the release APK is
unsigned. Tests:

```sh
ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :app:testDebugUnitTest :lib:dictate-core:test
```

The native cleanup library is built from a pinned llama.cpp commit by
[tools/local-cleanup/build-android.sh](tools/local-cleanup/build-android.sh), and the vocabulary
index is regenerated offline by [tools/vocabulary/build.py](tools/vocabulary/build.py).

## Privacy

- No network use for transcription, cleanup, vocabulary or number normalization.
- Optional models, dictionaries and translation data may require network downloads. Other apps
  receiving dictated text, or backup/sync services configured by the user, have their own policies.

## License

Apache License 2.0, as inherited from FlorisBoard. See [LICENSE](LICENSE) and
[NOTICE](NOTICE) for required attribution to FlorisBoard, the SCOWL/Ispell English dictionary,
the Lebedev Russian Hunspell dictionary, the Danakt Russian lexicon, llama.cpp, sherpa-onnx and
the model weights that can be downloaded on demand.
