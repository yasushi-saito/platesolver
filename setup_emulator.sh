adb push /home/saito/Downloads/m42.jpg /sdcard/Download/

if [ ! -f /tmp/h17.zip ]; then
    zip -r /tmp/h17.zip h17*
fi
adb push /tmp/h17.zip /sdcard/Download/
