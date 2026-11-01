# Dictate Keyboard — local fork

Local-focused build of the Dictate keyboard, forked from
[DevEmperor/DictateKeyboard](https://github.com/DevEmperor/DictateKeyboard), which is itself a
rebuild of Dictate on top of [FlorisBoard](https://github.com/florisboard/florisboard).

**Speech recognition and cleanup run on the device**, with no cloud inference or API keys.
An internet connection is needed to download optional models and language data.

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

## What was removed from upstream

The upstream README describes features this fork does not have. These were cut:

- Cloud inference and provider accounts (OpenAI, Gemini, Groq, Deepgram, Soniox, ElevenLabs,
  AssemblyAI, xAI, Anthropic, Mistral, OpenRouter, Azure, Scaleway, OVHcloud and others),
  self-hosted server configuration, wake-on-demand, and API-key handling.
- **Dictate Cloud** prepaid-credit backend and all billing/donation/promotional screens.
- Manual rewording: prompt chips, saved/queued/auto-apply prompts, the prompt library, the
  prompt panel and live prompts.
- Spoken editing commands (the trigger word) and the separate AI auto-formatting pass.
- Typed snippet expansion from saved prompts.
- GIF search (KLIPY), Wear OS tethering/keyboard, the add-on store and update checks.

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
