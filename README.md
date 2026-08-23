# EnCodec Android Player

An experimental Android player for HQ Meta EnCodec `.ecdc` files and EnCodec
Live v1 streams. It decodes the neural audio stream on the device with
ExecuTorch and sends the resulting PCM audio to Android's `AudioTrack`.

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

This player deliberately supports only the official HQ EnCodec format:

| Feature | Support |
| --- | --- |
| Model identifier | `encodec_48khz` |
| Audio | 48 kHz stereo |
| Nominal bitrates | 3, 6, 12, and 24 kbps |
| ECDC container version | Version 0 |
| Language-model entropy coding | No; encode without `--lm` |
| 24 kHz mono/non-HQ EnCodec | No |
| EnCodec Live protocol | `encodec-live-v1`, version 1 |

The app recognizes 24 kHz non-HQ files but rejects them with a clear message.
On-device testing showed that the non-HQ decoder could not maintain real-time
playback reliably, even on a high-end phone, so its model and playback path were
removed.

## Features

- Local `.ecdc` file picker.
- Persistent playlist with play, pause, stop, previous, next, and seeking.
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
- Static HTTPS files use exact ECDC frame byte ranges and bounded background
  download-ahead buffering, so playback and seeking do not require a complete
  download or a scan from byte zero.
- Adding a static URL uses a 1 KiB HTTP Range request for ECDC inspection rather
  than opening and abandoning a full-file response.
- HTTPS-to-HTTP redirects are accepted. The app therefore enables Android's
  global cleartext-traffic setting; do not use untrusted stream addresses.
- Native EnCodec Live v1 playback from rolling `stream.json` manifests over
  HTTP or HTTPS.
- Two verified segments are buffered before live playback starts, with up to
  three segments prefetched in the background to absorb network jitter.
- Live manifest windows are consumed locally and fully read HTTP responses
  reuse keep-alive connections, reducing repeated DNS and TLS work.
- Live-edge startup, manifest polling, ordered sequence playback, cleanup-window
  recovery, explicit discontinuity handling, reconnect, and Jump to live.
- Manifest/segment size limits plus ECDC header, byte-length, and SHA-256
  verification before a live segment is decoded.
- One ExecuTorch decoder and one `AudioTrack` are retained across normal live
  segment boundaries.

Static URL seeking requires an HTTP server that honors byte-range requests.

## Playing an EnCodec livestream

Choose **Open URL**, enter a manifest address such as:

```text
https://example.com/stream/stream.json
```

Then choose **Open livestream**. HTTP is also accepted for trusted local-network
servers. The app validates EnCodec Live v1 and begins approximately two
segments behind the live edge after buffering two verified segments. Live mode
displays connection status, queued segment count, sequence, bitrate, and
codebooks. Seeking, previous/next, shuffle, and repeat are disabled
until **Disconnect** returns the app to normal playlist mode.

The last live URL is remembered, but playback never starts automatically when
the app launches. The server must publish complete, independent ECDC v0 files
using the `encodec_48khz` model, 48 kHz stereo, 2/4/8/16 codebooks, and no
language-model entropy coding.

For a compatible encoder, rolling-manifest generator, and server setup, see the
[EnCodec Live Streamer](https://github.com/HenryDelMal/encodec-live-streamer)
project.

## Upstream projects

The codec design, reference implementation, model architecture, and model
weights come from Meta/Facebook Research's official
[EnCodec repository](https://github.com/facebookresearch/encodec). The upstream
project describes EnCodec as supporting 24 kHz mono and 48 kHz stereo, but it
does not officially support Android. This repository implements its own
experimental Android decoder integration and intentionally exposes only the HQ
48 kHz stereo variant.

Neural inference runs through PyTorch's
[ExecuTorch](https://github.com/pytorch/executorch) Android runtime with the
XNNPACK CPU backend.

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
core/decoder/    ExecuTorch model loading and EnCodec PCM decoding
core/playback/   Persistent AudioTrack output, crossfading, finite and live playback
tools/           Python model-export utility
```

The Android app does not contain the EnCodec encoder. An `.ecdc` file already
contains quantized codebook indices, so the exported mobile graph includes the
codebook reconstruction and neural waveform decoder only.

## Requirements

- Android Studio with Android SDK 35 installed.
- JDK 17.
- Android 8.0/API 26 or newer.
- An `arm64-v8a` or `x86_64` Android device.
- Python 3.10, 3.11, or 3.12 to generate the decoder model.
- Several gigabytes of free disk space for PyTorch, ExecuTorch, downloaded model
  data, Gradle dependencies, and build output.

The Gradle wrapper is included and downloads Gradle 8.9 automatically.

## Compile from source

### 1. Clone the repository

```bash
git clone https://github.com/HenryDelMal/Android-encodec-player.git
cd Android-encodec-player
```

### 2. Generate the HQ decoder model

The generated `.pte` model is approximately 39 MB and is intentionally excluded
from Git by `.gitignore`. It must exist before the APK is built.

On macOS or Linux:

```bash
python3.11 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -r tools/requirements-export.txt
python tools/export_decoder.py \
  --variant 48khz \
  --output app/src/main/assets/encodec_48khz_decoder.pte
```

On Windows PowerShell, activate the environment with:

```powershell
py -3.11 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install -r tools/requirements-export.txt
python tools/export_decoder.py --variant 48khz --output app/src/main/assets/encodec_48khz_decoder.pte
```

The exporter downloads Meta's official EnCodec checkpoint, exports the fixed HQ
decoder graph, lowers supported operations to XNNPACK, and checks ExecuTorch
output against PyTorch eager output. Exporting can take several minutes and may
use substantial memory.

After a successful export, this file must exist:

```text
app/src/main/assets/encodec_48khz_decoder.pte
```

### 3. Build with Android Studio

1. Open the repository folder in Android Studio.
2. Select JDK 17 for the Gradle JVM.
3. Allow Gradle to install/synchronize the required Android SDK 35 components.
4. Confirm that the generated `.pte` file is present in `app/src/main/assets/`.
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

Use Meta's EnCodec command-line encoder with the 48 kHz HQ model and language
model disabled. The exact command-line options depend on the upstream EnCodec
version; consult the official
[EnCodec usage documentation](https://github.com/facebookresearch/encodec#usage).

The resulting file must identify its model as `encodec_48khz`, contain stereo
audio metadata, and have `lm=false`. Files produced by the 24 kHz model are not
supported by this app.

## Known limitations

- HQ EnCodec only; no 24 kHz non-HQ decoder.
- No language-model entropy-coded streams.
- CPU/XNNPACK inference only; no guaranteed GPU or NPU acceleration.
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
- Universal debug builds are large because they include the neural model and
  native ExecuTorch libraries for two Android ABIs.

## Contributing

Issues and pull requests are welcome, especially when accompanied by a small
non-copyrighted test file, Android version, phone model, and reproducible steps.
Please do not report upstream EnCodec or ExecuTorch problems as if this were an
official Meta/PyTorch application.
