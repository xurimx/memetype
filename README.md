# Memetype (Android meme keyboard)

Build a meme without leaving the chat: switch to this keyboard, pick a template, type captions on the built-in mini QWERTY, drag the text boxes where you want them, tap **Send** — the PNG is inserted into the chat's text field (or copied to the clipboard) and the previous keyboard comes back.

## Status (version 0.2, 2026-09-07; renamed from Meme Keyboard to Memetype, package `com.umo.memetype`, the same day)

Builds with Android Studio 2026.1.4 (Quail 4), AGP 9.4.0, Gradle 9.7.1, compileSdk 37 / targetSdk 35 / minSdk 28, and every feature below was exercised on an Android 16 (API 36) Google Play emulator; Urim also confirmed on a phone that the PNG pastes into Discord.

- **Editor:** any number of text boxes per template; tap to select, drag to move, corner handles to resize, pinch to scale; add / delete / reset; per-template layout overrides persist. The panel grows from 45% to 65% of the screen while editing.
- **Output:** PNG ≤ 800 px with an optional "Memetype" watermark (default on). Sent memes are saved to the device (default on) — into a folder you pick or `Pictures/Memetype` — and there is a Save button too.
- **History:** the last 30 memes per template come back as a thumbnail strip when you reopen it.
- **Sources:** bundled pack + "Mine" (imported images with your own box layout) + installable template packs (`docs/PACK_FORMAT.md`). Remote sources (Imgflip, memegen.link) are designed for but not implemented.
- **Top bar:** the grid's search row carries switch keyboard, Settings, Sources, GitHub and Donate (the two links are placeholder strings until the repo and donation page exist).

Open items: real template images (10 solid-colour placeholders ship), remote sources, pack repositories, tests on Android 9 (legacy save path), WhatsApp/Telegram on a phone. `docs/SESSION_NOTES.md` has the verified matrix and a resume prompt.

## Build

Open the folder in Android Studio 2026.1 or newer and let it sync. Its bundled JDK 25 runs the build (Gradle 9.7.1 needs Java 17+). There is no separate Kotlin plugin: AGP 9 compiles Kotlin itself, and `jvmTarget` follows `compileOptions` (17). Dependencies are `core-ktx` and `recyclerview` only (no appcompat/material/compose; JSON is `org.json`).

From PowerShell, with `JAVA_HOME` pointing at Studio's `jbr` folder and `ANDROID_HOME` at the SDK:

```
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:installDebug
.\gradlew.bat :app:lintDebug
```

`local.properties` (git-ignored) needs `sdk.dir=C\:/path/to/Android/Sdk`; escape the drive colon or lint fails with `PropertyEscape`.

## Try it

