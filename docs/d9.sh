#!/bin/bash
export PATH=$PATH:$HOME/Android/Sdk/platform-tools
S=100.95.16.47:5555; P=com.morton.trucknav
st() { adb -s $S shell dumpsys media_session | grep -E "package=|state=[A-Z]+\(" | grep -A1 "package=$1" | grep -oE "state=[A-Z]+\(" | head -1 | cut -c7- | tr -d "("; }
tap() { ~/bin/ui.sh $S tap "$1" >/dev/null; }
adb -s $S logcat -c; adb -s $S logcat -c -b crash
echo "== D9 clean (0.9.4) =="
# start from silence: toggle whichever is playing
tap Music; sleep 2; [ "$(st com.unicornsonlsd.finamp)" = PLAYING ] && { tap "Play/Pause"; sleep 2; }
tap Books; sleep 2; [ "$(st $P)" = PLAYING ] && { tap "Play/Pause"; sleep 2; }
echo "0 silence: finamp=$(st com.unicornsonlsd.finamp) book=$(st $P)"
tap Music; sleep 2; tap "Play/Pause"; sleep 4; echo "1 music play: finamp=$(st com.unicornsonlsd.finamp) book=$(st $P)"
tap Books; sleep 2; tap "Play/Pause"; sleep 8; echo "2 book play (play-last): finamp=$(st com.unicornsonlsd.finamp) book=$(st $P)"
adb -s $S logcat -d -s BooksPlayer:* | grep -E "opened|error" | tail -3
tap Music; sleep 2; tap "Play/Pause"; sleep 4; echo "3 music play: finamp=$(st com.unicornsonlsd.finamp) book=$(st $P)"
tap Books; sleep 3; adb -s $S exec-out screencap -p > ~/d9-books-land.png
echo "crashes: $(adb -s $S logcat -d -b crash | grep -c "Process: $P")"
echo "service lifecycle: $(adb -s $S logcat -d -s BooksPlayer:* | grep -cE "onCreate|onDestroy") create/destroy lines; opened: $(adb -s $S logcat -d -s BooksPlayer:* | grep -c opened)"
