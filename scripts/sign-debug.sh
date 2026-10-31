#!/bin/sh
# Re-sign CI APKs with this computer's persistent development key for in-place updates.
set -eu
if [ "$#" -ne 2 ]; then
    echo 'Usage: JAVA_HOME=... ANDROID_HOME=... scripts/sign-debug.sh input.apk output.apk' >&2
    exit 1
fi
sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
keytool_bin="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
key_file="$HOME/.android/debug.keystore"
mkdir -p "$HOME/.android"
if [ ! -f "$key_file" ]; then
    "$keytool_bin" -genkeypair -keystore "$key_file" -storepass android -keypass android \
        -alias androiddebugkey -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 -validity 10000
    chmod 600 "$key_file"
fi
"$sdk_dir/build-tools/34.0.0/apksigner" sign --ks "$key_file" --ks-key-alias androiddebugkey \
    --ks-pass pass:android --key-pass pass:android --out "$2" "$1"
"$sdk_dir/build-tools/34.0.0/apksigner" verify "$2"
