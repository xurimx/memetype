<#
.SYNOPSIS
  Rebuilds the bundled template pack (app/src/main/assets/templates) and the memegen layout
  table (app/src/main/assets/memegen_layouts.json) from the network.

.DESCRIPTION
  Sources:
    * memegen.link (github.com/jacebrowning/memegen, MIT): templates/<id>/default.{png,jpg} and
      config.yml with text-box geometry in the same normalised fields our pack format uses.
    * Imgflip get_memes (https://api.imgflip.com/get_memes): the 100 most captioned templates,
      used for the classics memegen does not have (image + box_count only).
  Images are resized to <= 800 px wide and saved as JPEG (quality 85). The curated table below
  decides what ships; ids listed there that do not exist stop the script (fix the table).

  The meme images belong to their creators and are distributed here as widely shared meme
  templates, with a source link per template. Layouts come from memegen.link (MIT).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\fetch-templates.ps1
#>
param(
    [string]$Work = (Join-Path $env:TEMP 'memetype-fetch'),
    [string]$Assets = (Join-Path $PSScriptRoot '..\app\src\main\assets'),
    [int]$MaxWidth = 800,
    [int]$JpegQuality = 85
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$inv = [System.Globalization.CultureInfo]::InvariantCulture

# ---- curated pack: Imgflip popularity order ------------------------------------------------
# id = our stable template id (keep existing ones: recents/layouts/history key on "bundled/<id>")
# memegen = template folder in the memegen repo (image + geometry); imgflip = exact name in get_memes
$Curated = @(
    @{ id = 'drake';             name = 'Drake Hotline Bling';            memegen = 'drake';            tags = 'reaction,popular' },
    @{ id = 'two_buttons';       name = 'Two Buttons';                    memegen = 'ds';               tags = 'reaction,popular' },
    @{ id = 'distracted';        name = 'Distracted Boyfriend';           memegen = 'db';               tags = 'reaction,popular' },
    @{ id = 'bernie';            name = 'Bernie Asking For Your Support'; imgflip = 'Bernie I Am Once Again Asking For Your Support'; tags = 'popular' },
    @{ id = 'uno_draw_25';       name = 'UNO Draw 25 Cards';              imgflip = 'UNO Draw 25 Cards'; tags = 'reaction,popular' },
    @{ id = 'left_exit';         name = 'Left Exit 12 Off Ramp';          memegen = 'exit';             tags = 'popular' },
    @{ id = 'always_has_been';   name = 'Always Has Been';                memegen = 'astronaut';        tags = 'popular' },
    @{ id = 'anakin_padme';      name = 'Anakin Padme 4 Panel';           imgflip = 'Anakin Padme 4 Panel'; tags = 'popular' },
    @{ id = 'epic_handshake';    name = 'Epic Handshake';                 memegen = 'handshake';        tags = 'popular' },
    @{ id = 'grus_plan';         name = "Gru's Plan";                     memegen = 'gru';              tags = 'popular' },
    @{ id = 'running_balloon';   name = 'Running Away Balloon';           memegen = 'balloon';          tags = 'popular' },
    @{ id = 'waiting_skeleton';  name = 'Waiting Skeleton';               imgflip = 'Waiting Skeleton'; tags = 'reaction,popular' },
    @{ id = 'sad_pablo';         name = 'Sad Pablo Escobar';              imgflip = 'Sad Pablo Escobar'; tags = 'reaction' },
    @{ id = 'change_my_mind';    name = 'Change My Mind';                 memegen = 'cmm';              tags = 'popular' },
    @{ id = 'disaster_girl';     name = 'Disaster Girl';                  memegen = 'disastergirl';     tags = 'reaction,popular' },
    @{ id = 'trade_offer';       name = 'Trade Offer';                    imgflip = 'Trade Offer';      tags = 'popular' },
    @{ id = 'batman_robin';      name = 'Batman Slapping Robin';          imgflip = 'Batman Slapping Robin'; tags = 'reaction,classic' },
    @{ id = 'ancient_aliens';    name = 'Ancient Aliens';                 memegen = 'aag';              tags = 'classic' },
    @{ id = 'x_everywhere';      name = 'X, X Everywhere';                memegen = 'buzz';             tags = 'classic' },
    @{ id = 'mocking_spongebob'; name = 'Mocking Spongebob';              memegen = 'spongebob';        tags = 'reaction,popular' },
    @{ id = 'one_does_not';      name = 'One Does Not Simply';            memegen = 'mordor';           tags = 'classic' },
    @{ id = 'woman_yelling_cat'; name = 'Woman Yelling at a Cat';         memegen = 'woman-cat';        tags = 'reaction,animals,popular' },
    @{ id = 'is_this_a_pigeon';  name = 'Is This a Pigeon?';              memegen = 'pigeon';           tags = 'reaction' },
    @{ id = 'same_picture';      name = "They're the Same Picture";       memegen = 'same';             tags = 'popular' },
    @{ id = 'expanding_brain';   name = 'Expanding Brain';                imgflip = 'Expanding Brain';  tags = 'popular' },
    @{ id = 'tuxedo_pooh';       name = 'Tuxedo Winnie the Pooh';         memegen = 'pooh';             tags = 'reaction,popular' },
    @{ id = 'buff_doge_cheems';  name = 'Buff Doge vs. Cheems';           imgflip = 'Buff Doge vs. Cheems'; tags = 'animals,popular' },
    @{ id = 'this_is_fine';      name = 'This Is Fine';                   memegen = 'fine';             tags = 'reaction,animals,classic' },
    @{ id = 'oprah';             name = 'Oprah You Get a Car';            memegen = 'oprah';            tags = 'classic' },
    @{ id = 'monkey_puppet';     name = 'Monkey Puppet';                  imgflip = 'Monkey Puppet';    tags = 'reaction,animals' },
    @{ id = 'spiderman_pointing';name = 'Spider-Man Pointing';            memegen = 'spiderman';        tags = 'popular' },
    @{ id = 'roll_safe';         name = 'Roll Safe';                      memegen = 'rollsafe';         tags = 'reaction,classic' },
    @{ id = 'boardroom';         name = 'Boardroom Meeting Suggestion';   imgflip = 'Boardroom Meeting Suggestion'; tags = 'classic' },
    @{ id = 'hide_the_pain';     name = 'Hide the Pain Harold';           memegen = 'harold';           tags = 'reaction,classic' },
    @{ id = 'evil_kermit';       name = 'Evil Kermit';                    imgflip = 'Evil Kermit';      tags = 'reaction' },
    @{ id = 'success_kid';       name = 'Success Kid';                    memegen = 'success';          tags = 'reaction,classic' },
    @{ id = 'grumpy_cat';        name = 'Grumpy Cat';                     memegen = 'grumpycat';        tags = 'animals,classic' },
    @{ id = 'bad_luck_brian';    name = 'Bad Luck Brian';                 memegen = 'blb';              tags = 'classic' },
    @{ id = 'philosoraptor';     name = 'Philosoraptor';                  memegen = 'philosoraptor';    tags = 'classic' },
    @{ id = 'first_world';       name = 'First World Problems';           memegen = 'fwp';              tags = 'classic' },
    @{ id = 'futurama_fry';      name = 'Futurama Fry';                   memegen = 'fry';              tags = 'reaction,classic' },
    @{ id = 'y_u_no';            name = 'Y U No';                         memegen = 'yuno';             tags = 'classic' },
    @{ id = 'doge';              name = 'Doge';                           memegen = 'doge';             tags = 'animals,classic' },
    @{ id = 'galaxy_brain';      name = 'Galaxy Brain';                   memegen = 'gb';               tags = 'popular' },
    @{ id = 'panik_kalm';        name = 'Panik Kalm Panik';               memegen = 'panik-kalm-panik'; tags = 'reaction,popular' },
    @{ id = 'stonks';            name = 'Stonks';                         memegen = 'stonks';           tags = 'reaction,popular' },
    @{ id = 'surprised_pikachu'; name = 'Surprised Pikachu';              imgflip = 'Surprised Pikachu'; tags = 'reaction,popular' },
    @{ id = 'wonka';             name = 'Condescending Wonka';            memegen = 'wonka';            tags = 'classic' },
    @{ id = 'kermit_tea';        name = "But That's None of My Business"; memegen = 'kermit';           tags = 'reaction,classic' },
    @{ id = 'patrick';           name = 'Push It Somewhere Else Patrick'; memegen = 'patrick';          tags = 'classic' }
)

# ---- helpers -------------------------------------------------------------------------------
function Get-MemegenTemplates {
    $dir = Join-Path $Work 'memegen-main\templates'
    if (-not (Test-Path (Join-Path $dir 'drake\config.yml'))) {
        New-Item -ItemType Directory -Force $Work | Out-Null
        $tgz = Join-Path $Work 'memegen.tar.gz'
        Write-Host "Downloading memegen repository tarball..."
        Invoke-WebRequest 'https://github.com/jacebrowning/memegen/archive/refs/heads/main.tar.gz' -OutFile $tgz
        # bsdtar (Windows 10+) extracts only members matching the pattern; other paths in the repo are not valid on NTFS.
        & tar.exe -xzf $tgz -C $Work 'memegen-main/templates/*' 2>$null
    }
    return $dir
}

# Flat YAML reader for memegen's config.yml: top-level scalars, `text:` list of maps, `example:` / `keywords:` lists of scalars.
function Read-MemegenConfig([string]$path) {
    $cfg = [ordered]@{ name = ''; source = ''; keywords = @(); text = @(); example = @() }
    $section = ''
    $item = $null
    foreach ($raw in Get-Content -LiteralPath $path -Encoding UTF8) {
        $line = $raw.TrimEnd()
        if ($line -eq '' -or $line.TrimStart().StartsWith('#')) { continue }
        if ($line -match '^([A-Za-z_]+):\s*(.*)$') {
            if ($item) { $cfg.text += $item; $item = $null }
            $section = $Matches[1]
            $value = $Matches[2].Trim()
            if ($section -in @('name', 'source') -and $value -ne '') { $cfg[$section] = $value.Trim("'`"") }
            continue
        }
        if ($line -match '^\s+-\s*(.*)$') {
            $rest = $Matches[1]
            if ($section -eq 'text') {
                if ($item) { $cfg.text += $item }
                $item = [ordered]@{}
                if ($rest -match '^([A-Za-z_]+):\s*(.*)$') { $item[$Matches[1]] = $Matches[2].Trim().Trim("'`"") }
            } elseif ($section -in @('example', 'keywords')) {
                if ($rest -ne '' -and $rest -ne '~' -and $rest -ne 'null') { $cfg[$section] += $rest.Trim("'`"") }
            }
            continue
        }
        if ($item -ne $null -and $line -match '^\s+([A-Za-z_]+):\s*(.*)$') {
            $item[$Matches[1]] = $Matches[2].Trim().Trim("'`"")
        }
    }
    if ($item) { $cfg.text += $item }
    return $cfg
}

$ColorNames = @{ khaki = '#F0E68C'; palegoldenrod = '#EEE8AA'; gold = '#FFD700'; orange = '#FFA500'; pink = '#FFC0CB'; brown = '#A52A2A' }

# memegen text entry -> our pack text entry (same geometry fields; fonts/outline mapped).
function Convert-TextBox($t, [string]$hint) {
    $font = if ($t.font) { $t.font } else { 'thick' }
    $ourFont = switch ($font) { 'thick' { 'thick' } default { 'sans' } }
    $color = if ($t.color) { $t.color } else { 'white' }
    if ($ColorNames.ContainsKey($color)) { $color = $ColorNames[$color] }
    $style = if ($t.style -eq 'upper') { 'upper' } else { 'none' }
    $box = [ordered]@{
        anchor_x = [double]::Parse($t.anchor_x, $inv)
        anchor_y = [double]::Parse($t.anchor_y, $inv)
        scale_x  = [double]::Parse($t.scale_x, $inv)
        scale_y  = [double]::Parse($t.scale_y, $inv)
        align    = if ($t.align) { $t.align } else { 'center' }
        style    = $style
        color    = $color
        outline  = if ($ourFont -eq 'thick') { 'black' } else { 'none' }
        font     = $ourFont
        angle    = if ($t.angle) { [double]::Parse($t.angle, $inv) } else { 0.0 }
    }
    if ($hint) { $box.hint = $hint }
    return $box
}

function Save-Resized([string]$src, [string]$dest, [int]$max, [int]$quality) {
    $img = [System.Drawing.Image]::FromFile($src)
    try {
        $w = $img.Width; $h = $img.Height
        if ($w -gt $max) { $h = [int][Math]::Round($h * $max / $w); $w = $max }
        $bmp = New-Object System.Drawing.Bitmap $w, $h
        try {
            $g = [System.Drawing.Graphics]::FromImage($bmp)
            $g.Clear([System.Drawing.Color]::White)   # PNG alpha -> white, as memegen renders it
            $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $g.DrawImage($img, 0, 0, $w, $h)
            $g.Dispose()
            $codec = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq 'image/jpeg' }
            $params = New-Object System.Drawing.Imaging.EncoderParameters 1
            $params.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter ([System.Drawing.Imaging.Encoder]::Quality), ([long]$quality)
            $bmp.Save($dest, $codec, $params)
            return @($w, $h)
        } finally { $bmp.Dispose() }
    } finally { $img.Dispose() }
}

# ---- run -----------------------------------------------------------------------------------
$templatesDir = Get-MemegenTemplates
Write-Host "Fetching Imgflip get_memes..."
$imgflip = (Invoke-RestMethod 'https://api.imgflip.com/get_memes').data.memes

$outDir = Join-Path $Assets 'templates'
$imgDir = Join-Path $outDir 'images'
New-Item -ItemType Directory -Force $imgDir | Out-Null
Get-ChildItem -LiteralPath $outDir -Filter '*.png' -File | Remove-Item -Force   # the old placeholders
Get-ChildItem -LiteralPath $imgDir -File | Remove-Item -Force

$templates = @()
foreach ($row in $Curated) {
    $entry = [ordered]@{ id = $row.id; name = $row.name; file = "templates/images/$($row.id).jpg" }
    $tags = @($row.tags -split ',')
    if ($row.memegen) {
        $dir = Join-Path $templatesDir $row.memegen
        if (-not (Test-Path $dir)) { throw "memegen template '$($row.memegen)' not found for $($row.id)" }
        $image = @('default.png', 'default.jpg') | ForEach-Object { Join-Path $dir $_ } | Where-Object { Test-Path $_ } | Select-Object -First 1
        if (-not $image) { throw "no png/jpg image for memegen '$($row.memegen)'" }
        $cfg = Read-MemegenConfig (Join-Path $dir 'config.yml')
        $size = Save-Resized $image (Join-Path $imgDir "$($row.id).jpg") $MaxWidth $JpegQuality
        $entry.width = $size[0]; $entry.height = $size[1]
        if ($cfg.source) { $entry.source = $cfg.source }
        $entry.keywords = @($tags + $cfg.keywords | Where-Object { $_ } | Select-Object -Unique)
        if ($cfg.example.Count -gt 0) { $entry.example = @($cfg.example) }
        $boxes = @()
        for ($i = 0; $i -lt $cfg.text.Count; $i++) {
            $hint = if ($i -lt $cfg.example.Count) { $cfg.example[$i] } else { '' }
            $boxes += Convert-TextBox $cfg.text[$i] $hint
        }
        if ($boxes.Count -eq 0) { throw "no text boxes in config for '$($row.memegen)'" }
        $entry.text = $boxes
        Write-Host ("  {0,-20} memegen/{1} {2}x{3} {4} boxes" -f $row.id, $row.memegen, $size[0], $size[1], $boxes.Count)
    } else {
        $meme = $imgflip | Where-Object { $_.name -eq $row.imgflip } | Select-Object -First 1
        if (-not $meme) { throw "Imgflip meme '$($row.imgflip)' not in get_memes for $($row.id)" }
        $tmp = Join-Path $Work ("imgflip-" + $meme.id + [IO.Path]::GetExtension($meme.url))
        if (-not (Test-Path $tmp)) { Invoke-WebRequest $meme.url -OutFile $tmp }
        $size = Save-Resized $tmp (Join-Path $imgDir "$($row.id).jpg") $MaxWidth $JpegQuality
        $entry.width = $size[0]; $entry.height = $size[1]
        $entry.source = "https://imgflip.com/memegenerator/$($meme.id)"
        $entry.keywords = @($tags)
        $entry.boxes = [int]$meme.box_count
        Write-Host ("  {0,-20} imgflip/{1} {2}x{3} {4} boxes (synthesised)" -f $row.id, $meme.id, $size[0], $size[1], $meme.box_count)
    }
    $templates += $entry
}

$pack = [ordered]@{
    format   = 'memekb-pack'
    version  = 1
    id       = 'bundled'
    name     = 'Classic memes'
    author   = 'Memetype'
    licence  = 'Images belong to their creators and are distributed as widely shared meme templates; text layouts from memegen.link (MIT). See each template''s source link.'
    templates = $templates
}
$json = $pack | ConvertTo-Json -Depth 8
[IO.File]::WriteAllText((Join-Path $outDir 'templates.json'), $json, (New-Object System.Text.UTF8Encoding $false))
Write-Host "Wrote templates.json with $($templates.Count) templates"

# ---- memegen layout table for the online source --------------------------------------------
$layouts = [ordered]@{}
foreach ($dir in Get-ChildItem -LiteralPath $templatesDir -Directory | Where-Object { -not $_.Name.StartsWith('_') }) {
    $cfgPath = Join-Path $dir.FullName 'config.yml'
    if (-not (Test-Path $cfgPath)) { continue }
    $cfg = Read-MemegenConfig $cfgPath
    if ($cfg.text.Count -eq 0) { continue }
    $boxes = @()
    for ($i = 0; $i -lt $cfg.text.Count; $i++) {
        $hint = if ($i -lt $cfg.example.Count) { $cfg.example[$i] } else { '' }
        try { $boxes += Convert-TextBox $cfg.text[$i] $hint } catch { Write-Warning "skip $($dir.Name) box $i : $_"; $boxes = @(); break }
    }
    if ($boxes.Count -eq 0) { continue }
    $layouts[$dir.Name] = [ordered]@{ text = $boxes; example = @($cfg.example) }
}
$layoutJson = $layouts | ConvertTo-Json -Depth 6 -Compress
[IO.File]::WriteAllText((Join-Path $Assets 'memegen_layouts.json'), $layoutJson, (New-Object System.Text.UTF8Encoding $false))
Write-Host "Wrote memegen_layouts.json with $($layouts.Count) layouts"
