@echo off
rem Register the weekly purge on the batch server
schtasks /create /tn "Billing\WeeklyPurge" /tr "C:\Billing\NightlyExport\NightlyExport.exe /purge" /sc weekly /d SUN /st 03:00
