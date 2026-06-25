#!/bin/bash
# UnoOne Model Push Script for macOS / Linux
# Pushes model folders from local laptop to Xiaomi 14 via ADB

set -e

PACKAGE_NAME="com.unoone.agent"
DEVICE_MODEL_PATH="/sdcard/Android/data/${PACKAGE_NAME}/files/models"
LOCAL_MODELS_ROOT="../../models"

echo "=========================================="
echo " UnoOne Model Push Script"
echo "=========================================="
echo

# 1. Check adb devices
echo "[1/8] Checking ADB devices..."
if ! adb devices | grep -q "device$"; then
    echo "ERROR: No device found. Connect Xiaomi 14 and enable USB debugging."
    exit 1
fi
echo "OK: Device connected."
echo

# 2. Create directories
echo "[2/8] Creating model directories on device..."
adb shell mkdir -p "${DEVICE_MODEL_PATH}/gemma-local"
adb shell mkdir -p "${DEVICE_MODEL_PATH}/sherpa-asr-en"
adb shell mkdir -p "${DEVICE_MODEL_PATH}/sherpa-asr-whisper"
adb shell mkdir -p "${DEVICE_MODEL_PATH}/sherpa-tts-en"
for L in hin ben tam tel kan mal; do adb shell mkdir -p "${DEVICE_MODEL_PATH}/sherpa-tts-${L}"; done
adb shell mkdir -p "${DEVICE_MODEL_PATH}/vad"
adb shell mkdir -p "${DEVICE_MODEL_PATH}/punctuation"
adb shell mkdir -p "${DEVICE_MODEL_PATH}/ocr-optional"
echo "OK: Directories created."
echo

# 3. Push Gemma model
echo "[3/8] Pushing Gemma model..."
if [ -d "${LOCAL_MODELS_ROOT}/gemma-local" ]; then
    adb push "${LOCAL_MODELS_ROOT}/gemma-local" "${DEVICE_MODEL_PATH}/gemma-local"
    echo "OK: Gemma pushed."
else
    echo "WARN: gemma-local folder not found locally. Skipping."
fi
echo

# 4. Push Sherpa ASR (English transducer + shared Indic whisper)
echo "[4/8] Pushing Sherpa ASR models..."
if [ -d "${LOCAL_MODELS_ROOT}/sherpa-asr-en" ]; then
    adb push "${LOCAL_MODELS_ROOT}/sherpa-asr-en" "${DEVICE_MODEL_PATH}/sherpa-asr-en"
    echo "OK: English ASR pushed."
else
    echo "WARN: sherpa-asr-en folder not found locally. Skipping."
fi
if [ -d "${LOCAL_MODELS_ROOT}/sherpa-asr-whisper" ]; then
    adb push "${LOCAL_MODELS_ROOT}/sherpa-asr-whisper" "${DEVICE_MODEL_PATH}/sherpa-asr-whisper"
    echo "OK: Indic ASR (whisper-tiny) pushed."
else
    echo "WARN: sherpa-asr-whisper folder not found locally. Skipping."
fi
echo

# 5. Push Sherpa TTS (English Coqui + per-language MMS)
echo "[5/8] Pushing Sherpa TTS models..."
if [ -d "${LOCAL_MODELS_ROOT}/sherpa-tts-en" ]; then
    adb push "${LOCAL_MODELS_ROOT}/sherpa-tts-en" "${DEVICE_MODEL_PATH}/sherpa-tts-en"
    echo "OK: English TTS pushed."
else
    echo "WARN: sherpa-tts-en folder not found locally. Skipping."
fi
for L in hin ben tam tel kan mal; do
    if [ -d "${LOCAL_MODELS_ROOT}/sherpa-tts-${L}" ]; then
        adb push "${LOCAL_MODELS_ROOT}/sherpa-tts-${L}" "${DEVICE_MODEL_PATH}/sherpa-tts-${L}"
        echo "OK: TTS ${L} pushed."
    fi
done
echo

# 6. Push VAD
echo "[6/8] Pushing VAD model..."
if [ -d "${LOCAL_MODELS_ROOT}/vad" ]; then
    adb push "${LOCAL_MODELS_ROOT}/vad" "${DEVICE_MODEL_PATH}/vad"
    echo "OK: VAD pushed."
else
    echo "WARN: vad folder not found locally. Skipping."
fi
echo

# 7. Push optional models
echo "[7/8] Pushing optional models..."
if [ -d "${LOCAL_MODELS_ROOT}/punctuation" ]; then
    adb push "${LOCAL_MODELS_ROOT}/punctuation" "${DEVICE_MODEL_PATH}/punctuation"
    echo "OK: Punctuation pushed."
fi
if [ -d "${LOCAL_MODELS_ROOT}/ocr-optional" ]; then
    adb push "${LOCAL_MODELS_ROOT}/ocr-optional" "${DEVICE_MODEL_PATH}/ocr-optional"
    echo "OK: OCR pushed."
fi
echo

# 8. Verify
echo "[8/8] Verifying files on device..."
adb shell ls -R "${DEVICE_MODEL_PATH}"
echo

echo "=========================================="
echo " Model push complete."
echo " Path on device: ${DEVICE_MODEL_PATH}"
echo "=========================================="
