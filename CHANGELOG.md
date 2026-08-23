# Changelog

## 0.8.4

- Added exact HQ ECDC frame-offset calculation for static URL seeking.
- Static playback and seeking now use HTTP byte ranges instead of downloading
  the complete file or scanning from byte zero.
- Reduced EDGE startup to a bitrate-aware buffer of roughly three to six
  seconds while retaining up to 512 KiB of bounded download-ahead data.
- Made seek cancellation close the previous HTTP range immediately instead of
  waiting for its read timeout, and reused the validated ECDC header in memory.
- Started the new HTTP range concurrently with ExecuTorch decoder initialization
  so connection and initial buffering no longer wait for model setup.
- Started live manifest polling and segment prefetching before decoder
  initialization, allowing the two-segment live buffer to fill concurrently.

## 0.8.3

- Added bounded background download-ahead buffering for static HTTPS ECDC
  files, with a bitrate-aware startup cushion and up to 512 KiB held in memory.
- Kept fetching compressed audio while the decoder is running so temporary
  network stalls are less likely to starve `AudioTrack`.
- Preserved progressive playback: the complete remote file does not need to be
  downloaded before playback starts.
- Reduced startup latency for low-bitrate files and inspect new URLs with a
  1 KiB HTTP Range request before playback.

## 0.8.2

- Added a background queue of up to three downloaded, integrity-verified live
  segments and require two segments before starting live playback.
- Cached manifest windows so already-published segments are consumed without a
  redundant manifest request before every segment.
- Allowed fully consumed HTTP responses to reuse keep-alive sockets, reducing
  repeated DNS lookups and TLS handshakes.
- Added live buffer-depth and explicit rebuffering status in the player UI.
- Linked the companion
  [EnCodec Live Streamer](https://github.com/HenryDelMal/encodec-live-streamer)
  project in the livestream documentation.

## 0.8.1

- Changed the example live manifest URL into a placeholder so the URL field is
  empty and ready for typing when no real URL has previously been saved.

## 0.8.0

- Added mainstream EnCodec Live v1 playback from HTTP or HTTPS `stream.json`
  manifests through the existing Open URL workflow.
- Added strict manifest/codec validation, bounded downloads, byte-length and
  SHA-256 segment verification, redirect handling, cache bypass, and retry
  backoff.
- Added live-edge buffering, ordered sequence tracking without duplicates,
  cleanup-window recovery, and discontinuity detection for sequence gaps,
  epoch changes, and explicit markers.
- Reused one ExecuTorch decoder and one persistent `AudioTrack` across normal
  live segment boundaries while preserving ECDC frame overlap/crossfading.
- Added LIVE status, sequence, bitrate, codebook display, Reconnect, Jump to
  live, Stop, and Disconnect controls.
- Integrated live playback with the existing media session, notification,
  foreground service, CPU wake lock, and Wi-Fi lock.
- Preserved local files, finite HTTPS playback, playlists, persistence,
  seeking, shuffle, repeat, and background playback behavior.

The supported live codec remains HQ `encodec_48khz`, ECDC v0, 48 kHz stereo,
2/4/8/16 codebooks, with language-model entropy coding disabled.
