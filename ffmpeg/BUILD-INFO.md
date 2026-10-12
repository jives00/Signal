# FFmpeg build record

The FFmpeg in `androidTvApp/libs/media3-decoder-ffmpeg-1.11.1.aar` is licensed under the
GNU LGPL 2.1 or later. To rebuild it, run the "FFmpeg (LGPL)" workflow, or follow these steps.

- Media3: https://github.com/androidx/media tag `1.11.1` (Apache 2.0), module `libraries/decoder_ffmpeg`
- FFmpeg: https://git.ffmpeg.org/ffmpeg.git branch `release/6.0`, commit `b37bf81a7a9782438e4fe06a249329e279694494`
- NDK `26.3.11579264`, ANDROID_ABI `21`
- Script: Media3's `build_ffmpeg.sh`, unmodified, with the decoders: `"ac3 eac3 dca truehd mlp mp3 aac opus vorbis flac"`

Configure result:

```
#define FFMPEG_LICENSE "LGPL version 2.1 or later"
#define CONFIG_GPL 0
#define CONFIG_NONFREE 0
#define CONFIG_VERSION3 0
#define CONFIG_GPLV3 0
```
