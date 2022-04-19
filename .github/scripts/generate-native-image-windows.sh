#!/usr/bin/env bash
set -e

COMMAND="cli.base-image.writeNativeImageScript"

./mill.bat -i ci.copyJvm --dest jvm
export JAVA_HOME="$(pwd -W | sed 's,/,\\,g')\\jvm"
export GRAALVM_HOME="$JAVA_HOME"
export PATH="$(pwd)/bin:$PATH"
echo "PATH=$PATH"
./mill.bat -i "$COMMAND" generate-native-image.bat ""
./generate-native-image.bat
