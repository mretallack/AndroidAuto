#!/bin/bash
# dump_key.sh - Extract the Android Auto private key from a running phone
#
# Methods (in order of preference):
#
# Method 1: Frida (needs root or debuggable AA app)
#   frida -U -n "com.google.android.projection.gearhead" -l tools/dump_key_frida.js
#
# Method 2: Memory dump with pmdump (needs root)
#   adb shell su -c "cat /proc/$(pidof com.google.android.projection.gearhead)/mem" > mem.bin
#   strings mem.bin | grep -A50 "BEGIN PRIVATE"
#
# Method 3: Use Android's KeyStore debugging (if key is stored in AndroidKeyStore)
#   adb shell am start -n com.google.android.projection.gearhead/.debug.KeyDumpActivity
#
# Method 4: Repackage the AA APK as debuggable, then use JDWP to call getPrivateKey()
#   See below.

ADB="${ADB:-/home/mark/android-sdk/platform-tools/adb}"
AA_PKG="com.google.android.projection.gearhead"

echo "=== Android Auto Private Key Extraction ==="
echo ""

# Check device
if ! $ADB devices | grep -q "device$"; then
    echo "ERROR: No device connected"
    exit 1
fi

# Check if AA is installed
if ! $ADB shell pm list packages | grep -q "$AA_PKG"; then
    echo "ERROR: Android Auto not installed"
    exit 1
fi

echo "Android Auto is installed."
echo ""

# Method: Try to read from AA's shared preferences (needs run-as or root)
echo "Attempting to read stored cert from app data..."
CERT=$($ADB shell run-as $AA_PKG cat shared_prefs/*.xml 2>/dev/null | grep -o 'MII[A-Za-z0-9+/=]*')
if [ -n "$CERT" ]; then
    echo "Found certificate data in shared prefs!"
    echo "$CERT" | head -1
else
    echo "Cannot access app data (need root or debuggable app)"
fi

echo ""
echo "=== Recommended: Use Frida ==="
echo "1. Install frida-server on phone (needs root):"
echo "   adb push frida-server /data/local/tmp/"
echo "   adb shell su -c '/data/local/tmp/frida-server &'"
echo ""
echo "2. Run the extraction script:"
echo "   frida -U -n '$AA_PKG' -l tools/dump_key_frida.js"
echo ""
echo "3. Trigger TLS by connecting phone to head unit"
echo ""
echo "4. The key will be printed in hex and base64 format"
echo ""
echo "5. Convert to PEM:"
echo "   echo '-----BEGIN PRIVATE KEY-----' > carservice_key.pem"
echo "   echo '<base64 from frida output>' >> carservice_key.pem"  
echo "   echo '-----END PRIVATE KEY-----' >> carservice_key.pem"