1. Launch **Memetype** (the `SettingsActivity`) → *Enable keyboard* → toggle it on in system settings. *Select keyboard* opens the system picker.
2. Open a chat, tap the keyboard-switcher (globe) icon in the nav bar, pick *Memetype*.
3. Tap a template → type (↵ moves to the next box; on the last box it sends) → drag boxes if you like → **Send ✓** or the Save icon.
4. Settings (gear in the grid's top row): save folder, watermark, history, sources. Sources (puzzle icon): import a pack zip or a picture.

On an emulator, `adb shell ime enable com.umo.memetype/.MemeInputMethodService` followed by `adb shell ime set …` selects it without the picker. After a Send the IME hands back to the previous keyboard, so run `ime set` again before the next test.

## Layout

```
app/src/main/
  AndroidManifest.xml               IME service, FileProvider, Settings / Sources / ImportImage / Donate activities
  java/com/umo/memetype/
    MemeInputMethodService.kt       InputMethodService: panel host, render + save + deliver, history append, activity launches
    SettingsActivity.kt             Keyboard onboarding, Output (save, folder picker, watermark), History, Sources, About
    SourcesActivity.kt              enable/disable/remove sources, import pack (.zip) or image
    ImportImageActivity.kt          place text boxes on an imported image (reuses MemeCanvasView)
    DonateActivity.kt               dialog with the (placeholder) donation link
    ui/KeyboardPanelView.kt         root: swaps grid <-> editor; PanelMode drives the height; in-panel messages
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
    meme/TemplateRepository.kt      aggregates enabled sources, reloads on sources_version; thumbnails LruCache; recents
    meme/Bitmaps.kt                 two-pass downsampled decode
    source/MemeSource.kt            the source interface (data only, no code loading)
    source/PackSource.kt            bundled assets pack or installed pack directory
    source/LocalSource.kt           "Mine": imported images, index in pack format
    source/PackInstaller.kt         zip validation + install/uninstall under files/packs
    source/SourceRegistry.kt        discover / enable / install / import; bumps sources_version
    store/AppPrefs.kt               SharedPreferences "meme_kb" (recents, save, watermark, history, sources)
    store/JsonStore.kt              atomic JSON file read/write (tmp + rename)
    store/LayoutStore.kt            per-template box layout overrides (files/layouts)
    store/HistoryStore.kt           per-template history, 30 entries (files/history)
    share/MemeSender.kt             render to PNG + commitContent → clipboard fallback
    share/MemeSaver.kt              SAF folder → MediaStore (10+) → legacy Pictures (9) → none
  res/layout/, res/layout-land/     panel grid + editor (portrait: stacked; landscape: canvas beside the keyboard)
  res/drawable/ic_*.xml             Material Icons (Apache-2.0) vector icons
  assets/templates/*.png + templates.json   bundled pack (10 placeholders — replace with real, licensed images)
  assets/fonts/impact.ttf           Anton (OFL, licence alongside), the Impact substitute
docs/PACK_FORMAT.md                 the pack / layout / history JSON format
```

## Design notes

- **Panel height** is enforced in `KeyboardPanelView.onMeasure` (plus the navigation-bar inset) rather than via `LayoutParams`, because `InputMethodService` re-wraps the input view. Portrait: 45% of the screen for the grid, 65% for the editor. Landscape: 70% for both — growing the panel in landscape made the host app (Contacts) close the IME session because too little room was left for the focused field.
- **Baked + live rendering.** While a box is selected the editor renders the template plus every *other* box (and the watermark) once on a background thread; the selected box is drawn live by `MemeCanvasView` through the same `fit`/`draw` code the final render uses, so typing and dragging never wait for a bitmap and the preview is WYSIWYG.
- **Normalised geometry.** Boxes are stored in 0..1 image coordinates with memegen.link's field names (`anchor_x`, `scale_x`, `style`, `align`, …), so packs, layout overrides and history share one serialisation (`TextBoxSpec.toJson`) and memegen configs convert 1:1 later.
- **Storage** is hand-rolled `org.json` files in `filesDir` written atomically (`JsonStore`): `layouts/<key>.json`, `history/<key>.json`, `imported/index.json`, `packs/<id>/`. File names come from `MemeTemplate.safeFileName(key)`. No Room / DataStore / serialization plugin.
- **Save destinations** live in one place (`MemeSaver`): the SAF tree the user picked in Settings (persisted grant) → MediaStore `Pictures/Memetype` on Android 10+ → the legacy Pictures directory on Android 9 with `WRITE_EXTERNAL_STORAGE` (declared with `maxSdkVersion="28"`) → unavailable. A deleted or revoked folder is reported ("Couldn't save"), never silently replaced; Settings shows "Folder access lost".
- **The IME never prompts.** Folder pickers and permission requests happen in `SettingsActivity`; sources are managed in `SourcesActivity`; both are started from the service with `FLAG_ACTIVITY_NEW_TASK`. None of the app's activities contains an `EditText`: while Memetype is the selected IME an `EditText` would summon the meme panel over our own screen.
- **Sources are data only.** `MemeSource` implementations ship in the APK; packs are `pack.json` + images. `SourceRegistry` bumps `sources_version` in SharedPreferences on every change and `TemplateRepository` reloads when it sees a new value (same process, so reads are always current). The grid queries off the main thread.
- **`onEvaluateInputViewShown` returns true** (emulators and Bluetooth keyboards report a hardware keyboard, which would otherwise hide the IME) and **`onEvaluateFullscreenMode` returns false** (no landscape extract mode).
- **No toasts from the IME.** Android 13+ drops them without the notification permission; the panel shows its own messages (`KeyboardPanelView.showMessage`). Activities use normal toasts.
- **Send** renders on the service's executor, then delivers on the main thread (`commitContent` if the field accepts `image/png`, else `ClipData.newUri`), marks the template recent, appends history, and hands back to the previous keyboard (`switchToPreviousInputMethod` → `switchToNextInputMethod` → system picker).
- Hiding the panel resets the editor (captions are lost mid-edit); history recovers anything sent or saved.

## Next

- Real, licensed template images with proper `text[]` layouts (see `docs/PACK_FORMAT.md`).
- Remote sources behind `MemeSource` (`ImageRef.Remote` + `resolveImage`): Imgflip `get_memes` (count-only layouts via `DefaultLayouts.forCount`), memegen.link (convert `config.yml` → `text[]`).
- Pack repositories ("browse & install" from a JSON index; `pack_repos` preference exists).
- Fill in `github_url` / `donate_url`.
- Real-device matrix: WhatsApp, Telegram, Google Messages with MMS/RCS, Android 9.
