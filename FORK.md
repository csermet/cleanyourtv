# This fork: `repo-helper`

The [cleanyourtv](https://github.com/Gathin23/cleanyourtv) launcher as it runs on a TCL 55C728 (Google TV 11,
MediaTek, 3 GB of RAM), with a moving background, theme sounds and a single-row layout. Everything here is
opt-in through a theme's `config.json`: without these keys the launcher behaves like upstream's at the
commit this branch was last merged with.

**No internet permission.** Pictures, videos and sounds are files you push to the TV; nothing is fetched.
The APK asks for the same permissions as upstream's plus `WRITE_SETTINGS`, used only by night mode's
earlier screensaver and only once you grant it over ADB.

**Tested on:** TCL 55C728 (Smart TV Pro, `G05_4K_GB`), Google TV 11, build AR2101, firmware
V8-T615T03-LF1V618, wired, with a TCL P735W soundbar on HDMI ARC. Not tested on a 1 GB set: videos are
skipped below 2 GB of RAM, pictures and sounds still work.

## What it adds

- **Live background.** A looping clip from the theme's `videos/` folder behind Home, darkened behind the
  UI, with optional sparks or drifting dust over the picture or the video. Sound, full screen and "next
  clip" buttons join the top bar; a clip picker sits in Launcher settings. The player exists only while
  Home is on screen, so the app you open gets the hardware decoder.
- **Theme sounds.** `wake.ogg` when the TV comes on, `sleep.ogg` when it goes off, `launch.ogg` when an
  app opens. The wake sound can wait for an ARC soundbar to finish connecting (`wake_wait_arc`), which on
  this set takes 6 to 11 seconds after power-on, so the first syllable is not lost.
- **Single row.** One horizontal row of apps over the wallpaper instead of the dock and grid, with a
  band behind it, the app's name above it and the clock and date in a soft box at the top left.
- **Wallpapers.** A `wallpapers/` folder shuffles: the next picture (and clip) comes when the TV is
  turned on after an hour or more. Launcher settings has a picker with a full-screen preview.
- **Night mode.** Between `night_start` and `night_end` the wallpaper is darker and, if allowed, the
  screensaver comes sooner. The owner's own delay is put back in the morning.
- **Slideshow screensaver.** With a `wallpapers/` folder, the launcher's screensaver shows them one
  after another (three minutes each, a slow cross-fade and push-in), under the clock.
- **Tile menu.** Hold OK on a tile to move it left or right, hide it or open App info.

## Theme keys

The live background and sound keys are in [`themes/README.md`](themes/README.md) with the upstream ones.
The rest:

| Key | Type | Meaning |
|---|---|---|
| `single_row` | bool | One horizontal row instead of the dock and grid; `false` by default |
| `row_tiles` | 3 to 10 | Tiles across the screen in single_row, fractions allowed (`6.6` shows part of the next one); 0 follows `columns` |
| `tile_gap` | 0 to 40 | Space between tiles in dp; 0 keeps room for the full focus zoom |
| `title_size` | 20 to 60 | The app name above the row, in sp; 0 keeps the layout's size |
| `band_opacity` | 20 to 100 | The band behind the row and the top-bar pills, in percent |
| `top_buttons_size` | 60 to 100 | Size of the top-bar pills, in percent |
| `text_color` | `#RRGGBB` | Clock, date and title colour |
| `ambient_glow` | 0 to 100, or bool | Strength of the focused app's colour glow; `0` or `false` turns it off |
| `inputs_pill` | bool | `false` hides the Inputs pill; the remote's Input button still works |
| `open_from_tile` | bool | Apps open growing out of their tile |
| `parallax` | bool | The wallpaper drifts a little with the focus |
| `night_start`, `night_end` | `"HH:MM"` | Night mode window; it may cross midnight |
| `night_dim` | 0 to 90 | Extra darkening at night, in percent; 35 by default |
| `night_screensaver_min` | 0 to 60 | Screensaver delay at night, in minutes. Needs `appops set com.gathin.tvhome WRITE_SETTINGS allow` once; without it night mode only dims |

A theme close to this TV's, without its dock and hidden apps (`4099:none` silences the Prime Video button):

```json
{
  "single_row": true, "row_tiles": 6.6, "tile_gap": 12, "title_size": 40,
  "band_opacity": 75, "top_buttons_size": 76, "text_color": "#9B6FF0", "ambient_glow": 0,
  "show_network_pill": false, "inputs_pill": false, "open_from_tile": true,
  "live_background": "video_effect", "live_effect": "both",
  "night_start": "23:00", "night_end": "08:00", "night_dim": 35, "night_screensaver_min": 3,
  "sounds": true, "wake_wait_arc": true, "wake_delay_s": 3, "sound_preroll_ms": 600,
  "app_keys": ["4099:none", "4065:com.google.android.youtube.tv"],
  "settings_key_keycodes": [4077]
}
```

## Install

Build and install the launcher the upstream way (`TV_HOST=<ip> launcher/build-and-install.sh`), then
push the theme with `tools/theme.sh <theme folder>`. That sends `config.json`, `wallpaper.jpg` and
`wallpapers/`; sounds and clips go next to them by hand:

```sh
D=/sdcard/Android/data/com.gathin.tvhome/files
adb -s <ip>:5555 shell "mkdir -p $D/sounds $D/videos"
adb -s <ip>:5555 push mytheme/sounds/*.ogg $D/sounds/
adb -s <ip>:5555 push mytheme/videos/*.mp4 $D/videos/
```

Clips that play on most TVs' hardware decoder: 1080p H.264 with AAC sound, for example
`ffmpeg -i in.mkv -vf scale=-2:1080 -c:v libx264 -preset slow -crf 22 -c:a aac -b:a 128k out.mp4`.
On this set a video background costs about 44% of one CPU core while Home is idle, a picture under 1%.

## Upstream

Bugs in anything above belong here, not upstream. Fixes that make sense for everyone go to
[Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv) as small pull requests. Same MIT licence.
