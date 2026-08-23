# Changelog

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
