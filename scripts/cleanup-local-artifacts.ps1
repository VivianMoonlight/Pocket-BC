# Run from project root: D:\workspace\mobile-bc
$ErrorActionPreference = "Stop"

$artifactDir = "_local-artifacts"
New-Item -ItemType Directory -Path $artifactDir -Force | Out-Null

$rootPatterns = @(
    "*-files.zip",
    "*-patched.zip",
    "*-patch.zip",
    "backup-before-*",
    "storage-refactor-*-audit-report.txt",
    "storage-state-confirmation-report.txt"
)

foreach ($pattern in $rootPatterns) {
    Get-ChildItem -Path . -Name $pattern -Force | ForEach-Object {
        $name = $_
        if ($name -eq $artifactDir) {
            return
        }
        $src = Join-Path "." $name
        $dst = Join-Path $artifactDir $name
        if (!(Test-Path $src)) {
            return
        }
        if (Test-Path $dst) {
            Write-Host "Skip existing artifact: $dst"
            return
        }
        Move-Item -Path $src -Destination $dst
        Write-Host "Moved $src -> $dst"
    }
}

Get-ChildItem "data/storage/src/main/kotlin/dev/vivian/mobilebc/data/storage" -Recurse -Force -Filter "*.bak-*" |
    ForEach-Object {
        Write-Host "Removing scratch backup file: $($_.FullName)"
        Remove-Item $_.FullName -Force
    }

Write-Host "Storage local artifact cleanup done. Review with: git status"
