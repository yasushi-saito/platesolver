#!/bin/bash

set -ex

if [ ! -f app/src/main/jniLibs/x86_64/libastapcli.so ] then
   mkdir -p app/src/main/jniLibs/{x86_64,arm64-v8a}
   cp $HOME/srcr/astap_android/astap_cli app/src/main/jniLibs/x86_64
   cp $HOME/srcr/astap_android/astap_cli app/src/main/jniLibs/arm64-v8a
fi
