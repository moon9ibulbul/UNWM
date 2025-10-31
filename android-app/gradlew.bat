@ECHO OFF

SETLOCAL

WHERE gradle >NUL 2>&1

IF %ERRORLEVEL% EQU 0 (

  gradle %*

  EXIT /B %ERRORLEVEL%

) ELSE (

  ECHO Gradle is required to build this project. Install Gradle 8.6 or newer.

  EXIT /B 1

)

