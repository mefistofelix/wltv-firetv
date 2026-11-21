# WLTV for Fire TV — agent notes

Live TV player for Fire TV that replaces Kodi + the World Live TV addon (`plugin.video.wltvhelper`, Kodi repo
https://worldlivetv.github.io/repo/, GitHub WorldLiveTV/WorldLiveTV.github.io). UI strings Italian, code English,
user docs `README.md` (Italian). Public repo, APK built by GitHub Actions on every push to `main` (release `build-N`,
N = run number = versionCode). No local JDK/SDK on the dev PC.

## Sources (same as the addon)
- List index: `https://tvchannels.worldtvlive.eu/tv/all.json`; lists usable without Kodi helpers are the ones with
  `Enabled` and tag `kodi`. List: `.../tv/<Name>/list.m3u8`. Default `_dtt_kodi` (Italian DTT). The non-kodi lists
  answer "stream is not enabled".
- Entries are plain URLs (Kodi `url|Header=v` syntax allowed) or `plugin://plugin.video.wltvhelper/play/<broadcaster>/<id>`
  (plus a few `plugin://plugin.video.twitch/?...channel_name=` and youtube ones).
- `Resolver.kt` ports `broadcaster/*.py` of addon 1.2.14 **with hotfix** `hotfix-1.2.14.zip` (overrides la7, discovery,
  sky, rai, rsi, pluto, saiuz, sportitalia, supertennis, telelombardia, tv2000, dailymotion, generics, restream and adds
  sam, wedotv, antennasud, sole24, gamberorosso, legaseriea). When the addon gets a new hotfix
  (`https://worldlivetv.github.io/repo/plugin.video.wltvhelper.hotfix/hotfix.json`), diff it and port the changes.
- Kodi LicenseKey `url|headers|R{SSM}|R` = `Stream.licenseUrl` + `licenseHeaders` (ExoPlayer posts the raw challenge).
- Rai: relinker JSON `output=63&forceUserAgent=raiplayappletv` (HLS, usually clear). YouTube: `hlsManifestUrl` from the
  watch page, else hand off to the YouTube app. Twitch: GQL PlaybackAccessToken + usher.

## Code map (`app/src/main/java/com/mefistofelix/wltv/`)
- `MainActivity.kt` — player (Media3 ExoPlayer + OkHttp data source, per-stream headers and Widevine), panel with
  groups/channels ListViews, search field, buttons, remote key routing in `dispatchKeyEvent`, watch-time tick.
- `Lists.kt` — index + list download and cache in `files/`, selected list, custom M3U URL.
- `M3u.kt` — EXTINF parser. `Channel.key` = URL (stats key).
- `Stats.kt` — seconds watched per channel (prefs JSON), "Più guardati".
- `Logos.kt` — channel logos: list `tvg-logo`, else icons of the WLTV EPG `http://epg-guide.com/wltv.xz` (XZ, only the
  `<channel>` head is read; map cached weekly in `files/epg_icons.json`) by tvg-id, name, or longest tvg-id prefix
  (TGR -> rai3); the next source is tried when one fails; else an initials tile. Images cached in `files/logos`.
- `Search.kt` — fuzzy matching of dictated names (Italian number words → digits).
- `Http.kt` — OkHttp client with shared cookie jar, browser headers, `match` = scrapers.findSingleMatch.

## Device facts (from the sibling FireBro project)
- Fire TV apps get no speech recognizer: voice = dictation in the Fire TV keyboard, committed into the focused EditText.
  The keyboard does not close on Back by itself (`SearchField.onKeyPreIme`).
- Fire OS 6 launcher shows a grey tile for vector icons: icons are PNGs drawn by `tools/make_icons.ps1`.
- `app/debug.keystore` is a fixed key committed on purpose (same as FireBro) so builds update in place.
- Logs: `adb logcat -s WLTV ExoPlayerImplInternal`. Keys: `adb shell input keyevent MENU|DPAD_UP|...`.
