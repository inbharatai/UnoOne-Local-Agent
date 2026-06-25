@echo off
REM UnoOne Model Push Script for Windows
REM Pushes model folders from local laptop to Xiaomi 14 via ADB

set PACKAGE_NAME=com.unoone.agent
set DEVICE_MODEL_PATH=/sdcard/Android/data/%PACKAGE_NAME%/files/models
set LOCAL_MODELS_ROOT=..\..\models

echo ==========================================
echo  UnoOne Model Push Script
echo ==========================================
echo.

REM 1. Check adb devices
echo [1/8] Checking ADB devices...
adb devices | findstr "device$" >nul
if errorlevel 1 (
    echo ERROR: No device found. Connect Xiaomi 14 and enable USB debugging.
    exit /b 1
)
echo OK: Device connected.
echo.

REM 2. Create directories
echo [2/8] Creating model directories on device...
adb shell mkdir -p %DEVICE_MODEL_PATH%/gemma-local
adb shell mkdir -p %DEVICE_MODEL_PATH%/sherpa-asr-en
adb shell mkdir -p %DEVICE_MODEL_PATH%/sherpa-asr-whisper
adb shell mkdir -p %DEVICE_MODEL_PATH%/sherpa-tts-en
for %%L in (hin ben tam tel kan mal) do adb shell mkdir -p %DEVICE_MODEL_PATH%/sherpa-tts-%%L
adb shell mkdir -p %DEVICE_MODEL_PATH%/vad
adb shell mkdir -p %DEVICE_MODEL_PATH%/punctuation
adb shell mkdir -p %DEVICE_MODEL_PATH%/ocr-optional
echo OK: Directories created.
echo.

REM 3. Push Gemma model
echo [3/8] Pushing Gemma model...
if exist "%LOCAL_MODELS_ROOT%\gemma-local" (
    adb push "%LOCAL_MODELS_ROOT%\gemma-local" %DEVICE_MODEL_PATH%/gemma-local
    echo OK: Gemma pushed.
) else (
    echo WARN: gemma-local folder not found locally. Skipping.
)
echo.

REM 4. Push Sherpa ASR (English transducer + shared Indic whisper)
echo [4/8] Pushing Sherpa ASR models...
if exist "%LOCAL_MODELS_ROOT%\sherpa-asr-en" (
    adb push "%LOCAL_MODELS_ROOT%\sherpa-asr-en" %DEVICE_MODEL_PATH%/sherpa-asr-en
    echo OK: English ASR pushed.
) else (
    echo WARN: sherpa-asr-en folder not found locally. Skipping.
)
if exist "%LOCAL_MODELS_ROOT%\sherpa-asr-whisper" (
    adb push "%LOCAL_MODELS_ROOT%\sherpa-asr-whisper" %DEVICE_MODEL_PATH%/sherpa-asr-whisper
    echo OK: Indic ASR (whisper-tiny) pushed.
) else (
    echo WARN: sherpa-asr-whisper folder not found locally. Skipping.
)
echo.

REM 5. Push Sherpa TTS (English Coqui + per-language MMS)
echo [5/8] Pushing Sherpa TTS models...
if exist "%LOCAL_MODELS_ROOT%\sherpa-tts-en" (
    adb push "%LOCAL_MODELS_ROOT%\sherpa-tts-en" %DEVICE_MODEL_PATH%/sherpa-tts-en
    echo OK: English TTS pushed.
) else (
    echo WARN: sherpa-tts-en folder not found locally. Skipping.
)
for %%L in (hin ben tam tel kan mal) do (
    if exist "%LOCAL_MODELS_ROOT%\sherpa-tts-%%L" (
        adb push "%LOCAL_MODELS_ROOT%\sherpa-tts-%%L" %DEVICE_MODEL_PATH%/sherpa-tts-%%L
        echo OK: TTS %%L pushed.
    )
)
echo.

REM 6. Push VAD
echo [6/8] Pushing VAD model...
if exist "%LOCAL_MODELS_ROOT%\vad" (
    adb push "%LOCAL_MODELS_ROOT%\vad" %DEVICE_MODEL_PATH%/vad
    echo OK: VAD pushed.
) else (
    echo WARN: vad folder not found locally. Skipping.
)
echo.

REM 7. Push optional models
echo [7/8] Pushing optional models...
if exist "%LOCAL_MODELS_ROOT%\punctuation" (
    adb push "%LOCAL_MODELS_ROOT%\punctuation" %DEVICE_MODEL_PATH%/punctuation
    echo OK: Punctuation pushed.
)
if exist "%LOCAL_MODELS_ROOT%\ocr-optional" (
    adb push "%LOCAL_MODELS_ROOT%\ocr-optional" %DEVICE_MODEL_PATH%/ocr-optional
    echo OK: OCR pushed.
)
echo.

REM 8. Verify
echo [8/8] Verifying files on device...
adb shell ls -R %DEVICE_MODEL_PATH%
echo.

echo ==========================================
echo  Model push complete.
echo  Path on device: %DEVICE_MODEL_PATH%
echo ==========================================
pause
