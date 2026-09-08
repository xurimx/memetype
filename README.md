# Memetype

[![build](https://github.com/xurimx/memetype/actions/workflows/build.yml/badge.svg)](https://github.com/xurimx/memetype/actions/workflows/build.yml)
[![release](https://github.com/xurimx/memetype/actions/workflows/release.yml/badge.svg)](https://github.com/xurimx/memetype/releases)

An Android keyboard that makes memes. Switch to it in any chat, pick a template, type the captions on its built-in keyboard, drag the text where you want it and tap **Send**: the finished picture lands in the conversation and your usual keyboard comes back.

## Features

- **Templates from several sources.** Imgflip (the 100 most popular templates), memegen.link (about 200 templates with ready-made text layouts), a bundled "Classic memes" pack in the FOSS build, installable template packs (`docs/PACK_FORMAT.md`), and your own pictures: import an image, place the text boxes once, reuse it forever.
- **A real editor.** Any number of text boxes per template; tap to select, drag to move, corner handles to resize, pinch to scale; add, delete or reset boxes. Each template remembers your layout.
- **History.** Reopen a template and your previous captions are one tap away (up to 30 per template).
- **Saves what you send** to `Pictures/Memetype` or a folder you choose, with an optional small watermark, plus a Save button for memes you do not send.
- **Works everywhere.** Apps that accept images get the PNG inserted directly; anywhere else it is copied to the clipboard.
- **Optional Imgflip sign-in** unlocks Imgflip's premium search. Credentials stay in the app's private storage and are excluded from backups.
- **No ads, no tracking, no analytics, no account of its own.** Memetype never records what you type in other apps.

Requires Android 9 or newer (minSdk 28, targetSdk 36).

## Using it

1. Open **Memetype** from the launcher: *Enable keyboard* takes you to the system keyboard list; *Select keyboard* opens the picker.
2. In any chat, tap the keyboard-switcher (globe) icon in the navigation bar and pick *Memetype*.
3. Tap a template, type (↵ moves to the next box; on the last box it sends), drag boxes if you like, then **Send ✓** or the Save icon.
4. The gear in the grid's top row opens Settings (save folder, watermark, history, sources); the puzzle icon opens Sources (switch sources on and off, import a pack zip or a picture, sign in to Imgflip).

## Build

Open the folder in Android Studio 2026.1 or newer and let it sync. Its bundled JDK 25 runs the build (Gradle 9.7.1 needs Java 17+). There is no separate Kotlin plugin: AGP 9 compiles Kotlin itself, and `jvmTarget` follows `compileOptions` (17). Dependencies are `core-ktx` and `recyclerview` only (no appcompat/material/compose; JSON is `org.json`).

From a terminal, with `JAVA_HOME` pointing at Studio's `jbr` folder and `ANDROID_HOME` at the SDK:

```
.\gradlew.bat :app:installFossDebug        # day-to-day
.\gradlew.bat :app:assembleFossRelease     # signed APK for GitHub / F-Droid
.\gradlew.bat :app:bundlePlayRelease       # .aab for Google Play
.\gradlew.bat :app:lintFossRelease :app:lintPlayRelease
```

`local.properties` (git-ignored) needs `sdk.dir=C\:/path/to/Android/Sdk`; escape the drive colon or lint fails with `PropertyEscape`.

### Flavours

Two product flavours of the same app (dimension `distribution`):

| Flavour | Bundled "Classic memes" pack | Donate link | For |
|---|---|---|---|
| `foss` (default) | yes (`app/src/foss/assets/templates`) | yes | GitHub releases, F-Droid |
| `play` | no — templates come from the online sources | no | Google Play (IP and Payments policies) |

### Signing

Release builds are minified (R8) and signed with the upload key described in `keystore.properties.example`: copy it to `keystore.properties` (git-ignored) and fill in the paths and passwords. Without that file, release builds are unsigned and debug builds are unaffected. Google Play re-signs the `.aab` with its own key (Play App Signing), so a sideloaded `foss` APK and the Play install conflict on one device; that is normal for open-source apps.

### Releases and CI

Two GitHub Actions workflows live in `.github/workflows/`, both started by hand from the Actions tab for now:

- **build** — compiles both debug flavours, lints both release variants, keeps the debug APKs as run artifacts.
- **release** — builds the `foss` release APK and the `play` bundle, publishes them as run artifacts (`memetype-<version>-foss.apk`, `memetype-<version>-play.aab`, `SHA256SUMS.txt`) and, when given a tag such as `v0.4`, creates the GitHub Release with the APK attached. Bump `versionCode` / `versionName` in `app/build.gradle.kts` first. Signing uses the repository secrets `UPLOAD_KEYSTORE_B64` (base64 of the upload keystore), `UPLOAD_STORE_PASSWORD`, `UPLOAD_KEY_ALIAS` and `UPLOAD_KEY_PASSWORD`; without them the APK is built unsigned and the release says so.

Downloads: https://github.com/xurimx/memetype/releases

### Regenerating the bundled pack

```
powershell -ExecutionPolicy Bypass -File tools\fetch-templates.ps1
```

Downloads the memegen.link repository and Imgflip's `get_memes`, resizes the curated 50 templates to ≤ 800 px JPEG, and writes `app/src/foss/assets/templates/templates.json` plus `app/src/main/assets/memegen_layouts.json` (text-box geometry for every memegen template, used by the online source). Edit the curated table at the top of the script to change the selection; ids that do not exist stop the script. Needs Windows PowerShell 5.1 with System.Drawing and network access.

## Project layout

```
app/src/main/
  AndroidManifest.xml               IME service, FileProvider, Settings / Sources / SourceLogin / ImportImage / Donate activities
  java/com/umo/memetype/
    MemeInputMethodService.kt       InputMethodService: panel host, render + save + deliver, history append, activity launches
    SettingsActivity.kt             Keyboard onboarding, Output (save, folder picker, watermark), History, Sources, About
    SourcesActivity.kt              enable/disable/remove sources, sign-in rows, import pack (.zip) or image
    SourceLoginActivity.kt          username/password or API key for one source: Test / Clear / Save
    ImportImageActivity.kt          place text boxes on an imported image (reuses MemeCanvasView)
    DonateActivity.kt               dialog with the donation link (foss flavour only)
    ui/KeyboardPanelView.kt         root: swaps grid <-> editor <-> text mode; PanelMode drives the height; in-panel messages
    ui/TextModeView.kt              plain QWERTY shown when the focused field belongs to Memetype itself (sign-in screen)
    ui/TemplateGridView.kt          top row (search + global action buttons), RecyclerView grid, category chips
    ui/MemeEditorView.kt            toolbar, canvas, box chips, history strip, mini keyboard; layout overrides; baked + live rendering
    ui/MemeCanvasView.kt            the interactive canvas: draws the baked bitmap + the selected box live; move/resize/pinch gestures
    ui/HistoryStripView.kt          thumbnails of previous memes for the open template
    ui/MiniKeyboardView.kt          Canvas-drawn QWERTY (letters/symbols, shift, caps-lock, repeat ⌫)
    ui/Rows.kt                      programmatic settings rows (header, text, button, switch)
    meme/TextBoxSpec.kt             TextBoxSpec (normalised geometry + style), TextBox (spec + text), DefaultLayouts
    meme/MemeTemplate.kt            MemeTemplate, ImageRef (Asset / LocalFile / Content / Remote), key = "source/id"
    meme/PackParser.kt              memekb-pack v1 → templates
    meme/MemeRenderer.kt            fit (StaticLayout, auto-shrink) + draw (stroke + fill, rotation) + watermark; PNG to cacheDir/memes
    meme/TemplateRepository.kt      aggregates enabled sources (local sync, remote async + onChanged); thumbnails LruCache; recents; provider search
    meme/Bitmaps.kt                 two-pass downsampled decode
    source/MemeSource.kt            the source interface + AuthScheme / Credentials (data only, no code loading)
    source/PackSource.kt            bundled assets pack or installed pack directory
    source/LocalSource.kt           "Mine": imported images, index in pack format
    source/PackInstaller.kt         zip validation + install/uninstall under files/packs
    source/ImgflipSource.kt         Imgflip get_memes (+ premium search_memes with a login)
    source/MemegenSource.kt         memegen.link /templates/ + assets/memegen_layouts.json geometry
    source/RemoteCache.kt           cacheDir/sources/<id>/: index.json (24 h TTL) + images/
    source/net/Http.kt              HttpURLConnection get / postForm / download
    source/SourceRegistry.kt        discover / enable / install / import / credentials; bumps sources_version
    store/AppPrefs.kt               SharedPreferences "meme_kb" (recents, save, watermark, history, sources)
    store/SourceCredentials.kt      SharedPreferences "source_auth": per-source username/password or API key
    store/JsonStore.kt              atomic JSON file read/write (tmp + rename)
    store/LayoutStore.kt            per-template box layout overrides (files/layouts)
    store/HistoryStore.kt           per-template history, 30 entries (files/history)
    share/MemeSender.kt             render to PNG + commitContent → clipboard fallback
    share/MemeSaver.kt              SAF folder → MediaStore (10+) → legacy Pictures (9) → none
  res/layout/, res/layout-land/     panel grid + editor (portrait: stacked; landscape: canvas beside the keyboard)
  res/xml/                          IME declaration, FileProvider paths, backup rules
  assets/memegen_layouts.json       text-box geometry for every memegen.link template (generated)
  assets/fonts/impact.ttf           Anton (OFL, licence alongside), the Impact substitute
app/src/foss/assets/templates/      bundled "Classic memes" pack (generated, foss flavour only)
tools/fetch-templates.ps1           regenerates the two generated assets from memegen.link + Imgflip
docs/PACK_FORMAT.md                 the pack / layout / history JSON format and the memegen conversion
docs/privacy.md                     privacy policy
docs/PLAY_LISTING.md                Google Play listing material
```

## Design notes

- **Panel height** is enforced in `KeyboardPanelView.onMeasure` (plus the navigation-bar inset) rather than via `LayoutParams`, because `InputMethodService` re-wraps the input view. Portrait: 45% of the screen for the grid, 65% for the editor. Landscape: 70% for both, because host apps close the IME session when a landscape keyboard leaves too little room for the focused field.
- **Baked + live rendering.** While a box is selected the editor renders the template plus every *other* box (and the watermark) once on a background thread; the selected box is drawn live by `MemeCanvasView` through the same `fit`/`draw` code the final render uses, so typing and dragging never wait for a bitmap and the preview is WYSIWYG.
- **Normalised geometry.** Boxes are stored in 0..1 image coordinates with memegen.link's field names (`anchor_x`, `scale_x`, `style`, `align`, …), so packs, layout overrides, history and memegen configs share one serialisation (`TextBoxSpec.toJson`).
- **Storage** is `org.json` files in `filesDir` written atomically (`JsonStore`): `layouts/<key>.json`, `history/<key>.json`, `imported/index.json`, `packs/<id>/`. File names come from `MemeTemplate.safeFileName(key)`. No Room, DataStore or serialization plugin.
- **Save destinations** live in one place (`MemeSaver`): the folder the user picked in Settings (persisted SAF grant) → MediaStore `Pictures/Memetype` on Android 10+ → the legacy Pictures directory on Android 9 with `WRITE_EXTERNAL_STORAGE` (declared with `maxSdkVersion="28"`) → unavailable. A deleted or revoked folder is reported, never silently replaced.
- **The IME never prompts.** Folder pickers and permission requests happen in `SettingsActivity`; sources are managed in `SourcesActivity`; both are started from the service with `FLAG_ACTIVITY_NEW_TASK`.
- **Text mode.** When the focused field belongs to Memetype itself (`EditorInfo.packageName` equals our package, e.g. the sign-in screen) the panel becomes a plain QWERTY (`TextModeView`, `PanelMode.TEXT`, fixed 214 dp) that types through `commitText`; other apps always get the meme grid.
- **Sources are data only.** `MemeSource` implementations ship in the APK; packs are `pack.json` + images. `SourceRegistry` bumps `sources_version` in SharedPreferences on every change and `TemplateRepository` reloads when it sees a new value (same process). The grid queries off the main thread.
- **Online sources load asynchronously.** `TemplateRepository.all()` returns local sources at once and the last fetched list of each remote source; missing or stale (> 24 h) indexes are fetched on a dedicated thread, then `onChanged` makes the grid refresh. Failures retry after 60 s; offline, the cached index and images keep working. Remote images are downloaded into `cacheDir/sources/<id>/images/` when a tile or the editor first needs them.
- **Source auth.** A source declares an `AuthScheme` (`None`, `ApiKey`, `UsernamePassword`); credentials live in the private `source_auth` preferences and are handed to the source per call (`search`, `testCredentials`). Nothing is sent anywhere except the source's own endpoints; the provider's own error text is shown when a test fails.
- **`onEvaluateInputViewShown` returns true** (a reported hardware keyboard would otherwise hide the IME) and **`onEvaluateFullscreenMode` returns false** (no landscape extract mode).
- **No toasts from the IME.** Android 13+ drops them without the notification permission; the panel shows its own messages (`KeyboardPanelView.showMessage`). Activities use normal toasts.
- **Send** renders on the service's executor, then delivers on the main thread (`commitContent` if the field accepts `image/png`, else `ClipData.newUri`), marks the template recent, appends history, and hands back to the previous keyboard (`switchToPreviousInputMethod` → `switchToNextInputMethod` → system picker).
- Hiding the panel resets the editor; history recovers anything sent or saved.

## Credits and licences

- Text-box layouts and template metadata: [memegen.link](https://github.com/jacebrowning/memegen) (MIT).
- Template images belong to their creators and are distributed as widely shared meme templates; each template carries its source link. Online templates are fetched from Imgflip and memegen.link when used.
- Font: Anton (SIL Open Font License), shipped as `assets/fonts/impact.ttf` with its licence.
- Icons: Material Icons (Apache-2.0).
