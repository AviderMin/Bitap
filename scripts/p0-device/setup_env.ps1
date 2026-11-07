# Prepare device for P0 acceptance: reverse port 8769, Bitap IME, portrait, screen awake.
param(
  [int]$Port = 8769,
  [string]$Serial = "fe4ab855"
)
$ErrorActionPreference = "Stop"
adb -s $Serial reverse tcp:$Port tcp:$Port | Out-Null
adb -s $Serial shell ime enable com.bitap.keyboard/.ime.BitapImeService | Out-Null
adb -s $Serial shell ime set com.bitap.keyboard/.ime.BitapImeService | Out-Null
adb -s $Serial shell settings put system accelerometer_rotation 0 | Out-Null
adb -s $Serial shell settings put system user_rotation 0 | Out-Null
adb -s $Serial shell input keyevent KEYCODE_WAKEUP | Out-Null
adb -s $Serial shell wm dismiss-keyguard | Out-Null
Write-Output "reverse:"
adb -s $Serial reverse --list
Write-Output "default IME:"
adb -s $Serial shell settings get secure default_input_method
