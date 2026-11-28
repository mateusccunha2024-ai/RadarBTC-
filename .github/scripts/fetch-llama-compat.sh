#!/usr/bin/env bash
# Coloca a IA compatível (PC sem AVX2) dentro do app do Windows: desktop/appResources/windows/jarvis-compat/{avx,sse}/jllama.dll
set -e
dest=desktop/appResources/windows/jarvis-compat
mkdir -p "$dest"
gh release download llama-compat-b4916-v2 -R "$GITHUB_REPOSITORY" -p llama-compat-windows-x64.zip -D "$RUNNER_TEMP" --clobber
7z x -y "$RUNNER_TEMP/llama-compat-windows-x64.zip" -o"$dest" > /dev/null
find "$dest" -type f
