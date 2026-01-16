#!/bin/bash
cd /home/kavia/workspace/code-generation/bike-connect-199364-199373/android_app
./gradlew lint
LINT_EXIT_CODE=$?
if [ $LINT_EXIT_CODE -ne 0 ]; then
   exit 1
fi

