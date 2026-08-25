# EnCodec Android Player

An experimental Android player for Meta EnCodec `.ecdc` files and EnCodec Live
v1 streams. It decodes the neural audio stream on the device with a lightweight
C++/Eigen runtime and sends the resulting PCM audio to Android's `AudioTrack`.

> [!CAUTION]
> **This project is AI slop.** Most of its architecture, implementation,
> debugging, documentation, and build setup were produced through iterative AI
> assistance. It has received practical testing, but it has not received a
> professional security review, a comprehensive device-compatibility audit, or
> the level of maintenance expected from production media software. Read the
> code, expect rough edges, and use it at your own risk.

This is an independent experiment. It is not an official Meta, Facebook,
PyTorch, or EnCodec application and is not endorsed by those projects.

## Supported format

Static files support both official EnCodec model variants:

| Feature | Support |
| --- | --- |
| Model identifier | `encodec_24khz` or `encodec_48khz` |
| Audio | 24 kHz mono or 48 kHz stereo |
| Nominal bitrates | Determined by the valid ECDC codebook count |
| ECDC container version | Version 0 |
| Language-model entropy coding | No; encode without `--lm` |
| 24 kHz mono/non-HQ EnCodec | Yes, automatically detected |
| EnCodec Live protocol | `encodec-live-v1`, version 1 |

EnCodec Live v1 supports both 24 kHz mono and 48 kHz stereo manifests.
Language-model entropy coding is not supported for either variant.

## Features

- Local `.ecdc` file picker.
- Persistent playlist with play, pause, stop, previous, next, and seeking.
- Separate persistent saved-livestream library with per-entry removal and a
  Delete all control.
- Automatic playback when the first item is added and automatic advancement
  when a track ends.
- Shuffle and loop modes for one track or the whole playlist.
- Per-track removal and a Delete all control.
- Foreground playback with CPU and Wi-Fi wake locks for reliable playback while
  the screen is locked.
- Automatic retry for temporary DNS and connection failures when opening a
  remote stream.
- Android media-session and notification controls.
- Adaptive `AudioTrack` creation with a 16-bit PCM fallback for Android
  emulators and restrictive audio devices.
- Legacy minimum-buffer PCM16 output on Android 8.0/8.1 and API 27 AVDs.
- Per-file duration, codebook count, sample rate, and nominal bitrate.
- Static HTTPS files use bounded background download-ahead buffering and direct
  byte-range seeking. A 24 kHz seek also fetches the preceding code chunk to
  preserve the causal decoder warm-up context.
- Adding a static URL uses a 1 KiB HTTP Range request for ECDC inspection rather
  than opening and abandoning a full-file response.
- HTTPS-to-HTTP redirects are accepted. The app therefore enables Android's
  global cleartext-traffic setting; do not use untrusted stream addresses.
- Native EnCodec Live v1 playback from rolling `stream.json` manifests over
  HTTP or HTTPS.
- Playback starts after the first verified live segment. Further segments are
  downloaded into a bounded background queue while that segment plays, and the
  rebuffer target grows after actual rebuffer events to absorb poor-network
  jitter.
- Live manifest windows are consumed locally and fully read HTTP responses
  reuse keep-alive connections, reducing repeated DNS and TLS work.
- Live-edge startup, manifest polling, ordered sequence playback, cleanup-window
  recovery, explicit discontinuity handling, reconnect, and Jump to live.
- Manifest/segment size limits plus ECDC header, byte-length, and SHA-256
  verification before a live segment is decoded.
- One C++ decoder and one `AudioTrack` are retained across normal live
  segment boundaries.

Static URL seeking requires an HTTP server that honors byte-range requests.

## Playing an EnCodec livestream

Choose **Open URL**, enter a manifest address such as:

```text
https://example.com/stream/stream.json
```

Then choose **Open**. HTTP is also accepted for trusted local-network servers.
The app validates EnCodec Live v1, saves the address in the Livestreams menu,
and starts behind the protected live edge after downloading its first verified
segment. Live mode displays connection status, queued segment count, sequence,
bitrate, and codebooks. Seeking, previous/next, shuffle, and repeat are disabled
until **Disconnect** returns the app to normal playlist mode.

If the manifest includes an optional top-level `title` string, the app uses it
for Now Playing, Android media controls, and the saved livestream name.

Saved live URLs are remembered, but playback never starts automatically when
the app launches. The server must publish complete, independent ECDC v0 files
using the `encodec_24khz` mono or `encodec_48khz` stereo model, a codebook count
valid for that model, and no language-model entropy coding.

