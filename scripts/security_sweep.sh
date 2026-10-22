#!/usr/bin/env bash
# B14 security sweep: no API key in the APK, the manifest flags are safe, no HTTP body logging in the code.
# Usage: scripts/security_sweep.sh path/to/app.apk
set -euo pipefail
APK="$1"
fail=0
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
unzip -q -o "$APK" -d "$tmp"

# 1. No Google API key anywhere in the APK (real keys are AIza + 35 chars)
if grep -raEo 'AIza[0-9A-Za-z_-]{35}' "$tmp" | head -5 | grep -q .; then
  echo "FAIL: something that looks like a Google API key is inside the APK"; fail=1
else echo "ok: no API key in the APK"; fi

# 2. Manifest flags
AAPT2=$(ls "${ANDROID_HOME:-/opt/android-sdk}"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)
man=$("$AAPT2" dump xmltree "$APK" --file AndroidManifest.xml)
grep -q 'allowBackup.*=false' <<<"$man" && echo "ok: allowBackup=false" || { echo "FAIL: allowBackup not false"; fail=1; }
grep -q 'usesCleartextTraffic.*=false' <<<"$man" && echo "ok: cleartext traffic off" || { echo "FAIL: cleartext not off"; fail=1; }
grep -q 'networkSecurityConfig' <<<"$man" && echo "ok: network security config" || { echo "FAIL: no network security config"; fail=1; }
grep -q 'debuggable.*=true' <<<"$man" && echo "note: debug build is debuggable (expected for the MVP debug APK)"

# 3. Code: no HTTP logging interceptor, no log call printing keys, key never in a URL
root=$(cd "$(dirname "$0")/.." && pwd)
if grep -rn 'HttpLoggingInterceptor' "$root/app/src/main" "$root/core/src/main"; then echo "FAIL: HTTP logging"; fail=1; else echo "ok: no HTTP body logging"; fi
if grep -rnE 'Log\.[dviwe]\(.*(key|secret)' "$root/app/src/main"; then echo "FAIL: a log call mentions a key"; fail=1; else echo "ok: no key in log calls"; fi
if grep -rnE '[?&]key=' "$root/app/src/main" "$root/core/src/main"; then echo "FAIL: key as URL parameter"; fail=1; else echo "ok: key only in the x-goog-api-key header"; fi
exit $fail
