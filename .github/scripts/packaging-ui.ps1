# The installed application with a window of the packaging-ui job (packaging/ui-integration-tests), on Windows : started
# with a file, started a second time with a file and a URL (single instance), then opened with a file and a URL as the
# Explorer and the browsers open them, and stopped. The application writes its events in ~/packaging-ui-it/events.txt
# (see Events), which this script checks.
#
# Environment : TOOLKIT (swing, swt), MODE (jvm, native), SCREENSHOT (the file of the screenshot)
$ErrorActionPreference = 'Stop'

$directory = Join-Path $HOME 'packaging-ui-it'
$events = Join-Path $directory 'events.txt'
$documents = Join-Path $env:RUNNER_TEMP 'documents'
$launcher = "$env:ProgramFiles\Packaging UI IT\Packaging UI IT.exe"
Remove-Item $directory, $documents -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $documents, (Split-Path $env:SCREENSHOT) | Out-Null
foreach ($document in 'first', 'second', 'third') { Set-Content -Path "$documents\$document.pui" -Value $document }

# Waits for a line of the events that contains the text
function Wait-Event([string]$text) {
  for ($second = 0; $second -lt 90; $second++) {
    if (Test-Path $events) {
      $line = Get-Content $events | Where-Object { $_.Contains($text) } | Select-Object -First 1
      if ($line) { "event : $line"; return }
    }
    Start-Sleep -Seconds 1
  }
  if (Test-Path $events) { Get-Content $events } else { '(no events)' }
  throw "No event '$text' of the application ($env:TOOLKIT, $env:MODE)"
}

if (-not (Test-Path $launcher)) { throw "$launcher is not installed" }
$first = Start-Process -FilePath $launcher -ArgumentList "`"$documents\first.pui`"" -PassThru
Wait-Event "shown $env:TOOLKIT $env:MODE"
Wait-Event 'open LAUNCH [first.pui] []'
Start-Sleep -Seconds 2
try {
  Add-Type -AssemblyName System.Windows.Forms, System.Drawing
  $bounds = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
  $bitmap = New-Object System.Drawing.Bitmap $bounds.Width, $bounds.Height
  $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
  $graphics.CopyFromScreen($bounds.Location, [System.Drawing.Point]::Empty, $bounds.Size)
  $bitmap.Save($env:SCREENSHOT, [System.Drawing.Imaging.ImageFormat]::Png)
} catch {
  "::warning::No screenshot : $_"
}

# A second launch gives its file and URL to the running instance, and exits with 0
$second = Start-Process -FilePath $launcher -ArgumentList "`"$documents\second.pui`"", 'packaging-ui-it://second' -Wait -PassThru
if ($second.ExitCode -ne 0) { throw "The second launch exited with $($second.ExitCode)" }
Wait-Event 'open ANOTHER_LAUNCH [second.pui] [packaging-ui-it://second]'

# The Explorer and the browsers start the application of the type and of the scheme : a second launch
Start-Process -FilePath "$documents\third.pui"
Wait-Event 'open ANOTHER_LAUNCH [third.pui] []'
Start-Process -FilePath 'packaging-ui-it://third'
Wait-Event 'open ANOTHER_LAUNCH [] [packaging-ui-it://third'

New-Item -ItemType File (Join-Path $directory 'quit') | Out-Null
if (-not $first.WaitForExit(60000)) { throw 'The application did not stop' }
'events :'
Get-Content $events
