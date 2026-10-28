$bad = "$([char]0xC2)$([char]0xA7)"
$good = "$([char]0xA7)"
$badEmoji = "$([char]0xE2)$([char]0x9A)$([char]0xA0)$([char]0xEF)$([char]0xB8)$([char]0x8F)"
$goodEmoji = [char]0x26A0 # Warning sign ⚠

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$files = Get-ChildItem -Recurse -Include *.java,*.json
foreach ($f in $files) {
    $content = [System.IO.File]::ReadAllText($f.Path)
    $changed = $false
    if ($content.Contains($bad)) {
        Write-Host "Fixing Â§ in $($f.Path)"
        $content = $content.Replace($bad, $good)
        $changed = $true
    }
    if ($content.Contains($badEmoji)) {
        Write-Host "Fixing emoji in $($f.Path)"
        $content = $content.Replace($badEmoji, $goodEmoji)
        $changed = $true
    }
    if ($changed) {
        [System.IO.File]::WriteAllText($f.Path, $content, $utf8NoBom)
    }
}
