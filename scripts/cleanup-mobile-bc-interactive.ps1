# save as: cleanup-mobile-bc-interactive.ps1

$ErrorActionPreference = "Stop"

function Ask-Text {
    param(
        [string]$Prompt,
        [string]$Default
    )

    $inputValue = Read-Host "$Prompt [$Default]"
    if ([string]::IsNullOrWhiteSpace($inputValue)) {
        return $Default
    }
    return $inputValue
}

function Ask-YesNo {
    param(
        [string]$Prompt,
        [bool]$Default = $false
    )

    $suffix = if ($Default) { "[Y/n]" } else { "[y/N]" }

    while ($true) {
        $answer = Read-Host "$Prompt $suffix"

        if ([string]::IsNullOrWhiteSpace($answer)) {
            return $Default
        }

        switch ($answer.Trim().ToLower()) {
            "y" { return $true }
            "yes" { return $true }
            "n" { return $false }
            "no" { return $false }
            default { Write-Host "Please enter y or n." }
        }
    }
}

function Ask-Menu {
    param(
        [string]$Prompt,
        [string[]]$Options,
        [int]$DefaultIndex = 0
    )

    Write-Host ""
    Write-Host $Prompt

    for ($i = 0; $i -lt $Options.Count; $i++) {
        $mark = if ($i -eq $DefaultIndex) { "*" } else { " " }
        Write-Host "  $($i + 1).$mark $($Options[$i])"
    }

    while ($true) {
        $answer = Read-Host "Choose 1-$($Options.Count) [default $($DefaultIndex + 1)]"

        if ([string]::IsNullOrWhiteSpace($answer)) {
            return $DefaultIndex
        }

        $number = 0
        if ([int]::TryParse($answer, [ref]$number)) {
            if ($number -ge 1 -and $number -le $Options.Count) {
                return ($number - 1)
            }
        }

        Write-Host "Invalid choice."
    }
}

function Get-RelativePathCompat {
    param(
        [Parameter(Mandatory = $true)]
        [string]$BasePath,

        [Parameter(Mandatory = $true)]
        [string]$TargetPath
    )

    $baseFull = (Resolve-Path -LiteralPath $BasePath).Path
    $targetFull = (Resolve-Path -LiteralPath $TargetPath).Path

    if (-not $baseFull.EndsWith([System.IO.Path]::DirectorySeparatorChar)) {
        $baseFull += [System.IO.Path]::DirectorySeparatorChar
    }

    $baseUri = New-Object System.Uri($baseFull)
    $targetUri = New-Object System.Uri($targetFull)

    $relativeUri = $baseUri.MakeRelativeUri($targetUri)
    $relativePath = [System.Uri]::UnescapeDataString($relativeUri.ToString())

    return $relativePath -replace '/', [System.IO.Path]::DirectorySeparatorChar
}
function Is-In-CleanupTrash {
    param([string]$Path)

    return $Path -match "\\_cleanup-trash-[0-9]{8}-[0-9]{6}(\\|$)"
}

$RootInput = Ask-Text "Project root" "."
$RootPath = (Resolve-Path $RootInput).Path

Write-Host ""
Write-Host "Project root resolved to:"
Write-Host "  $RootPath"

