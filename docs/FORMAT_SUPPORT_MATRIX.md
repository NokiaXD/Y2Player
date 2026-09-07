# Format support matrix

All advertised formats use the same FFmpeg decoder and AudioTrack lifecycle.
There is no platform-decoder fallback.

| Extension/container | Codec | Status |
| --- | --- | --- |
| `.mp3` | MPEG Layer III | Enabled |
| `.aac` | AAC/ADTS | Enabled |
| `.m4a`, `.m4b`, `.m4r` | AAC in MOV/MP4 | Enabled |
| `.m4a`, `.m4b`, `.m4r` | ALAC in MOV/MP4 | Enabled |
| `.flac` | FLAC | Enabled |
| `.wav`, `.wave` | Allowlisted integer/float PCM | Enabled |
| `.ogg`, `.oga` | Vorbis | Enabled |
| `.opus`, `.ogg`, `.oga` | Opus | Enabled |
| `.aif`, `.aiff`, `.aifc` | Allowlisted AIFF PCM | Enabled |
| WMA, APE, MP2, AMR, WavPack, AC-3, MKA | Various | Not enabled |
| DSF/DFF/native DSD | DSD | Not enabled and no verified output path |

The library scanner indexes only the enabled extension set. Metadata for every
indexed format comes from FFmpeg. The build also carries the ASF demuxer so the
same metadata API can inspect WMA containers, but WMA remains unadvertised and
unplayable because no WMA decoder is enabled.

Embedded chapter metadata in an M4B does not prevent indexing or playback. The
current audiobook model presents one physical file as one playable item; it does
not expose embedded chapter boundaries as separate chapter rows.

There is no runtime format probe: support is a build property checked against
the native configure allowlists by unit tests. Database migration version 9
clears old framework-specific playback failures and probe rows; version 10 drops
the obsolete probe table. Version 11 stores the unified FFmpeg metadata record
and schedules one refresh of existing library rows.

All formats decode through the same 44.1 kHz stereo float32 internal path.
Sources at other rates or layouts are converted by `libswresample`; application
gain, ReplayGain, balance, fades, ducking, and crossfade retain float precision.
The final AudioTrack output remains 44.1 kHz stereo PCM16 because that is the
only normal primary format verified in the stock Y2 audio policy. This is not a
claim of bit-perfect or high-rate hardware output.
