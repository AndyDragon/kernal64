@echo off
set HOME=%~dp0
set LIB=%HOME%lib
set ROMS=%HOME%roms
set CP=%ROMS%;%LIB%\kernal64.jar;%LIB%\jinput.jar;%LIB%\scala-library.jar;%LIB%\scala-parser-combinators_2.11-1.0.3.jar;%LIB%\commons-net-3.3.jar;%LIB%\dropbox-core-sdk-1.7.7.jar;%LIB%\jackson-core-2.2.4.jar
rem to add custom Kernals set the variable below adding -Dkernal=<kernal file> -D1541kernal=<1541 kernal file>
rem both kernal files must be placed under roms directory
rem example: KERNALS_ROM=-Dkernal=jiffydos_kernal.bin -D1541kernal=jiffydos1541_kernal.bin
set KERNALS_ROMS=
start javaw -server -Xms64M -Xmx64M -cp %CP% -Djava.library.path=%LIB% %KERNALS_ROMS% ucesoft.c64.C64 %*
