@echo off
REM Verify model file integrity with SHA-256 checksums
set MODELS_ROOT=..\..\models
set CHECKSUM_FILE=checksums.txt

echo UnoOne Model Checksum Verification
echo ===================================
echo.

if not exist "%CHECKSUM_FILE%" (
    echo No checksums.txt found. Generating...
    goto generate
)

echo Verifying files against checksums.txt...
for /f "tokens=1,2" %%a in (%CHECKSUM_FILE%) do (
    set file=%%b
    set expected=%%a
    if exist "%%b" (
        for /f "skip=1 tokens=*" %%c in ('certutil -hashfile "%%b" SHA256') do (
            set actual=%%c
            goto compare
        )
        :compare
        if "!expected!"=="!actual!" (
            echo [OK] %%b
        ) else (
            echo [FAIL] %%b — checksum mismatch
        )
    ) else (
        echo [MISSING] %%b
    )
)
echo.
echo Verification complete.
goto end

:generate
echo Generating checksums for model files...
(
    for /r "%MODELS_ROOT%" %%f in (*) do (
        certutil -hashfile "%%f" SHA256 | findstr /v "CertUtil" | findstr /v "SHA256"
        echo %%f
    )
) > %CHECKSUM_FILE%
echo checksums.txt generated.

:end
pause
