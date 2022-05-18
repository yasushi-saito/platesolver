#!/bin/bash
set -ex
adb push /home/saito/Downloads/m42.jpg /sdcard/Download/

if [ ! -f /tmp/h17.zip ]; then
    (cd /opt/astap; zip -r /tmp/h17.zip h17*)
fi
adb push /tmp/h17.zip /sdcard/Download/
#adb push /home/saito/src/astap_android/astap.fpc/command-line_version/astap_cli /sdcard/Download/
#adb push /home/saito/src/astap_android/astap.fpc/command-line_version/astap_cli /storage/emulated/0/Android/data/com.yasushisaito.platesolver/files/
#adb shell chmod 755 /storage/emulated/0/Android/data/com.yasushisaito.platesolver/files/astap_cli
