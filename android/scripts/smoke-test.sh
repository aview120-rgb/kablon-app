#!/usr/bin/env bash
# בדיקת עשן על אמולטור: התקנה, פתיחת מסכים, הפעלת שירות הנגישות, ובדיקת קריסות.
set -uo pipefail
APK="$1"
PKG=com.kablon.automation
SVC="$PKG/$PKG.automation.KablonAccessibilityService"

fail() { echo "❌ $1"; adb logcat -d | grep -E -A40 "FATAL EXCEPTION" | head -120; exit 1; }

adb install -r -g "$APK" || fail "install failed"
adb logcat -c

echo "▶ פתיחת המסך הראשי"
adb shell am start -W -n "$PKG/.ui.MainActivity" || fail "MainActivity start failed"
sleep 5
adb shell pidof "$PKG" >/dev/null || fail "app not running after launch"

echo "▶ פתיחת מסך המדיה"
adb shell am start -W -n "$PKG/.ui.MediaActivity" || fail "MediaActivity start failed"
sleep 3

echo "▶ הפעלת שירות הנגישות"
adb shell settings put secure enabled_accessibility_services "$SVC"
adb shell settings put secure accessibility_enabled 1
sleep 5
if adb shell dumpsys accessibility | grep -q "KablonAccessibilityService"; then
  echo "✅ שירות הנגישות נטען"
else
  echo "⚠️ שירות הנגישות לא הופיע ב-dumpsys (לא חוסם)"
fi

echo "▶ Monkey: 400 אירועי UI אקראיים בתוך האפליקציה"
adb shell am start -W -n "$PKG/.ui.MainActivity"
adb shell monkey -p "$PKG" --pct-syskeys 0 --pct-appswitch 0 --throttle 150 -s 42 -v 400 > monkey.txt 2>&1 || true
tail -5 monkey.txt
sleep 2

if adb logcat -d | grep -E "FATAL EXCEPTION" -A3 | grep -q "$PKG"; then
  fail "crash detected"
fi
if grep -q "CRASH: $PKG" monkey.txt; then
  cat monkey.txt | grep -A30 "CRASH" | head -60
  fail "monkey reported a crash"
fi
echo "✅ SMOKE TEST PASSED"
