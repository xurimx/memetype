# Memetype (Android meme keyboard)

Build a meme without leaving the chat: switch to this keyboard, pick a template, type captions on the built-in mini QWERTY, drag the text boxes where you want them, tap **Send** — the PNG is inserted into the chat's text field (or copied to the clipboard) and the previous keyboard comes back.

## Status (version 0.3, 2026-09-08; renamed from Meme Keyboard to Memetype, package `com.umo.memetype`, on 2026-09-07)

Builds with Android Studio 2026.1.4 (Quail 4), AGP 9.4.0, Gradle 9.7.1, compileSdk 37 / targetSdk 35 / minSdk 28, and every feature below was exercised on an Android 16 (API 36) Google Play emulator; Urim also confirmed on a phone that the PNG pastes into Discord.

- **Templates:** 50 classic memes bundled ("Classic memes", built by `tools/fetch-templates.ps1` from memegen.link geometry + Imgflip's popularity list), plus two online sources: **Imgflip** (100 most popular templates, layouts synthesised from the box count) and **memegen.link** (~210 templates with real text layouts). Online indexes and images are cached under `cacheDir/sources/`.
- **Editor:** any number of text boxes per template; tap to select, drag to move, corner handles to resize, pinch to scale; add / delete / reset; per-template layout overrides persist. The panel grows from 45% to 65% of the screen while editing.
- **Output:** PNG ≤ 800 px with an optional "Memetype" watermark (default on). Sent memes are saved to the device (default on) — into a folder you pick or `Pictures/Memetype` — and there is a Save button too.
- **History:** the last 30 memes per template come back as a thumbnail strip when you reopen it.
- **Sources & sign-in:** bundled pack + "Mine" (imported images with your own box layout) + installable template packs (`docs/PACK_FORMAT.md`) + the online sources, each with an on/off switch. Sources that support an account (Imgflip: optional, unlocks premium search) get a "Sign in" row; credentials stay in the app's private preferences.
- **Top bar:** the grid's search row carries switch keyboard, Settings, Sources, GitHub and Donate (the two links are placeholder strings until the repo and donation page exist).

Open items: pack repositories, smaller thumbnail downloads for online tiles, tests on Android 9 (legacy save path), WhatsApp/Telegram on a phone, Imgflip premium search with a real account. `docs/SESSION_NOTES.md` has the verified matrix and a resume prompt.

The bundled meme images belong to their creators and are distributed as widely shared meme templates; each template carries its source link. Text layouts come from [memegen.link](https://github.com/jacebrowning/memegen) (MIT).

## Build

Open the folder in Android Studio 2026.1 or newer and let it sync. Its bundled JDK 25 runs the build (Gradle 9.7.1 needs Java 17+). There is no separate Kotlin plugin: AGP 9 compiles Kotlin itself, and `jvmTarget` follows `compileOptions` (17). Dependencies are `core-ktx` and `recyclerview` only (no appcompat/material/compose; JSON is `org.json`).

From PowerShell, with `JAVA_HOME` pointing at Studio's `jbr` folder and `ANDROID_HOME` at the SDK:

```
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:installDebug
.\gradlew.bat :app:lintDebug
```

`local.properties` (git-ignored) needs `sdk.dir=C\:/path/to/Android/Sdk`; escape the drive colon or lint fails with `PropertyEscape`.

### Flavours and release builds

Two product flavours of the same app (dimension `distribution`):

| Flavour | Bundled "Classic memes" pack | Donate link | For |
|---|---|---|---|
| `foss` (default) | yes (`app/src/foss/assets/templates`) | yes | GitHub releases, F-Droid |
| `play` | no — templates come from the online sources | no | Google Play (IP and Payments policies) |

Release builds are minified (R8) and signed with the upload key described in `keystore.properties.example`
(copy it to `keystore.properties`, git-ignored). Without that file, release builds are unsigned and debug
builds are unaffected.

```
.\gradlew.bat :app:installFossDebug        # day-to-day
.\gradlew.bat :app:assembleFossRelease     # signed APK for GitHub
.\gradlew.bat :app:bundlePlayRelease       # .aab for Play Console
```

`targetSdk` is 36 (Google Play requires API 36 for new apps and updates from 31 Aug 2026). Backups exclude the
`source_auth` credentials file (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`). Store-listing text,
Data-safety answers and the privacy policy draft live in `docs/PLAY_LISTING.md` and `docs/privacy.md`.

## Try it

1. Launch **Memetype** (the `SettingsActivity`) → *Enable keyboard* → toggle it on in system settings. *Select keyboard* opens the system picker.
2. Open a chat, tap the keyboard-switcher (globe) icon in the nav bar, pick *Memetype*.
3. Tap a template → type (↵ moves to the next box; on the last box it sends) → drag boxes if you like → **Send ✓** or the Save icon.
4. Settings (gear in the grid's top row): save folder, watermark, history, sources. Sources (puzzle icon): import a pack zip or a picture.

On an emulator, `adb shell ime enable com.umo.memetype/.MemeInputMethodService` followed by `adb shell ime set …` selects it without the picker. After a Send the IME hands back to the previous keyboard, so run `ime set` again before the next test.

### Regenerating the bundled pack

```
powershell -ExecutionPolicy Bypass -File tools\fetch-templates.ps1
```

Downloads the memegen repository tarball and Imgflip's `get_memes`, resizes the curated 50 templates to ≤ 800 px JPEG, writes `assets/templates/templates.json` and `assets/memegen_layouts.json` (all memegen layouts, used by the online source). Edit the curated table at the top of the script to change the selection; ids that do not exist stop the script.

## Layout

```
app/src/main/
  AndroidManifest.xml               IME service, FileProvider, Settings / Sources / ImportImage / Donate activities
  java/com/umo/memetype/
    MemeInputMethodService.kt       InputMethodService: panel host, render + save + deliver, history append, activity launches
    SettingsActivity.kt             Keyboard onboarding, Output (save, folder picker, watermark), History, Sources, About
    SourcesActivity.kt              enable/disable/remove sources, sign-in rows, import pack (.zip) or image
    SourceLoginActivity.kt          username/password or API key for one source: Test / Clear / Save
    ImportImageActivity.kt          place text boxes on an imported image (reuses MemeCanvasView)
    DonateActivity.kt               dialog with the (placeholder) donation link
    ui/KeyboardPanelView.kt         root: swaps grid <-> editor <-> text mode; PanelMode drives the height; in-panel messages
    ui/TextModeView.kt              plain QWERTY shown when the focused field belongs to Memetype itself (sign-in screen)
    ui/TemplateGridView.kt          top row (search + global action buttons), RecyclerView grid, category chips (Popular/Recent/Mine/Reaction/Animals)
    ui/MemeEditorView.kt            toolbar, canvas, box chips, history strip, mini keyboard; layout overrides; baked + live rendering
    ui/MemeCanvasView.kt            the interactive canvas: draws the baked bitmap + the selected box live; move/resize/pinch gestures
    ui/HistoryStripView.kt          thumbnails of previous memes for the open template
    ui/MiniKeyboardView.kt          Canvas-drawn QWERTY (letters/symbols, shift, caps-lock, repeat ⌫)
    ui/Rows.kt                      programmatic settings rows (header, text, button, switch)
    meme/TextBoxSpec.kt             TextBoxSpec (normalised geometry + style), TextBox (spec + text), DefaultLayouts
    meme/MemeTemplate.kt            MemeTemplate, ImageRef (Asset / LocalFile / Content / Remote), key = "source/id"
    meme/PackParser.kt              memekb-pack v1 → templates
    meme/MemeRenderer.kt            fit (StaticLayout, auto-shrink) + draw (stroke + fill, rotation) + watermark; PNG to cacheDir/memes
    meme/TemplateRepository.kt      aggregates enabled sources (local sync, remote async + onChanged), reloads on sources_version; thumbnails LruCache; recents; provider search
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
  res/drawable/ic_*.xml             Material Icons (Apache-2.0) vector icons
  assets/templates/images/*.jpg + templates.json   bundled "Classic memes" pack (50, generated by tools/fetch-templates.ps1)
  assets/memegen_layouts.json       text-box geometry for every memegen.link template (generated)
  assets/fonts/impact.ttf           Anton (OFL, licence alongside), the Impact substitute
tools/fetch-templates.ps1           regenerates the two generated assets from memegen + Imgflip
docs/PACK_FORMAT.md                 the pack / layout / history JSON format, memegen conversion
```

## Design notes

- **Panel height** is enforced in `KeyboardPanelView.onMeasure` (plus the navigation-bar inset) rather than via `LayoutParams`, because `InputMethodService` re-wraps the input view. Portrait: 45% of the screen for the grid, 65% for the editor. Landscape: 70% for both — growing the panel in landscape made the host app (Contacts) close the IME session because too little room was left for the focused field.
- **Baked + live rendering.** While a box is selected the editor renders the template plus every *other* box (and the watermark) once on a background thread; the selected box is drawn live by `MemeCanvasView` through the same `fit`/`draw` code the final render uses, so typing and dragging never wait for a bitmap and the preview is WYSIWYG.
- **Normalised geometry.** Boxes are stored in 0..1 image coordinates with memegen.link's field names (`anchor_x`, `scale_x`, `style`, `align`, …), so packs, layout overrides and history share one serialisation (`TextBoxSpec.toJson`) and memegen configs convert 1:1 later.
- **Storage** is hand-rolled `org.json` files in `filesDir` written atomically (`JsonStore`): `layouts/<key>.json`, `history/<key>.json`, `imported/index.json`, `packs/<id>/`. File names come from `MemeTemplate.safeFileName(key)`. No Room / DataStore / serialization plugin.
- **Save destinations** live in one place (`MemeSaver`): the SAF tree the user picked in Settings (persisted grant) → MediaStore `Pictures/Memetype` on Android 10+ → the legacy Pictures directory on Android 9 with `WRITE_EXTERNAL_STORAGE` (declared with `maxSdkVersion="28"`) → unavailable. A deleted or revoked folder is reported ("Couldn't save"), never silently replaced; Settings shows "Folder access lost".
- **The IME never prompts.** Folder pickers and permission requests happen in `SettingsActivity`; sources are managed in `SourcesActivity`; both are started from the service with `FLAG_ACTIVITY_NEW_TASK`.
- **Text mode.** When the focused field belongs to Memetype itself (`EditorInfo.packageName == our package`, e.g. the sign-in screen) the panel becomes a plain QWERTY (`TextModeView`, `PanelMode.TEXT`, fixed 214 dp) that types through `commitText`; other apps always get the meme grid. This is what lets our own screens use real `EditText`s.
- **Sources are data only.** `MemeSource` implementations ship in the APK; packs are `pack.json` + images. `SourceRegistry` bumps `sources_version` in SharedPreferences on every change and `TemplateRepository` reloads when it sees a new value (same process, so reads are always current). The grid queries off the main thread.
- **Online sources load asynchronously.** `TemplateRepository.all()` returns local sources at once and the last fetched list of each remote source; missing or stale (> 24 h) indexes are fetched on a dedicated thread, then `onChanged` makes the grid refresh. Failures retry after 60 s; offline, the cached index and images keep working. Remote images are downloaded into `cacheDir/sources/<id>/images/` when a tile or the editor first needs them.
- **Source auth mirrors Mihon.** A source declares an `AuthScheme` (`None`, `ApiKey`, `UsernamePassword`); credentials live in the private `source_auth` preferences and are handed to the source per call (`search`, `testCredentials`). Nothing is sent anywhere except the source's own endpoints. Imgflip's login is optional and only unlocks its premium `search_memes`; the provider's own error text is shown when a test fails.
- **`onEvaluateInputViewShown` returns true** (emulators and Bluetooth keyboards report a hardware keyboard, which would otherwise hide the IME) and **`onEvaluateFullscreenMode` returns false** (no landscape extract mode).
- **No toasts from the IME.** Android 13+ drops them without the notification permission; the panel shows its own messages (`KeyboardPanelView.showMessage`). Activities use normal toasts.
- **Send** renders on the service's executor, then delivers on the main thread (`commitContent` if the field accepts `image/png`, else `ClipData.newUri`), marks the template recent, appends history, and hands back to the previous keyboard (`switchToPreviousInputMethod` → `switchToNextInputMethod` → system picker).
- Hiding the panel resets the editor (captions are lost mid-edit); history recovers anything sent or saved.

## Next

- Smaller thumbnail downloads for online tiles (memegen supports `?width=`; Imgflip does not) so the first scroll fills faster.
- More online sources on the same interface: an API-key source (Giphy / Tenor stickers) would reuse `AuthScheme.ApiKey`.
- Pack repositories ("browse & install" from a JSON index; `pack_repos` preference exists).
- Fill in `github_url` / `donate_url`.
- Real-device matrix: WhatsApp, Telegram, Google Messages with MMS/RCS, Android 9.