$scopeIndex = Ask-Menu `
    -Prompt "What do you want to clean?" `
    -Options @(
        "Artifacts only: backups, local patch artifacts, zip/report files",
        "Build only: Gradle/Android build outputs",
        "All: artifacts + build outputs"
    ) `
    -DefaultIndex 2

$CleanArtifacts = $scopeIndex -eq 0 -or $scopeIndex -eq 2
$CleanBuild = $scopeIndex -eq 1 -or $scopeIndex -eq 2

$IncludeZips = $false
$IncludeReports = $false
$IncludeTreeFiles = $false
$IncludePackScripts = $false

if ($CleanArtifacts) {
    $IncludeZips = Ask-YesNo "Include root-level *.zip patch/reference packages?" $true
    $IncludeReports = Ask-YesNo "Include generated *-report.txt / *-audit-report.txt / *-compile-error.txt files?" $true
    $IncludeTreeFiles = Ask-YesNo "Include clean-tree*.txt and filetree*.txt snapshots?" $false
    $IncludePackScripts = Ask-YesNo "Include temporary pack-*.ps1 scripts?" $false
}

$Candidates = New-Object System.Collections.Generic.List[object]
$Seen = New-Object System.Collections.Generic.HashSet[string]

function Add-Candidate {
    param(
        [string]$Path,
        [string]$Group,
        [string]$Reason
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }

    $Resolved = (Resolve-Path -LiteralPath $Path).Path

    if ($Resolved -eq $RootPath) {
        return
    }

    if (Is-In-CleanupTrash $Resolved) {
        return
    }

    if ($Seen.Add($Resolved)) {
        $Item = Get-Item -LiteralPath $Resolved -Force

        $Candidates.Add([pscustomobject]@{
            Selected = $true
            Group = $Group
            Reason = $Reason
            Type = if ($Item.PSIsContainer) { "Dir" } else { "File" }
            Path = $Resolved
        })
    }
}

function Add-RootChildrenByName {
    param(
        [string]$Pattern,
        [string]$Group,
        [string]$Reason,
        [switch]$DirsOnly,
        [switch]$FilesOnly
    )

    Get-ChildItem -LiteralPath $RootPath -Force |
        Where-Object {
            $_.Name -like $Pattern -and
            ((-not $DirsOnly) -or $_.PSIsContainer) -and
            ((-not $FilesOnly) -or -not $_.PSIsContainer)
        } |
        ForEach-Object {
            Add-Candidate -Path $_.FullName -Group $Group -Reason $Reason
        }
}

function Add-RecursiveDirsByName {
    param(
        [string[]]$Names,
        [string]$Group,
        [string]$Reason
    )

    Get-ChildItem -LiteralPath $RootPath -Force -Recurse -Directory |
        Where-Object {
            ($Names -contains $_.Name) -and
            (-not (Is-In-CleanupTrash $_.FullName))
        } |
        ForEach-Object {
            Add-Candidate -Path $_.FullName -Group $Group -Reason $Reason
        }
}

if ($CleanArtifacts) {
    Add-RootChildrenByName `
        -Pattern ".backup-*" `
        -DirsOnly `
        -Group "Patch backups" `
        -Reason "phase backup directory"

    Add-RootChildrenByName `
        -Pattern "backup-before-*" `
        -DirsOnly `
        -Group "Patch backups" `
        -Reason "before-apply backup directory"

    Add-Candidate `
        -Path (Join-Path $RootPath "_local-artifacts") `
        -Group "Local artifacts" `
        -Reason "local generated patch/material cache"

    if ($IncludeZips) {
        Add-RootChildrenByName `
            -Pattern "*.zip" `
            -FilesOnly `
            -Group "Zip packages" `
            -Reason "root patch/reference zip"
    }

    if ($IncludeReports) {
        Add-RootChildrenByName `
            -Pattern "*-report.txt" `
            -FilesOnly `
            -Group "Reports" `
            -Reason "generated report"

        Add-RootChildrenByName `
            -Pattern "*-audit-report.txt" `
            -FilesOnly `
            -Group "Reports" `
            -Reason "generated audit report"

        Add-RootChildrenByName `
            -Pattern "*-compile-error.txt" `
            -FilesOnly `
            -Group "Reports" `
            -Reason "captured compile error log"
    }

    if ($IncludeTreeFiles) {
        Add-RootChildrenByName `
            -Pattern "clean-tree*.txt" `
            -FilesOnly `
            -Group "Tree snapshots" `
            -Reason "generated clean tree snapshot"

        Add-RootChildrenByName `
            -Pattern "filetree*.txt" `
            -FilesOnly `
            -Group "Tree snapshots" `
            -Reason "generated raw tree snapshot"
    }

    if ($IncludePackScripts) {
        Add-RootChildrenByName `
            -Pattern "pack-*.ps1" `
            -FilesOnly `
            -Group "Pack scripts" `
            -Reason "temporary packing script"
    }
}

