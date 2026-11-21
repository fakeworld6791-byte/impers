@echo off
chcp 65001 >nul
echo Сборка Saguroko Impers...
call mvn -q clean package
if errorlevel 1 (
  echo ОШИБКА сборки. Скопируйте текст ошибки выше и отправьте Claude.
) else (
  echo Готово: target\SagurokoImpers.jar
)
pause
