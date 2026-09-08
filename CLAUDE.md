# Memetype — developer notes

Memetype is an Android input method (IME) that builds meme images inside the chat: template grid → editor with movable text boxes → `commitContent` (or clipboard) → back to the previous keyboard. Package `com.umo.memetype`, Kotlin, no Compose.

## Stack and build

- Android Studio 2026.1+ with its bundled JBR 25; Gradle 9.7.1; AGP 9.4 with built-in Kotlin (no Kotlin plugin; `jvmTarget` follows `compileOptions` = 17). `compileSdk 37`, `targetSdk 36`, `minSdk 28`. Dependencies: `core-ktx`, `recyclerview` only; JSON via `org.json`.
- `local.properties` (git-ignored): `sdk.dir=C\:/path/to/Android/Sdk` (escaped colon, or lint fails with `PropertyEscape`).
- Commands (PowerShell; from Git Bash use `./gradlew.bat` with `JAVA_HOME` set to Studio's `jbr`):
  ```
  .\gradlew.bat :app:installFossDebug
  .\gradlew.bat :app:assembleFossRelease :app:bundlePlayRelease
  .\gradlew.bat :app:lintFossRelease :app:lintPlayRelease
  ```
- Flavours (dimension `distribution`): `foss` (default) ships the bundled "Classic memes" pack from `app/src/foss/assets/templates` and the Donate link; `play` ships neither (Google Play IP and Payments policies) and fills the grid from the online sources. `BuildConfig.DONATE_ENABLED` and the presence of `templates/templates.json` in assets are the only switches.
- Release builds are minified (R8 + resource shrinking) and signed from `keystore.properties` (git-ignored; see `keystore.properties.example`); without it release builds are unsigned and debug builds are unaffected. Bump `versionCode` / `versionName` in `app/build.gradle.kts` before tagging a release.
- `tools/fetch-templates.ps1` regenerates the bundled pack and `app/src/main/assets/memegen_layouts.json` from the memegen.link repository tarball (its git clone fails on Windows: NTFS-invalid file names) and Imgflip's `get_memes`. Windows PowerShell 5.1 + System.Drawing, no Python.

## Project layout

```
app/src/main/
  AndroidManifest.xml               IME service, FileProvider, Settings / Sources / SourceLogin / ImportImage / Donate activities
  java/com/umo/memetype/
    MemeInputMethodService.kt       InputMethodService: panel host, render + save + deliver, history append, activity launches, text mode
    SettingsActivity.kt             Keyboard onboarding, Output (save, folder picker, watermark), History, Sources, About
    SourcesActivity.kt              enable/disable/remove sources, sign-in rows, import pack (.zip) or image
    SourceLoginActivity.kt          username/password or API key for one source: Test / Clear / Save
    ImportImageActivity.kt          place text boxes on an imported image (reuses MemeCanvasView)
    DonateActivity.kt               dialog with the donation link (foss flavour only)
    ui/KeyboardPanelView.kt         root: swaps grid <-> editor <-> text mode; PanelMode drives the height; in-panel messages
    ui/TextModeView.kt              plain QWERTY shown when the focused field belongs to Memetype itself
    ui/TemplateGridView.kt          top row (search + global action buttons), RecyclerView grid, category chips
    ui/MemeEditorView.kt            toolbar, canvas, box chips, history strip, mini keyboard; layout overrides; baked + live rendering
    ui/MemeCanvasView.kt            interactive canvas: baked bitmap + the selected box drawn live; move/resize/pinch gestures
    ui/HistoryStripView.kt          thumbnails of previous memes for the open template
    ui/MiniKeyboardView.kt          Canvas-drawn QWERTY (letters/symbols, shift, caps-lock, repeat backspace)
    ui/Rows.kt                      programmatic settings rows (header, text, button, switch)
    meme/TextBoxSpec.kt             TextBoxSpec (normalised geometry + style), TextBox (spec + text), DefaultLayouts
    meme/MemeTemplate.kt            MemeTemplate, ImageRef (Asset / LocalFile / Content / Remote), key = "source/id"
    meme/PackParser.kt              memekb-pack v1 -> templates
    meme/MemeRenderer.kt            fit (StaticLayout, auto-shrink) + draw (stroke + fill, rotation) + watermark; PNG to cacheDir/memes
    meme/TemplateRepository.kt      aggregates enabled sources (local sync, remote async + onChanged); thumbnail LruCache; recents; provider search
    meme/Bitmaps.kt                 two-pass downsampled decode
    source/MemeSource.kt            source interface + AuthScheme / Credentials (data only, no code loading)
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
    share/MemeSender.kt             render to PNG + commitContent -> clipboard fallback
    share/MemeSaver.kt              SAF folder -> MediaStore (10+) -> legacy Pictures (9) -> none
  res/layout/, res/layout-land/     panel grid + editor (portrait stacked; landscape canvas beside the keyboard)
  res/xml/                          IME declaration, FileProvider paths, backup rules
  assets/memegen_layouts.json       text-box geometry for every memegen.link template (generated)
  assets/fonts/impact.ttf           Anton (OFL), the Impact substitute
app/src/foss/assets/templates/      bundled "Classic memes" pack (generated, foss only)
tools/fetch-templates.ps1           regenerates the two generated assets
.github/workflows/                  build.yml (debug flavours + lint), release.yml (foss APK + play .aab + GitHub Release); both manual
docs/PACK_FORMAT.md                 pack / layout / history JSON format and the memegen conversion
docs/screenshots/                   README images
```

Only `docs/PACK_FORMAT.md` and `docs/screenshots/` are tracked; the other files under `docs/` (privacy policy draft, Play listing material, session notes) are the maintainer's to commit. Do not stage them.

## Design notes

- **Panel height** is enforced in `KeyboardPanelView.onMeasure` (plus the navigation-bar inset) rather than via `LayoutParams`, because `InputMethodService` re-wraps the input view. Portrait: 45% of the screen for the grid, 65% for the editor. Landscape: 70% for both, because host apps close the IME session when a landscape keyboard leaves too little room for the focused field. `PanelMode.TEXT` is a fixed 214 dp.
- **Baked + live rendering.** While a box is selected the editor renders the template plus every *other* box (and the watermark) once on a background thread; the selected box is drawn live by `MemeCanvasView` through the same `fit`/`draw` code the final render uses, so typing and dragging never wait for a bitmap and the preview matches the output.
- **Normalised geometry.** Boxes are stored in 0..1 image coordinates with memegen.link's field names (`anchor_x`, `scale_x`, `style`, `align`, …), so packs, layout overrides, history and memegen configs share one serialisation (`TextBoxSpec.toJson`).
- **Storage** is `org.json` files in `filesDir` written atomically (`JsonStore`): `layouts/<key>.json`, `history/<key>.json`, `imported/index.json`, `packs/<id>/`. File names come from `MemeTemplate.safeFileName(key)`; a template key is `"<sourceId>/<id>"`. No Room, DataStore or serialization plugin.
- **Save destinations** live in one place (`MemeSaver`): the folder the user picked in Settings (persisted SAF grant) → MediaStore `Pictures/Memetype` on Android 10+ → the legacy Pictures directory on Android 9 with `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion="28"`) → unavailable. A deleted or revoked folder is reported, never silently replaced.
- **The IME never prompts.** Folder pickers and permission requests happen in `SettingsActivity`; sources in `SourcesActivity`; both are started from the service with `FLAG_ACTIVITY_NEW_TASK`.
- **Text mode.** When the focused field belongs to Memetype itself (`EditorInfo.packageName` equals our package, e.g. the sign-in screen) the panel becomes a plain QWERTY (`TextModeView`) that types through `commitText`; other apps always get the meme grid. This is what lets our own screens use real `EditText`s.
- **Sources are data only.** `MemeSource` implementations ship in the APK; packs are `pack.json` + images. `SourceRegistry` bumps `sources_version` in SharedPreferences on every change and `TemplateRepository` reloads when it sees a new value (IME and activities share one process; never add `android:process`). The grid queries off the main thread.
- **Online sources load asynchronously.** `TemplateRepository.all()` returns local sources at once and the last fetched list of each remote source; missing or stale (> 24 h) indexes are fetched on a dedicated thread, then `onChanged` makes the grid refresh. Failures retry after 60 s; offline, cached index and images keep working. Remote images download into `cacheDir/sources/<id>/images/` when a tile or the editor first needs them.
- **Source auth.** A source declares an `AuthScheme` (`None`, `ApiKey`, `UsernamePassword`); credentials live in the private `source_auth` preferences (excluded from backups) and are handed to the source per call (`search`, `testCredentials`). Nothing is sent anywhere except the source's own endpoints; the provider's error text is shown when a test fails. Imgflip's login is optional and only unlocks its premium `search_memes` (untested: no account).
- **`onEvaluateInputViewShown` returns true** (a reported hardware keyboard would otherwise hide the IME) and **`onEvaluateFullscreenMode` returns false**.
- **No toasts from the IME.** Android 13+ drops them without the notification permission; the panel shows its own messages (`KeyboardPanelView.showMessage`). Activities use normal toasts.
- **Send** renders on the service's executor, then delivers on the main thread (`commitContent` if the field accepts `image/png`, else `ClipData.newUri`), marks the template recent, appends history, and hands back to the previous keyboard (`switchToPreviousInputMethod` → `switchToNextInputMethod` → system picker).
- Hiding the panel resets the editor; history recovers anything sent or saved.

