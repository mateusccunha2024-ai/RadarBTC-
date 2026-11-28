#!/usr/bin/env bash
# Teste do Jarvis num celular de verdade (emulador Android): abre o painel, depois baixa a IA, a voz e o microfone
# e usa tudo. Guarda capturas, o log do app e qualquer fechamento em smoke/.
set -x
PKG=com.cunhac.btcradar
APK=$(ls app/build/outputs/apk/debug/*.apk | head -n 1)
mkdir -p smoke
adb install -r -g "$APK" || adb install -r "$APK"
adb shell pm grant $PKG android.permission.RECORD_AUDIO || true
adb logcat -c

alive() { adb shell pidof $PKG > /dev/null 2>&1; }

# 1) Só abrir o Jarvis (sem modelos)
adb shell am start -W -n $PKG/.MainActivity --ez jarvis true
sleep 25
adb exec-out screencap -p > smoke/1-jarvis-aberto.png
if alive; then echo "1 abrir o Jarvis: app aberto" >> smoke/status.txt; else echo "1 abrir o Jarvis: APP FECHOU" >> smoke/status.txt; fi

# 2) Teste completo: baixa os modelos leves, pergunta, fala e carrega o microfone
adb shell am force-stop $PKG
adb shell am start -W -n $PKG/.MainActivity --ez jarvis true --ez jarvisSelfTest true
for i in $(seq 1 100); do
  sleep 10
  if adb logcat -d -s RadarBTC:I | grep -q "selftest: fim"; then echo "2 teste completo: terminou (${i}0 s)" >> smoke/status.txt; break; fi
  if ! alive; then echo "2 teste completo: APP FECHOU depois de ${i}0 s" >> smoke/status.txt; break; fi
  [ "$i" = 40 ] && adb exec-out screencap -p > smoke/2a-meio.png
done
adb exec-out screencap -p > smoke/2-teste-completo.png
alive && echo "2 fim: app aberto" >> smoke/status.txt || echo "2 fim: app fechado" >> smoke/status.txt

# 3) Abrir de novo depois do teste (modelos já baixados)
adb shell am force-stop $PKG
adb shell am start -W -n $PKG/.MainActivity --ez jarvis true
sleep 20
adb exec-out screencap -p > smoke/3-reaberto.png
alive && echo "3 reabrir com modelos: app aberto" >> smoke/status.txt || echo "3 reabrir com modelos: APP FECHOU" >> smoke/status.txt

adb logcat -d > smoke/logcat-completo.txt
grep -E "RadarBTC|FATAL|AndroidRuntime|DEBUG   :|libc  |lowmemorykiller|lmkd|Process $PKG|ANR in" smoke/logcat-completo.txt | tail -n 500 > smoke/logcat.txt
grep -h "selftest" smoke/logcat.txt >> smoke/status.txt || true
grep -A30 "FATAL EXCEPTION" smoke/logcat-completo.txt | head -n 80 > smoke/fechamento.txt || true
adb shell run-as $PKG cat files/jarvis/ultimo-fechamento.txt > smoke/ultimo-fechamento.txt 2>/dev/null || true
cat smoke/status.txt
exit 0
