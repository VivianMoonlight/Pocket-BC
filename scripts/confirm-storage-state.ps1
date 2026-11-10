# Run from project root: D:\workspace\mobile-bc
$ErrorActionPreference = "Stop"

$report = "storage-state-confirmation-report.txt"
if (Test-Path $report) {
    Remove-Item $report -Force
}

function Add-Section($title) {
    Add-Content $report ""
    Add-Content $report "============================================================"
    Add-Content $report $title
    Add-Content $report "============================================================"
}

function Run-And-Capture($title, $scriptBlock) {
    Add-Section $title
    try {
        & $scriptBlock 2>&1 | Out-String | Add-Content $report
    } catch {
        Add-Content $report "ERROR: $($_.Exception.Message)"
    }
}

Add-Content $report "Storage state confirmation report"
Add-Content $report "Generated at: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"

Run-And-Capture "Git status" {
    git status --short
}

Run-And-Capture "Room DB version and account-scoped primary keys" {
    Get-ChildItem "data/storage/src/main/kotlin/dev/vivian/mobilebc/data/storage" -Recurse -Include "*.kt" |
        Select-String -Pattern "@Database|version\s*=|@Entity|tableName\s*=|primaryKeys\s*=|@PrimaryKey|fallbackToDestructiveMigration" |
        ForEach-Object {
            "$($_.Path.Replace((Get-Location).Path + '\','')):$($_.LineNumber): $($_.Line.Trim())"
        }
}

Run-And-Capture "Storage entrypoints" {
    Select-String -Path "data/storage/src/main/kotlin/dev/vivian/mobilebc/data/storage/BcPersistenceManager.kt" `
        -Pattern "val shared|val accountRouted|fun forAccount|@Deprecated|ReplaceWith" |
        ForEach-Object {
            "$($_.Path.Replace((Get-Location).Path + '\','')):$($_.LineNumber): $($_.Line.Trim())"
        }
}

Run-And-Capture "Production use of preferred entrypoints" {
    Get-ChildItem @("app/src/main/kotlin", "feature", "domain") -Recurse -Include "*.kt" |
        Select-String -Pattern "\.shared|\.accountRouted|\.forAccount\(" |
        ForEach-Object {
            "$($_.Path.Replace((Get-Location).Path + '\','')):$($_.LineNumber): $($_.Line.Trim())"
        }
}

Run-And-Capture "Possible direct BcPersistenceManager legacy field usage" {
    $patterns = @(
        "persistence\.accounts",
        "persistence\.sessionState",
        "persistence\.settings",
        "persistence\.roomTargets",
        "persistence\.deadLetters",
        "persistence\.conversations",
        "persistence\.messages",
        "persistence\.contactProfiles",
        "persistence\.outbox",
        "persistence\.inbox",
        "persistence\.cursors",
        "persistence\.inboundPersistence"
    )

    Get-ChildItem @("app/src/main/kotlin", "feature", "domain") -Recurse -Include "*.kt" |
        Select-String -Pattern $patterns |
        ForEach-Object {
            "$($_.Path.Replace((Get-Location).Path + '\','')):$($_.LineNumber): $($_.Line.Trim())"
        }
}

Run-And-Capture "DataStore compatibility keys" {
    Get-ChildItem "data/storage/src/main/kotlin/dev/vivian/mobilebc/data/storage" -Recurse -Include "*.kt" |
        Select-String -Pattern "mobile-bc-state|profilesJson|lastAccountName|desiredOnlineAccountName|autoJoinRoomAfterReconnect|lastRoomTarget|password:" |
        ForEach-Object {
            "$($_.Path.Replace((Get-Location).Path + '\','')):$($_.LineNumber): $($_.Line.Trim())"
        }
}

Run-And-Capture "Local patch/backup artifacts at root" {
    Get-ChildItem -Path . -Force |
        Where-Object {
            $_.Name -like "*-files.zip" -or
            $_.Name -like "*-patched.zip" -or
            $_.Name -like "*-patch.zip" -or
            $_.Name -like "backup-before-*" -or
            $_.Name -like "*.bak-*"
        } |
        Select-Object Name, Mode, LastWriteTime
}

Write-Host "Created $report"
