@echo off
rem =========================================================================
rem dev-start.bat — Safe Baritone dev-client launcher for Windows
rem
rem Invokes dev-start.ps1 which:
rem   1. Kills lingering Fabric dev JVMs holding .fabric\processedMods\*.jar
rem   2. Deletes the processedMods cache directory
rem   3. Runs gradlew.bat :fabric:runClient
rem
rem Usage:
rem   dev-start.bat            (smart kill - only dev-client JVMs)
rem   dev-start.bat -AllJava   (nuclear - kill ALL java.exe processes)
rem =========================================================================
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0dev-start.ps1" %*