For a compatible encoder, rolling-manifest generator, and server setup, see the
[EnCodec Live Streamer](https://github.com/HenryDelMal/encodec-live-streamer)
project.

## Upstream projects

The codec design, reference implementation, model architecture, and model
weights come from Meta/Facebook Research's official
[EnCodec repository](https://github.com/facebookresearch/encodec). The upstream
project describes EnCodec as supporting 24 kHz mono and 48 kHz stereo, but it
does not officially support Android. This repository implements its own
experimental Android decoder integration.

Neural inference uses the C++/Eigen implementation derived from
[pfeatherstone/encodec.cpp](https://github.com/pfeatherstone/encodec.cpp) and the
dual-model fork at
[HenryDelMal/encodec.cpp](https://github.com/HenryDelMal/encodec.cpp). The
Android build uses a single native inference worker and ARM vectorization where
the target CPU supports it; it does not require a mobile ML runtime.

The EnCodec paper is
[High Fidelity Neural Audio Compression](https://arxiv.org/abs/2210.13438) by
Alexandre Défossez, Jade Copet, Gabriel Synnaeve, and Yossi Adi.

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the included EnCodec
notice. EnCodec is distributed by Meta under the MIT License. Other dependencies
retain their respective licenses.

## Project structure

```text
app/             Compose UI, playlist, URLs, and Android media controls
core/ecdc/       ECDC header parser and raw 10-bit code unpacking
core/decoder/    C++/JNI model loading and EnCodec PCM decoding
core/playback/   Persistent AudioTrack output, crossfading, finite and live playback
tools/           Python model-export utility
```

The Android app does not contain the EnCodec encoder. An `.ecdc` file already
contains quantized codebook indices, so each runtime model contains only the
codebooks and neural waveform decoder weights.

## Requirements

- Android Studio with Android SDK 35 installed.
- JDK 17.
- Android 8.0/API 26 or newer.
- An `armeabi-v7a`, `arm64-v8a`, or `x86_64` Android device.
- Python 3.10, 3.11, or 3.12 to generate the decoder model.
- Android NDK 27 and CMake 3.22.1 (Android Studio can install both).
- Several gigabytes of free disk space for PyTorch, downloaded model data,
  Gradle dependencies, and build output.

The Gradle wrapper is included and downloads Gradle 8.9 automatically.

## Compile from source

### 1. Clone the repository

```bash
git clone https://github.com/HenryDelMal/Android-encodec-player.git
cd Android-encodec-player
```

### 2. Generate both C++ decoder models

The generated `.bin` models are intentionally excluded from Git. Both must
exist before the APK is built.

On macOS or Linux:

```bash
python3.11 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -r tools/requirements-export.txt
python tools/export_cpp_decoder_models.py \
  --sample-rate 24000 \
  --output app/src/main/assets/encodec-decoder-24khz-f32.bin
python tools/export_cpp_decoder_models.py \
  --sample-rate 48000 \
  --output app/src/main/assets/encodec-decoder-48khz-f32.bin
```

On Windows PowerShell, activate the environment with:

```powershell
py -3.11 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install -r tools/requirements-export.txt
python tools/export_cpp_decoder_models.py --sample-rate 24000 --output app/src/main/assets/encodec-decoder-24khz-f32.bin
python tools/export_cpp_decoder_models.py --sample-rate 48000 --output app/src/main/assets/encodec-decoder-48khz-f32.bin
```

The exporter downloads Meta's official checkpoints and writes the decoder and
RVQ weights in the format consumed by the C++ runtime. Exporting can take
several minutes and may use substantial memory.

After a successful export, these files must exist:

```text
app/src/main/assets/encodec-decoder-24khz-f32.bin
app/src/main/assets/encodec-decoder-48khz-f32.bin
```

### 3. Build with Android Studio

1. Open the repository folder in Android Studio.
2. Select JDK 17 for the Gradle JVM.
3. Allow Gradle to install/synchronize the required Android SDK 35 components.
4. Confirm that both generated `.bin` files are present in
   `app/src/main/assets/`.
5. Select the `app` configuration and run it on an Android device, or choose
   **Build > Build APK(s)**.

### 4. Build from the command line

On macOS or Linux:

```bash
./gradlew :core:ecdc:test :app:testDebugUnitTest assembleDebug
```

On Windows:

```powershell
.\gradlew.bat :core:ecdc:test :app:testDebugUnitTest assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The debug build is signed with Android's development certificate and can be
sideloaded for testing. It is not a Play Store release build.

## Encoding compatible files

Use Meta's EnCodec command-line encoder with language-model coding disabled.
The source audio and encoder options select either the 24 kHz mono or 48 kHz
stereo model. Consult the official
[EnCodec usage documentation](https://github.com/facebookresearch/encodec#usage).

The resulting file must identify its model as `encodec_24khz` or
`encodec_48khz` and have `lm=false`.

## Known limitations

- No language-model entropy-coded streams.
- CPU Eigen/NEON inference only; no GPU or NPU acceleration.
- Emulator decoding speed depends heavily on the host CPU and virtualization
  settings, even when its audio output is compatible.
- No gapless-playback guarantee.
- Remote streams have no persistent cache or HTTP Range index.
- Live playback uses a custom EnCodec protocol, not standard HLS; ordinary HLS
  clients cannot play its manifests.
- A connection failure during an individual segment download is retried, but a
  server must retain segments long enough for cleanup-window recovery.
- Global cleartext traffic is enabled to permit accepted HTTPS-to-HTTP
  redirects.
- Universal debug builds are large because they include both neural decoder
  models and native libraries for three Android ABIs.

## Contributing

Issues and pull requests are welcome, especially when accompanied by a small
non-copyrighted test file, Android version, phone model, and reproducible steps.
Please do not report upstream EnCodec or `encodec.cpp` problems as if this were
an official Meta application.
