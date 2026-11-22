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
- Rai: relinker JSON `output=63&forceUserAgent=raiplayappletv` (HLS, usually clear). YouTube entries are dropped from every list (`Lists.isYouTube`); a webcam falling
  back to YouTube tries the watch page `hlsManifestUrl`, else it is "not available". Twitch: GQL PlaybackAccessToken + usher.

## Code map (`app/src/main/java/com/mefistofelix/wltv/`)
- `MainActivity.kt` — two panel modes (channels / guide by programme), info banner with progress; player (Media3 ExoPlayer + OkHttp data source, per-stream headers and Widevine). Panel = settings column (left edge) + ONE list (right edge: `items` of `Header` dividers and `Row`s, most watched copies on top, folder = "source › group" on dividers and rows); search narrows it keeping the place (`sel`/`anchor`/`movedInResults`, Back resets);
  remote key routing in `dispatchKeyEvent` (Menu = search, long Menu = refresh, ⏪⏩ = folders in the list), watch-time tick.
- `Lists.kt` — sources, several on at once (prefs string set `sources`): `wltv:<Name>` (all.json), `world:<cc>`
  (ListWorldTv.bin → iptv-org M3U per country), `cam:<Country>` (ListSkyLineWebcam.bin), `radio:<CC>` (ListWorldRadio.bin +
  `worldradio/<CC>.bin`), `custom`. The .bin files on `www.worldtvlive.eu/world/files` are base64 of raw deflate.
- `Epg.kt` — WLTV XMLTV guide `epg-guide.com/wltv.xz` (8 MiB XZ dictionary), downloaded at most every 12 h, parsed
  with XmlPullParser keeping now-1h..now+30h; channels matched by tvg-id, then display name. Feeds rows, banner,
  guide mode, programme search and the channel icon map of `Logos`.
- `M3u.kt` — EXTINF parser; iptv-org `http-user-agent`/`http-referrer` and `#EXTVLCOPT` become a Kodi `url|Header=v`
  suffix. `Channel.key` = URL (stats key), `Channel.source` = list label (groups per source).
- `Stats.kt` — seconds watched per channel (prefs JSON), "Più guardati".
- `Logos.kt` — channel logos: list `tvg-logo`, else the guide icons from `Epg` (map saved in `files/epg_icons.json` for
  the next start) by tvg-id, name, or longest tvg-id prefix
  (TGR -> rai3); the next source is tried when one fails; else an initials tile. Images cached in `files/logos`.
- `Search.kt` — fuzzy matching of dictated names (Italian number words → digits).
- `Updater.kt` / `Relauncher.kt` — self-update as in FireBro: `firetv-apps/wltv.json` checked in `onResume` (2 min
  throttle), dialog, SHA-256 checked download, PackageInstaller session, reopen after our own update; Fire OS 6
  launcher tile refresh. Published from the dev PC as in FireBro: after `git push`, `bash scripts/release.sh` (waits for the Actions build,
  downloads it, runs `scripts/publish-update.sh` with the local gh login; no token in this public repo).
- `Http.kt` — OkHttp client with shared cookie jar, browser headers, `match` = scrapers.findSingleMatch.

## Device facts (from the sibling FireBro project)
- Fire TV apps get no speech recognizer: voice = dictation in the Fire TV keyboard, committed into the focused EditText.
  The keyboard does not close on Back by itself (`SearchField.onKeyPreIme`).
- Fire OS 6 launcher shows a grey tile for vector icons: icons are PNGs drawn by `tools/make_icons.ps1`.
- `app/debug.keystore` is a fixed key committed on purpose (same as FireBro) so builds update in place.
- Logs: `adb logcat -s WLTV ExoPlayerImplInternal`. Keys: `adb shell input keyevent MENU|DPAD_UP|...`.