## Gotchas

- Kotlin nests block comments: never write `image/*`-style text inside KDoc (it swallowed the rest of a file once). Write `image/<any>`.
- Executors in views shut down for good in `destroy()`; guard every `execute` with `isShutdown`.
- Password `EditText`s: set `isSingleLine` before `inputType`, or the password shows in clear text.
- `PackParser` throws on structural errors; callers log and skip the pack, so one bad pack never takes the IME down.
- The Play flavour has no bundled pack: `SourceRegistry.hasBundled` checks the asset and hides the row.

## Testing on the emulator

- AVD `meme_api36` (Android 16, Google Play image, Pixel 9 profile); Gboard is the other IME.
- Select the IME: `adb shell ime enable com.umo.memetype/.MemeInputMethodService && adb shell ime set com.umo.memetype/.MemeInputMethodService`. After a Send the IME hands back to the previous keyboard, so run `ime set` again before the next test.
- Targets: Contacts (`am start -a android.intent.action.INSERT -t vnd.android.cursor.dir/contact`) exercises the clipboard path; Google Messages (`am start -a android.intent.action.SENDTO -d sms:5551234567`) accepts `commitContent`.
- Screenshots: `adb exec-out screencap -p > file.png` (binary through `exec-out`; from Git Bash set `MSYS_NO_PATHCONV=1` for device paths). IME views are not in `uiautomator dump`; tap by geometry.
- Activities other than Settings are not exported: `am start -n …/.SourcesActivity` is refused; open them through the app.
- Saved memes: `adb shell content query --uri content://media/external/images/media --projection _display_name:relative_path --where "owner_package_name='com.umo.memetype'"`.
- Offline behaviour: `adb shell cmd connectivity airplane-mode enable` / `disable`.
- `act` (local GitHub Actions): needs `%LOCALAPPDATA%\act\actrc` with `-P ubuntu-latest=catthehacker/ubuntu:act-latest`; `actions/setup-java` must stay on v5.x (v6 is a no-op under act); the artifact-upload step fails only under act. A full build of both flavours takes ~2 min in the container.
