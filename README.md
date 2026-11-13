# Goal Recorder - app Android (Lega Amatori Calcio Treviso)

App vera: la pagina e' dentro l'app (`app/src/main/assets/www`), non punta a nessun sito.
- Controller e camere si collegano come prima (serve internet solo per il primo collegamento: arriva dall'hotspot).
- Guida di avvio con i pulsanti diretti: modalita' aereo / Wi-Fi (camera), dati / hotspot (controller).
- I video si scrivono direttamente in **Download/GoalRecorder** mentre registri: niente download, funziona senza rete.
  Il file degli orari (`..._eventi.json`) finisce nella stessa cartella: copiala sul PC e Goal Extractor fa il resto.

L'APK si costruisce da solo su GitHub (Actions) a ogni modifica e si scarica sempre dallo stesso link:
`https://github.com/<account>/<repository>/releases/download/apk/GoalRecorder.apk`
Istruzioni passo passo: `COME_COSTRUIRE_L_APK.txt`.
