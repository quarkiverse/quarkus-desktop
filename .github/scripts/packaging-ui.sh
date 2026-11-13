#!/usr/bin/env bash
# The installed application with a window of the packaging-ui job (packaging/ui-integration-tests), on Linux (in a
# virtual X display) and macOS : started with a file, started a second time with a file and a URL (single instance),
# then asked by the system to open a file and a URL, as the file manager and the browser do, and stopped. The
# application writes its events in ~/packaging-ui-it/events.txt (see Events), which this script checks.
#
# Environment : TOOLKIT (swing, swt), MODE (jvm, native), SCREENSHOT (the file of the screenshot)
set -euo pipefail

events="$HOME/packaging-ui-it/events.txt"
documents="$RUNNER_TEMP/documents"
rm -rf "$HOME/packaging-ui-it" "$documents"
mkdir -p "$documents" "$(dirname "$SCREENSHOT")"
for document in first second third; do echo "$document" > "$documents/$document.pui"; done

# Waits for a line of the events that contains the text
wait_for() {
  for _ in $(seq 1 90); do
    if grep -qF -- "$1" "$events" 2> /dev/null; then
      echo "event : $(grep -F -- "$1" "$events" | head -1)"
      return 0
    fi
    sleep 1
  done
  echo "::error::No event '$1' of the application ($TOOLKIT, $MODE)"
  cat "$events" 2> /dev/null || echo "(no events)"
  return 1
}

if [[ "$(uname)" == Darwin ]]; then
  app="/Applications/Packaging UI IT.app"
  executable="$app/Contents/MacOS/Packaging UI IT"
  # LaunchServices knows the bundle, its document types and its URL schemes
  /System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -f "$app"
  # Started as the Finder starts it, with the file as an argument
  open "$app" --args "$documents/first.pui"
  screenshot() { screencapture -x "$SCREENSHOT" || echo "::warning::No screenshot"; }
  # The Finder and the browsers send the running application an Apple event
  open_with_system() { open "$1"; }
  system_source=SYSTEM
else
  executable=packaging-ui-it
  "$executable" "$documents/first.pui" &
  screenshot() { import -window root "$SCREENSHOT" || echo "::warning::No screenshot"; }
  # The file managers and the browsers start the application of the type with its desktop entry : a second launch
  open_with_system() { gio open "$1"; }
  system_source=ANOTHER_LAUNCH
fi

wait_for "shown $TOOLKIT $MODE"
wait_for "open LAUNCH [first.pui] []"
sleep 2
screenshot

# A second launch gives its file and URL to the running instance, and exits with 0 (macOS has no timeout command :
# the job has its own)
"$executable" "$documents/second.pui" "packaging-ui-it://second"
wait_for "open ANOTHER_LAUNCH [second.pui] [packaging-ui-it://second]"

open_with_system "$documents/third.pui"
wait_for "open $system_source [third.pui] []"
if [[ "$(uname)" == Darwin && "$TOOLKIT" == swt ]]; then
  # SWT never fires SWT.OpenUrl on macOS : it registers its delegate method as application:openUrls:, while AppKit
  # calls application:openURLs: (a bug of SWT 3.132.0 and 3.135.0, see the macOS section of the Desktop SWT guide)
  echo "No URL opened by the system : SWT does not receive them on macOS"
else
  open_with_system "packaging-ui-it://third"
  wait_for "open $system_source [] [packaging-ui-it://third"
fi

touch "$HOME/packaging-ui-it/quit"
for _ in $(seq 1 60); do
  pgrep -f "Packaging UI IT|packaging-ui-it" > /dev/null || break
  sleep 1
done
if pgrep -fl "Packaging UI IT|packaging-ui-it"; then
  echo "::error::The application did not stop"
  exit 1
fi
echo "events :"
cat "$events"