if ($CleanBuild) {
    Add-Candidate `
        -Path (Join-Path $RootPath ".gradle") `
        -Group "Build outputs" `
        -Reason "Gradle local cache"

    Add-Candidate `
        -Path (Join-Path $RootPath ".kotlin") `
        -Group "Build outputs" `
        -Reason "Kotlin local cache"

    Add-Candidate `
        -Path (Join-Path $RootPath ".cxx") `
        -Group "Build outputs" `
        -Reason "Android native build cache"

    Add-Candidate `
        -Path (Join-Path $RootPath "captures") `
        -Group "Build outputs" `
        -Reason "Android Studio captures"

    Add-Candidate `
        -Path (Join-Path $RootPath "app\debug") `
        -Group "Build outputs" `
        -Reason "debug APK metadata/output"

    Add-RecursiveDirsByName `
        -Names @("build") `
        -Group "Build outputs" `
        -Reason "Gradle module build output"
}

if ($Candidates.Count -eq 0) {
    Write-Host ""
    Write-Host "Nothing to clean."
    exit 0
}

Write-Host ""
Write-Host "Found cleanup candidates:"
$Candidates |
    Sort-Object Group, Type, Path |
    Format-Table Group, Type, Reason, Path -AutoSize

Write-Host ""
Write-Host "Now choose groups to include."

$Groups = $Candidates |
    Group-Object Group |
    Sort-Object Name

foreach ($group in $Groups) {
    Write-Host ""
    Write-Host "Group: $($group.Name)"
    Write-Host "Items: $($group.Count)"

    $group.Group |
        Sort-Object Type, Path |
        Select-Object Type, Reason, Path |
        Format-Table -AutoSize

    $keepGroup = Ask-YesNo "Clean this group?" $true

    if (-not $keepGroup) {
        foreach ($item in $group.Group) {
            $item.Selected = $false
        }
    }
}

$Selected = @($Candidates | Where-Object { $_.Selected })

if ($Selected.Count -eq 0) {
    Write-Host ""
    Write-Host "No selected candidates. Nothing changed."
    exit 0
}

Write-Host ""
Write-Host "Final selected candidates:"
$Selected |
    Sort-Object Group, Type, Path |
    Format-Table Group, Type, Reason, Path -AutoSize

$modeIndex = Ask-Menu `
    -Prompt "How should selected files be cleaned?" `
    -Options @(
        "Dry run only, do not change anything",
        "Move to _cleanup-trash-<timestamp>",
        "Permanently delete"
    ) `
    -DefaultIndex 1

if ($modeIndex -eq 0) {
    Write-Host ""
    Write-Host "Dry run selected. Nothing changed."
    exit 0
}

$PermanentlyDelete = $modeIndex -eq 2

Write-Host ""
Write-Host "Selected item count: $($Selected.Count)"
Write-Host "Mode: $(if ($PermanentlyDelete) { 'PERMANENT DELETE' } else { 'MOVE TO TRASH' })"

$confirmText = if ($PermanentlyDelete) {
    "DELETE"
} else {
    "MOVE"
}

$confirmation = Read-Host "Type $confirmText to continue"

if ($confirmation -ne $confirmText) {
    Write-Host "Confirmation failed. Nothing changed."
    exit 0
}

$Timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$TrashRoot = Join-Path $RootPath "_cleanup-trash-$Timestamp"

if (-not $PermanentlyDelete) {
    New-Item -ItemType Directory -Force -Path $TrashRoot | Out-Null
    Write-Host ""
    Write-Host "Moving selected files to:"
    Write-Host "  $TrashRoot"
}

foreach ($item in ($Selected | Sort-Object { $_.Path.Length } -Descending)) {
    if (-not (Test-Path -LiteralPath $item.Path)) {
        continue
    }

    if ($PermanentlyDelete) {
        Remove-Item -LiteralPath $item.Path -Recurse -Force
        Write-Host "Deleted: $($item.Path)"
    } else {
        $relative = Get-RelativePathCompat -BasePath $RootPath -TargetPath $item.Path
        $destination = Join-Path $TrashRoot $relative
        $destinationParent = Split-Path $destination -Parent

        New-Item -ItemType Directory -Force -Path $destinationParent | Out-Null
        Move-Item -LiteralPath $item.Path -Destination $destination -Force

        Write-Host "Moved: $($item.Path)"
    }
}

Write-Host ""
Write-Host "Cleanup done."

if (-not $PermanentlyDelete) {
    Write-Host ""
    Write-Host "Trash folder:"
    Write-Host "  $TrashRoot"
    Write-Host ""
    Write-Host "After checking everything is fine, you can delete it manually:"
    Write-Host "  Remove-Item -LiteralPath `"$TrashRoot`" -Recurse -Force"
}
