param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('UP', 'DOWN')]
    [string] $State
)

$ErrorActionPreference = 'Stop'
$runtimeDirectory = Join-Path $PSScriptRoot '.runtime'
[System.IO.Directory]::CreateDirectory($runtimeDirectory) | Out-Null
$statePath = Join-Path $runtimeDirectory 'health-state.txt'
$temporaryPath = Join-Path $runtimeDirectory ('state-' + [guid]::NewGuid().ToString('N') + '.tmp')
$backupPath = $temporaryPath + '.bak'
try {
    [System.IO.File]::WriteAllText($temporaryPath, $State.ToUpperInvariant() + "`n", [System.Text.Encoding]::ASCII)
    # Replace atomically: a health request must never observe a partially written state.
    if ([System.IO.File]::Exists($statePath)) {
        [System.IO.File]::Replace($temporaryPath, $statePath, $backupPath)
    } else {
        [System.IO.File]::Move($temporaryPath, $statePath)
    }
} finally {
    if (Test-Path -LiteralPath $temporaryPath) { Remove-Item -LiteralPath $temporaryPath }
    if (Test-Path -LiteralPath $backupPath) { Remove-Item -LiteralPath $backupPath }
}
Write-Output ('Demo health set to ' + $State.ToUpperInvariant())
