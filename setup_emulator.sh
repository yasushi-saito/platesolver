#!/bin/bash
set -ex
adb push /home/saito/astrometry-images/*.jpg /sdcard/Download/
adb push /home/saito/astrometry-images/*.png /sdcard/Download/

if [ ! -f /tmp/h17.zip ]; then
    (cd /opt/astap; zip -r /tmp/h17.zip h17*)
fi
if [ ! -f /tmp/h18.zip ]; then
    (cd /opt/astap; zip -r /tmp/h18.zip h18*)
fi

if [ ! -f /tmp/v17.zip ]; then
    (cd /opt/astap; zip -r /tmp/v17.zip v17* w08*)
fi

adb push /tmp/h17.zip /sdcard/Download/
#adb push /home/saito/src/astap_android/astap.fpc/command-line_version/astap_cli /sdcard/Download/
#adb push /home/saito/src/astap_android/astap.fpc/command-line_version/astap_cli /storage/emulated/0/Android/data/com.yasushisaito.platesolver/files/
#adb shell chmod 755 /storage/emulated/0/Android/data/com.yasushisaito.platesolver/files/astap_cli
