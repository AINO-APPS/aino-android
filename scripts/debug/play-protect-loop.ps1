param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^content://')]
    [string]$ApkUri,
    [Parameter(Mandatory = $true)]
    [string]$ExpectedVersion,
    [string]$PackageName = 'app.aino.mobile'
)

$ErrorActionPreference = 'Stop'
$deviceDump = '/sdcard/aino-installer-diagnostic.xml'

function Read-InstallerScreen {
    & adb shell uiautomator dump $deviceDump | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not capture installer screen' }
    [xml]$screen = (& adb shell cat $deviceDump | Out-String)
    if ($LASTEXITCODE -ne 0) { throw 'Could not read installer screen' }
    return $screen
}

try {
    $screen = Read-InstallerScreen
    if ($screen.SelectNodes('//node') | Where-Object {
        $_.package -eq 'com.android.vending' -and $_.text -match '(?i)harmful'
    }) {
        & adb shell input keyevent KEYCODE_BACK | Out-Null
    }
    & adb shell am start -W -a android.intent.action.VIEW -d $ApkUri `
        -t application/vnd.android.package-archive -f 1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not launch the normal APK installer' }

    $timer = [Diagnostics.Stopwatch]::StartNew()
    $confirmed = $false
    while ($timer.Elapsed.TotalSeconds -lt 90) {
        $screen = Read-InstallerScreen
        $nodes = @($screen.SelectNodes('//node'))
        $harmful = @($nodes | Where-Object {
            $_.package -eq 'com.android.vending' -and $_.text -match '(?i)harmful'
        })
        if ($harmful.Count -gt 0) {
            $harmful | ForEach-Object { Write-Output "PLAY_PROTECT: $($_.text)" }
            throw 'REPRO_RED: Play Protect blocked the APK as harmful'
        }

        $confirmation = $nodes | Where-Object {
            $_.package -match 'packageinstaller' -and
            $_.'resource-id' -eq 'android:id/button1' -and
            $_.text -match '^(Update|Install)$'
        } | Select-Object -First 1
        if (-not $confirmed -and $confirmation) {
            $coordinates = [regex]::Matches($confirmation.bounds, '\d+')
            $centerX = ([int]$coordinates[0].Value + [int]$coordinates[2].Value) / 2
            $centerY = ([int]$coordinates[1].Value + [int]$coordinates[3].Value) / 2
            & adb shell input tap ([int]$centerX) ([int]$centerY) | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'Could not confirm the APK installation' }
            $confirmed = $true
        }

        if ($confirmed -and ($nodes | Where-Object {
            $_.package -match 'packageinstaller' -and $_.text -eq 'App installed.'
        })) {
            $packageInfo = & adb shell dumpsys package $PackageName
            if (-not ($packageInfo | Select-String -Pattern "^\s*versionName=$([regex]::Escape($ExpectedVersion))\s*$")) {
                throw 'REPRO_INCONCLUSIVE: installed version does not match the expected APK'
            }
            Write-Output "REPRO_GREEN: $PackageName $ExpectedVersion installed through the normal installer"
            exit 0
        }
    }
    throw 'REPRO_INCONCLUSIVE: no harmful warning or verified install success within 90 seconds'
} finally {
    & adb shell rm -f $deviceDump | Out-Null
}