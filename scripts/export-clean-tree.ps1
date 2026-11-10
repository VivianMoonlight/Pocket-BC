param(
    [string]$OutputPath = "clean-tree.txt",
    [switch]$TrackedOnly,
    [switch]$NoGitStatus
)

$ErrorActionPreference = "Stop"

function Normalize-Path {
    param([string]$Path)
    return $Path.Replace("\", "/").Trim()
}

function Should-Exclude {
    param([string]$RelativePath)

    $p = Normalize-Path $RelativePath

    $excludePatterns = @(
        '(^|/)\.git(/|$)',
        '(^|/)\.gradle(/|$)',
        '(^|/)build(/|$)',
        '(^|/)node_modules(/|$)',
        '(^|/)\.idea(/|$)',
        '(^|/)\.vscode(/|$)',
        '(^|/)\.kotlin(/|$)',
        '(^|/)\.cxx(/|$)',
        '(^|/)captures(/|$)',
        '(^|/)out(/|$)',
        '(^|/)dist(/|$)',
        '(^|/)coverage(/|$)',
        '(^|/)tmp(/|$)',
        '(^|/)temp(/|$)',
        '(^|/)logs(/|$)',
        '(^|/)local\.properties$',
        '\.apk$',
        '\.aab$',
        '\.iml$',
        '\.log$'
    )

    foreach ($pattern in $excludePatterns) {
        if ($p -match $pattern) {
            return $true
        }
    }

    return $false
}

try {
    $repoRoot = (& git rev-parse --show-toplevel 2>$null).Trim()
} catch {
    throw "This script must be run inside a Git repository."
}

Push-Location $repoRoot

try {
    $branch = (& git branch --show-current 2>$null).Trim()
    $commit = (& git rev-parse --short HEAD 2>$null).Trim()

    if ($TrackedOnly) {
        $files = & git ls-files
    } else {
        # tracked + untracked, respecting .gitignore
        $files = & git ls-files --cached --others --exclude-standard
    }

    $files = $files |
        ForEach-Object { Normalize-Path $_ } |
        Where-Object { $_ -and -not (Should-Exclude $_) } |
        Sort-Object -Unique

    $topLevelCounts =
        $files |
        ForEach-Object {
            $parts = $_ -split '/'
            if ($parts.Length -gt 1) {
                $parts[0]
            } else {
                "."
            }
        } |
        Group-Object |
        Sort-Object Name

    $lines = New-Object System.Collections.Generic.List[string]

    $lines.Add("# mobile-bc analysis tree")
    $lines.Add("")
    $lines.Add("GeneratedAt: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')")
    $lines.Add("RepoRoot: $repoRoot")
    $lines.Add("Branch: $branch")
    $lines.Add("Commit: $commit")
    $lines.Add("Mode: $(if ($TrackedOnly) { 'tracked-only' } else { 'tracked-and-untracked' })")
    $lines.Add("FileCount: $($files.Count)")
    $lines.Add("")

    if (-not $NoGitStatus) {
        $lines.Add("## git status --short")
        $lines.Add("")

        $status = & git status --short

        if ($status) {
            foreach ($s in $status) {
                $lines.Add($s)
            }
        } else {
            $lines.Add("(clean)")
        }

        $lines.Add("")
    }

    $lines.Add("## top-level counts")
    $lines.Add("")

    foreach ($group in $topLevelCounts) {
        $lines.Add("$($group.Name) $($group.Count)")
    }

    $lines.Add("")
    $lines.Add("## files")
    $lines.Add("")

    foreach ($file in $files) {
        $lines.Add($file)
    }

    $outputFullPath = Join-Path $repoRoot $OutputPath
    $outputDir = Split-Path $outputFullPath -Parent

    if ($outputDir -and -not (Test-Path $outputDir)) {
        New-Item -ItemType Directory -Path $outputDir | Out-Null
    }

    $lines | Set-Content -Path $outputFullPath -Encoding UTF8

    Write-Host "Exported analysis tree:"
    Write-Host $outputFullPath
    Write-Host "Files: $($files.Count)"
} finally {
    Pop-Location
}