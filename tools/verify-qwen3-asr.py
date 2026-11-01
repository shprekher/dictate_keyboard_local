#!/usr/bin/env python3
"""CPU smoke decode with the pinned runtime: pip install sherpa-onnx==1.13.3 soundfile."""
import json
import pathlib
import sys
import time

import sherpa_onnx
import soundfile as sf

assert sherpa_onnx.__version__ == "1.13.3"
root = pathlib.Path(sys.argv[1])
recognizer = sherpa_onnx.OfflineRecognizer.from_qwen3_asr(
    conv_frontend=str(root / "conv_frontend.onnx"),
    encoder=str(root / "encoder.int8.onnx"),
    decoder=str(root / "decoder.int8.onnx"),
    tokenizer=str(root / "tokenizer"),
    num_threads=2, max_total_len=512, max_new_tokens=256, temperature=1e-6,
)
for filename, language in [("ru1.wav", ""), ("ru1.wav", "Russian"),
                            ("f1_noise.wav", ""), ("f1_noise.wav", "English")]:
    samples, rate = sf.read(root / "test_wavs" / filename, dtype="float32")
    if samples.ndim == 2:
        samples = samples.mean(axis=1)
    # The Android path segments at 15 s. Decode each piece independently, not as a batch.
    pieces = []
    started = time.monotonic()
    for offset in range(0, len(samples), rate * 15):
        stream = recognizer.create_stream()
        if language:
            stream.set_option("language", language)
        stream.accept_waveform(rate, samples[offset:offset + rate * 15])
        recognizer.decode_stream(stream)
        pieces.append(stream.result.text)
    text = " ".join(pieces)
    assert text.strip() and "<asr_text>" not in text and "<|im_" not in text, text
    assert any("а" <= ch.lower() <= "я" for ch in text) if filename == "ru1.wav" else "Charles" in text
    print(json.dumps(dict(file=filename, language=language or "auto", text=text,
                          seconds=round(time.monotonic() - started, 2)), ensure_ascii=False), flush=True)
