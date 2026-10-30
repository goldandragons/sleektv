# SleekTV

A native Android TV app for Jellyfin with a SleekFin-style look (black UI, red accent, Inter, floating header, full-width hero, full-page detail backdrops), and a companion Jellyfin plugin that lets you change the app's layout from your server's dashboard.

```
sleektv/
├── android/   Android TV app (Kotlin, Jetpack Compose for TV, Media3/ExoPlayer)
├── plugin/    Jellyfin 12.3+ plugin: settings page + /SleekTV/Layout endpoint
└── .github/workflows/build.yml   builds both on GitHub for free
```

## 1. Build it (no Android setup needed)

1. Create a new **private** repository on GitHub and upload this whole folder (drag and drop works on the repo's "uploading an existing file" page).
2. Open the repo's **Actions** tab. The **Build SleekTV** workflow runs on every push; click **Run workflow** to start it by hand.
3. When it finishes (about 5 minutes), open the run and download the two artifacts:
   - **SleekTV-apk** → `SleekTV.apk`
   - **SleekTV-plugin** → a folder named `SleekTV_0.1.0.0`

Prefer Android Studio? Open the `android` folder and use **Build → Build APK(s)**.

## 2. Install the plugin

1. Stop Jellyfin.
2. Copy the `SleekTV_0.1.0.0` folder into your plugins directory (`/config/data/plugins/` in Docker).
3. Start Jellyfin. Settings live at **Dashboard → Plugins → SleekTV**.

The plugin is built against Jellyfin 12.3 and doesn't need File Transformation.

## 3. Install the app on the TV box

Any of these work:

- **Downloader app:** upload `SleekTV.apk` somewhere with a direct link and open it in Downloader.
- **USB stick:** copy the APK over and open it with a file manager.
- **adb:** `adb connect <tv-ip>` then `adb install SleekTV.apk`.

You may need to allow "Install unknown apps" for whichever app you use to open the APK. Then open **SleekTV** and enter your server address (e.g. `http://192.168.1.50:8096`).

### Signing in

- **Quick Connect (easiest with a remote):** choose *Sign in with Quick Connect*. The TV shows a 6-digit code. On your phone or computer, open Jellyfin, go to your profile, tap **Quick Connect**, and enter the code. The TV signs in by itself within a few seconds. Quick Connect has to be enabled on the server (Dashboard → General → *Enable Quick Connect*).
- **Password:** type your username and password instead.

## Customizing

Change settings in the dashboard and press **Save**. The TV picks them up whenever it returns to the home screen, or right away from **Account → Reload layout**.

| Area | What you can change |
|---|---|
| Colors | Accent, background, card surface, text |
| Text and headers | Global text scale, hero title, row headers, detail title, header style (floating / solid / hidden) |
| Home | Hero on/off, hero height, rotate speed, which rows show and in what order, items per row |
| Cards | Width, portrait or landscape, corner radius, row and card spacing, focus zoom, titles, watched badges |
| Episodes | List / Grid / Row layout, grid columns, thumbnail width, season tabs or stacked, sort order, descriptions |
| Details | Cast row, "You may like" row |
| Player | Skip intros/recaps and credits: show a button, skip automatically, or off; how long the button stays up |

### Custom styles

The plugin's **Custom styles** box takes CSS-style rules that override everything above. The app is native, so this isn't real CSS: each property maps to one of the settings, and anything it doesn't recognize is ignored.

```css
:root    { accent: #1db954; background: #0b0b0b; font-scale: 1.1; }
header   { style: solid; }
hero     { height: 460; title-size: 52; rotate: 6s; }
section  { title-size: 24; spacing: 32; }
card     { width: 170; radius: 16; focus-scale: 1.12; shape: landscape; }
continue { shape: portrait; }
details  { title-size: 52; cast: hide; }
home     { rows: NextUp, ContinueWatching, Latest; latest-count: 30; }
episodes { layout: grid; columns: 5; seasons: stacked; order: desc; overview: hide; }
player   { skip-intro: auto; skip-credits: button; button-seconds: 15; }
```

## Seerr (Jellyseerr / Overseerr)

In the SleekTV plugin settings, fill in the **Seerr** section: the Seerr address as your Jellyfin server reaches it (e.g. `http://192.168.1.50:5055`) and the API key from *Seerr → Settings → General*. Save, then press **Test connection**.

Once that's set:

- **Search** gets a second tab, **Request (Seerr)**, that searches everything on TMDB.
- **Home** gets *Trending*, *Popular movies* and *Popular shows* rows with only titles you don't have yet. Move them with `Discover` in the home row list, or turn them off with the checkbox.
- Each title opens a page with **Request** (or **Request all seasons** for shows). Titles you already have show **Watch in Jellyfin**.

The API key never leaves your Jellyfin server; the plugin forwards requests to Seerr as the Jellyfin user who made them, so Seerr's permissions, quotas and auto-approve rules still apply. Each Jellyfin user needs a Seerr account (*Seerr → Users → Import Jellyfin Users*).

## Playback

The app direct-plays the original file when the box can decode it. If playback fails, it switches to a server transcode (H.264/AAC over HLS) at the same position. Progress is reported to Jellyfin, so Continue Watching and watched status stay in sync with your other clients.

### Skip intro and credits

If the [Intro Skipper](https://github.com/intro-skipper/intro-skipper) plugin is installed, the player reads its timestamps through Jellyfin's Media Segments API (with a fallback to Intro Skipper's own endpoint). During an intro, recap, preview or credits, a **Skip Intro** / **Skip Credits** pill slides in at the bottom right and takes focus, so one press of OK skips it. Set either one to skip automatically, or turn it off, under **Player** in the SleekTV plugin settings. Episodes Intro Skipper hasn't analyzed yet just play normally.

## Known limits of this first version

- No Live TV or music yet.
- Subtitles: embedded tracks work through the player's subtitle button; external subtitle files aren't loaded yet.
- The APK is a debug build (installable and fine for personal use, slightly larger than a release build).

## Credits

Visual style inspired by [SleekFin](https://github.com/varunaditya-plus/SleekFin). Uses the [Inter](https://rsms.me/inter/) typeface (SIL Open Font License, see `android/app/src/main/INTER_LICENSE.txt`).
