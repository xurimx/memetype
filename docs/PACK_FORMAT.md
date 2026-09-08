# Template pack format (`memekb-pack` v1)

A pack is a folder (or a zip of that folder) with a `pack.json` at the root and the template
images it references. The same document format is used for the bundled templates
(`app/src/main/assets/templates/templates.json`), for packs the user installs from a zip
(`files/packs/<id>/pack.json`) and for the "Mine" index of imported images
(`files/imported/index.json`). Parser: `meme/PackParser.kt`; installer: `source/PackInstaller.kt`.

Packs are **data only**. No code is ever loaded from a pack (Google Play forbids executable
downloads, and there is no need: every source implementation ships in the APK).

## Example

```json
{
  "format": "memekb-pack",
  "version": 1,
  "id": "starter",
  "name": "Starter pack",
  "author": "John",
  "licence": "CC-BY-4.0",
  "templates": [
    {
      "id": "drake",
      "name": "Drake Hotline Bling",
      "file": "images/drake.png",
      "width": 1200,
      "height": 1200,
      "source": "https://knowyourmeme.com/memes/drakeposting",
      "keywords": ["reaction"],
      "example": ["Writing tests", "Testing in prod"],
      "text": [
        { "anchor_x": 0.52, "anchor_y": 0.03, "scale_x": 0.45, "scale_y": 0.44,
          "align": "center", "valign": "center", "style": "upper",
          "color": "black", "outline": "none", "font": "thick", "angle": 0,
          "max_lines": 0, "max_size": 0.11, "hint": "Nah" },
        { "anchor_x": 0.52, "anchor_y": 0.53, "scale_x": 0.45, "scale_y": 0.44,
          "align": "center", "style": "upper", "color": "black", "outline": "none", "hint": "Yeah" }
      ]
    },
    { "id": "two_buttons", "name": "Two Buttons", "file": "images/two_buttons.png", "boxes": 3 },
    { "id": "doge", "name": "Doge", "file": "images/doge.png" }
  ]
}
```

## Pack level

| Field | Required | Meaning |
|---|---|---|
| `format` | yes | must be `"memekb-pack"` (tolerated if absent) |
| `version` | yes | must be `1` |
| `id` | yes | stable id; the install directory and the source id (`pack:<id>`) derive from it. Reinstalling a pack with the same id replaces it |
| `name` | no | shown in the Sources screen (defaults to the id) |
| `author`, `licence` (or `license`) | no | shown under the name |
| `templates` | yes | array of templates |

## Template level

| Field | Required | Meaning |
|---|---|---|
| `id` | yes | unique within the pack; the template key is `"<sourceId>/<id>"` and recents, layout overrides and history key on it |
| `name` | no | display name (defaults to id) |
| `file` | yes | image path relative to `pack.json` (`png`, `jpg`, `jpeg`, `webp`) |
| `width`, `height` | no | pixel size hints (0 = unknown, decoded on demand) |
| `keywords` (alias `tags`) | no | category chips filter on `reaction` and `animals`; search matches any keyword |
| `example` | no | one example caption per box; used as the box hint when `hint` is absent (memegen.link field) |
| `source`, `licence` | no | attribution |
| `text` | no | array of text boxes (below). Wins over `boxes` |
| `boxes` | no | integer; synthesises a layout: 1 → bottom, 2 → top/bottom, n → stacked rows |
| *(neither)* | | classic top/bottom layout |

## Text box (`text[]`)

Geometry is normalised to the image (0..1, top-left anchor). Field names follow memegen.link's
`config.yml` so its templates convert 1:1; fields marked * are Memetype extensions.

| Field | Default | Meaning |
|---|---|---|
| `anchor_x`, `anchor_y` | required | top-left corner |
| `scale_x`, `scale_y` | required | width and height (minimum 0.08) |
| `align` | `center` | `start`/`left`, `center`, `end`/`right` |
| `valign`* | `center` | `top`, `center`, `bottom` |
| `style` | `upper` | `upper` uppercases the caption; anything else keeps it as typed |
| `color` | `white` | fill colour: a name `Color.parseColor` knows or `#RRGGBB(AA)` |
| `outline`* | `black` | stroke colour; `none` = no stroke |
| `font` | `thick` | `thick` (Anton, the bundled Impact substitute), `sans`, `condensed`, `serif`, `mono` |
| `angle` | `0` | degrees, rotation about the box centre |
| `max_lines`* | `0` | 0 = as many lines as fit the box height |
| `max_size`* | `0.111` | starting text size as a fraction of the image height |
| `hint`* | `example[i]` or `Text N` | chip placeholder in the editor |
| `value`* | | only in history and layout files, never in packs: the typed caption |

## Zip rules (installer)

- `pack.json` at the root of the zip, or inside a single top-level folder (flattened on install).
- Allowed files: `.json`, `.png`, `.jpg`, `.jpeg`, `.webp`. Hidden files (`.DS_Store`, `__MACOSX`) are skipped, anything else is rejected.
- No `..` segments or absolute paths; every referenced image must exist inside the pack.
- At most 500 files and 200 MB. Backslash separators (PowerShell `Compress-Archive`) are normalised.
- Installed to `files/packs/<id>/`; removed from the Sources screen.

## Repository index (planned, not implemented)

For "browse and install" a repository would be a JSON index at a URL:

```json
{ "format": "memekb-repo", "version": 1, "name": "…",
  "packs": [ { "id": "starter", "name": "Starter pack", "version": 3, "description": "…",
               "author": "…", "download": "https://…/starter.zip", "size": 1234567 } ] }
```

The `pack_repos` preference already exists for the list of repository URLs.

## memegen.link conversion (`tools/fetch-templates.ps1`)

memegen's `templates/<id>/config.yml` uses the same normalised fields (`anchor_x`, `anchor_y`,
`scale_x`, `scale_y`, `align`, `style`, `color`, `font`, `angle`), so a text entry converts 1:1 with
three mappings: `font` `thick` → `thick` with a black `outline`, `thin`/`comic` → `sans` with
`outline: none`; `style` `upper` → `upper`, everything else (`default`, `none`, `mock`) → `none`;
colour names Android does not know (`khaki`, `palegoldenrod`, …) → hex. `example[i]` becomes the
box hint. The script writes two files:

- `app/src/main/assets/templates/templates.json` + `templates/images/*.jpg` — the bundled
  "Classic memes" pack (50 templates, curated table in the script, images ≤ 800 px JPEG q85).
- `app/src/main/assets/memegen_layouts.json` — `{ "<memegen id>": { "text": [...], "example": [...] } }`
  for every memegen template with a config (≈ 209), used by the memegen.link online source so
  its templates get real geometry without runtime requests.

Re-run with `powershell -ExecutionPolicy Bypass -File tools\fetch-templates.ps1` (needs network;
PowerShell 5.1 with System.Drawing, no Python).
