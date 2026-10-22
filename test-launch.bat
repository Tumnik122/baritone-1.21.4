@echo off
set "RT=%~dp0fabric\build\bot-runtime"
set "CL=%~dp0fabric\run\client"
java @"%CL%\jvm-launch-args.txt" @"%RT%\unimined-jvm-args.txt" @"%RT%\classpath.txt" net.fabricmc.loader.impl.launch.knot.KnotClient --username Gamer6844 --version 1.21.4 --gameDir "%CL%" --assetsDir "C:\Users\Administrator\.gradle\caches\unimined\assets" --assetIndex 19 --accessToken 0 --clientId unimined --xuid "${auth_xuid}" --userType msa --versionType release
