$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$files = Get-ChildItem -Recurse -Include *.java,*.json | Select-String -Pattern "Â§|âš ï¸" | Select-Object -Property Path -Unique
foreach ($f in $files) {
    Write-Host "Processing $($f.Path)"
    $content = [System.IO.File]::ReadAllText($f.Path)
    $newContent = $content.Replace("Â§", "§")
    $newContent = $newContent.Replace("âš ï¸", "⚠")
    [System.IO.File]::WriteAllText($f.Path, $newContent, $utf8NoBom)
}
