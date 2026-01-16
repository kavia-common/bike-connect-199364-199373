#!/bin/bash

/opt/gradle/gradle-9.0.0/bin/gradle init \
  -Dorg.gradle.buildinit.specs=org.gradle.experimental.android-ecosystem-init:0.1.44 \
  -Dorg.gradle.buildinit.language=java \
  --use-defaults \
  --overwrite
