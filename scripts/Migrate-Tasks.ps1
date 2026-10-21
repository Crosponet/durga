#Requires -Version 7.5
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$SourceFile,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$RepositoryPath,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$OrderFile
)

$ErrorActionPreference = 'Stop'
$SourceFile = [System.IO.Path]::GetFullPath($SourceFile, $PWD.Path)
$RepositoryPath = [System.IO.Path]::GetFullPath($RepositoryPath, $PWD.Path)
$OrderFile = [System.IO.Path]::GetFullPath($OrderFile, $PWD.Path)

function Test-InRepository([string]$Path) {
    return $Path.Equals($RepositoryPath, [System.StringComparison]::OrdinalIgnoreCase) -or
        $Path.StartsWith(
            $RepositoryPath.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar,
            [System.StringComparison]::OrdinalIgnoreCase)
}

if ((Test-InRepository $SourceFile) -or (Test-InRepository $OrderFile)) {
    throw 'La source et le fichier ordre doivent rester hors du depot partage.'
}
if (Test-Path -LiteralPath $OrderFile) { throw "Ordre local deja present : $OrderFile" }
if (Test-Path -LiteralPath $RepositoryPath) {
    if (-not (Test-Path -LiteralPath $RepositoryPath -PathType Container)) {
        throw 'Cible non repertoire.'
    }
    if (@(Get-ChildItem -LiteralPath $RepositoryPath -Force).Count) {
        throw 'Le dossier cible doit etre vide.'
    }
}

$raw = Get-Content -LiteralPath $SourceFile -Raw -Encoding utf8
if (-not $raw.TrimStart().StartsWith('[')) { throw 'La source doit etre un tableau JSON.' }
$tasks = @($raw | ConvertFrom-Json -DateKind String)
if (-not $tasks.Count) { throw 'Aucune Task a migrer.' }
$ids = [System.Collections.Generic.HashSet[string]]::new()
$documents = @{}
$positions = @{}
foreach ($task in $tasks) {
    $id = [guid]::Parse([string]$task.id).ToString()
    if (-not $ids.Add($id)) { throw "UUID duplique : $id" }
    if ($null -ne $task.sortOrder) {
        $rank = [double]$task.sortOrder
        if (-not [double]::IsFinite($rank)) { throw "sortOrder invalide : $id" }
        $positions[$id] = $rank
    }
    $document = [ordered]@{}
    foreach ($property in $task.PSObject.Properties) {
        if ($property.Name -notin @('sortOrder', 'revision')) {
            $document[$property.Name] = $property.Value
        }
    }
    $document['id'] = $id
    if (-not $document['workStatus']) { $document['workStatus'] = 'TODO' }
    $documents[$id] = ConvertTo-Json -InputObject $document -Depth 100
}
$orderedIds = @($ids | Sort-Object @{
    Expression = { if ($positions.ContainsKey($_)) { $positions[$_] } else { [double]::PositiveInfinity } }
}, @{ Expression = { $_ } })

# Toutes les conversions sont terminees avant la premiere ecriture.
$backup = "$SourceFile.before-git-$([guid]::NewGuid().ToString('N')).bak"
Copy-Item -LiteralPath $SourceFile -Destination $backup
$taskDirectory = Join-Path $RepositoryPath 'tasks'
New-Item -ItemType Directory -Path $taskDirectory -Force | Out-Null
New-Item -ItemType Directory -Path (Split-Path -Parent $OrderFile) -Force | Out-Null
$utf8 = [System.Text.UTF8Encoding]::new($false)
foreach ($id in $ids) {
    [System.IO.File]::WriteAllText((Join-Path $taskDirectory "$id.json"), $documents[$id], $utf8)
}
[System.IO.File]::WriteAllText(
    $OrderFile, (ConvertTo-Json -InputObject $orderedIds), $utf8)

$files = @(Get-ChildItem -LiteralPath $taskDirectory -Filter '*.json' -File)
if ($files.Count -ne $tasks.Count) { throw 'Nombre de documents incorrect.' }
foreach ($file in $files) {
    $saved = Get-Content -LiteralPath $file.FullName -Raw -Encoding utf8 |
        ConvertFrom-Json -DateKind String
    if ($saved.id -cne $file.BaseName) { throw "UUID/fichier incoherent : $file" }
    if ($saved.PSObject.Properties.Name -contains 'sortOrder' -or
        $saved.PSObject.Properties.Name -contains 'revision') {
        throw "Metadonnees locales dans un document partage : $file"
    }
}
Write-Host "$($files.Count) Tasks converties. Sauvegarde : $backup"
