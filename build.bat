@echo off
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
if not exist ".gradle-user-home" mkdir ".gradle-user-home"
set GRADLE_USER_HOME=%cd%\.gradle-user-home
call ".gradle-local\gradle-9.4.1\bin\gradle.bat" :app:assembleDebug --no-daemon %*
