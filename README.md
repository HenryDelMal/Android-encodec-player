# EnCodec Android Player

An experimental Android player for HQ Meta EnCodec `.ecdc` files. It decodes
the neural audio stream on the device with ExecuTorch and sends the resulting
PCM audio to Android's `AudioTrack`.

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

The app recognizes 24 kHz non-HQ files but rejects them with a clear message.
On-device testing showed that the non-HQ decoder could not maintain real-time
playback reliably, even on a high-end phone, so its model and playback path were
removed.

## Features

- Local `.ecdc` file picker.
- Playlist with play, pause, stop, previous, next, and seeking.
- Android media-session and notification controls.
- Per-file duration, codebook count, sample rate, and nominal bitrate.
- Progressive playback from a direct HTTPS URL without downloading the entire
  file first.
- HTTPS-to-HTTP redirects are accepted. The app therefore enables Android's
  global cleartext-traffic setting; do not use untrusted stream addresses.
- Automatic playback of the next playlist item.

Remote seeking currently reconnects and scans the stream from the beginning.
HTTP Range-based seeking and a permanent download cache are not implemented.

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
core/playback/   AudioTrack output, crossfading, progress, and seeking
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

Replace the example URL with the repository address created in your GitHub
account.

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
./gradlew :core:ecdc:test assembleDebug
```

On Windows:

```powershell
.\gradlew.bat :core:ecdc:test assembleDebug
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
- No gapless-playback guarantee.
- Remote streams have no persistent cache or HTTP Range index.
- Global cleartext traffic is enabled to permit accepted HTTPS-to-HTTP
  redirects.
- Universal debug builds are large because they include the neural model and
  native ExecuTorch libraries for two Android ABIs.

## Contributing

Issues and pull requests are welcome, especially when accompanied by a small
non-copyrighted test file, Android version, phone model, and reproducible steps.
Please do not report upstream EnCodec or ExecuTorch problems as if this were an
official Meta/PyTorch application.
