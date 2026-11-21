# WLTV per Fire TV

App Android TV / Fire TV per guardare i canali in diretta delle liste **World Live TV**, senza Kodi.
Usa le stesse sorgenti dell'addon Kodi *World Live TV* (`plugin.video.wltvhelper`,
repository <https://github.com/WorldLiveTV/WorldLiveTV.github.io>): le liste M3U pubblicate su
`tvchannels.worldtvlive.eu` e, per i canali che nella lista sono link `plugin://plugin.video.wltvhelper/play/…`,
la stessa logica dei moduli *broadcaster* dell'addon (Rai, Mediaset, La7, Discovery, Sky, Pluto TV, Rakuten,
Samsung TV Plus, regionali…), riscritta in Kotlin. Il player è ExoPlayer (HLS, DASH, Widevine).

## Uso col telecomando

| Tasto | Video a schermo intero | Pannello aperto |
|---|---|---|
| ▲ ▼, CH+ CH− | canale precedente / successivo (e riquadro col programma) | si muove nella lista |
| OK, ◀ | apre il pannello **canali** | OK guarda il canale |
| ▶, ⏩ | apre la **Guida TV** (programmi in onda) | ⏩ passa tra canali e guida |
| ≡ Menu | apre il pannello e **riscarica gli elenchi** | **riscarica gli elenchi** |
| ≡ Menu tenuto premuto | ricerca: tastiera aperta nel campo Cerca | idem |
| 0–9 | numero del canale (LCN) | idem |
| Indietro | chiude il riquadro; due volte per uscire | chiude il pannello |
| ⏯ | ricarica il canale | |

- **Elenchi**: si possono attivare **più elenchi insieme** (caselle di spunta, salvate subito: nessun pulsante di
  conferma, Indietro torna su e all'uscita i canali si ricaricano; in cima a ogni sezione *Seleziona tutti /
  deseleziona tutti*), divisi in quattro sezioni come
  nell'addon: *Liste WLTV* (DTT, Vari, Regionali, Radio, Pluto TV, Samsung TV, Rakuten, WeDoTv…), *TV dal mondo*
  (un elenco per paese, liste iptv-org), *Webcam* (SkylineWebcams per paese) e *Radio dal mondo* (per paese), più un
  **URL personalizzato** di una lista M3U. Il predefinito è *DTT*. I canali di tutti gli elenchi attivi finiscono
  in un'unica lista; a sinistra ci sono i gruppi per elenco e per categoria, ognuno con il **numero di canali**.
  Gli elenchi restano in memoria e funzionano anche senza rete; si riscaricano con ≡ e da soli dopo un giorno.
- **Guida programmi**: la guida EPG di WLTV (`epg-guide.com/wltv.xz`, la stessa dell'addon) viene scaricata e letta
  sulla TV. Sotto ogni canale c'è il **programma in onda** con orario e barra di avanzamento. Cambiando canale
  compare per qualche secondo un riquadro con logo, programma, ora di inizio e di fine, **barra di avanzamento**,
  minuti mancanti e programma successivo.
- **Guida TV**: vista per **programmi** invece che per canali: *In onda ora* (in ordine di titolo), *Tra poco*
  (le prossime 4 ore, in ordine di orario) e le categorie dei programmi in onda; per ognuno il canale, l'orario e
  quanto manca. OK guarda il canale che lo trasmette.
- **Più guardati**: l'app conta il tempo di visione di ogni canale; il gruppo *★ Più guardati* in cima mostra i
  canali visti per almeno un minuto, in ordine di tempo, con il totale. *Azzera più guardati* li cancella.
- **Ricerca vocale**: premi *Cerca* (o tieni premuto ≡), poi il tasto microfono della tastiera Fire TV e di' il nome
  del canale («Rai tre», «canale cinque», «la sette», «venti») oppure di un **programma in onda** («Un posto al
  sole»): trova il canale che lo sta trasmettendo. I risultati compaiono mentre parli; «Vai» sulla
  tastiera avvia il primo. Il tasto microfono del telecomando *fuori* dalla tastiera apre sempre Alexa: Fire OS non lo
  passa alle app.
- **Loghi**: quelli della lista o, se mancano, quelli della guida EPG di WLTV (la stessa che Kodi usa per i loghi);
  i TGR prendono il logo di Rai 3; senza logo compare un riquadro colorato con le iniziali.
- I canali YouTube senza un flusso HLS diretto si aprono nell'app YouTube.

## Build

Ogni push su `main` costruisce l'APK con GitHub Actions (`.github/workflows/build.yml`) e lo pubblica come release
`build-N`. Installazione sulla TV (ADB di rete attivo):

```powershell
gh release download -R mefistofelix/wltv-firetv -p "*.apk" -D out --clobber
adb connect 192.168.1.43:5555
adb install -r (Get-ChildItem out\*.apk | Sort-Object LastWriteTime | Select-Object -Last 1).FullName
```

Oppure `tools\install.ps1 -Device 192.168.1.43:5555`.

## Note

- I resolver seguono l'addon 1.2.14 con l'hotfix 121418. Quando i siti cambiano, l'addon pubblica un nuovo hotfix:
  il confronto con `broadcaster/*.py` dice cosa aggiornare in `Resolver.kt`.
- Molti flussi sono riservati all'Italia (geoblocco).
