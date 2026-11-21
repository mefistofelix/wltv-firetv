# WLTV per Fire TV

App Android TV / Fire TV per guardare i canali in diretta delle liste **World Live TV**, senza Kodi.
Usa le stesse sorgenti dell'addon Kodi *World Live TV* (`plugin.video.wltvhelper`,
repository <https://github.com/WorldLiveTV/WorldLiveTV.github.io>): le liste M3U pubblicate su
`tvchannels.worldtvlive.eu` e, per i canali che nella lista sono link `plugin://plugin.video.wltvhelper/play/…`,
la stessa logica dei moduli *broadcaster* dell'addon (Rai, Mediaset, La7, Discovery, Sky, Pluto TV, Rakuten,
Samsung TV Plus, regionali…), riscritta in Kotlin. Il player è ExoPlayer (HLS, DASH, Widevine).

## Uso col telecomando

| Tasto | Video a schermo intero | Pannello canali aperto |
|---|---|---|
| ▲ ▼, CH+ CH− | canale precedente / successivo | si muove nella lista |
| OK, ◀ ▶ | apre il pannello canali | OK guarda il canale |
| ≡ Menu | apre il pannello e **riscarica l'elenco** | **riscarica l'elenco** |
| ≡ Menu tenuto premuto | ricerca: tastiera aperta nel campo Cerca | idem |
| 0–9 | numero del canale (LCN) | idem |
| Indietro | due volte per uscire | chiude il pannello |
| ⏯ | ricarica il canale | |

- **Elenco predefinito**: *DTT* (digitale terrestre italiano, `_dtt_kodi`). Il pulsante *Elenco* mostra tutte le
  liste WLTV disponibili (Vari, Regionali, Radio, Pluto TV, Samsung TV, Rakuten TV, WeDoTv, …) e permette anche un
  **URL personalizzato** di una lista M3U qualsiasi. La lista resta in memoria e funziona anche senza rete;
  si riscarica con ≡ e da sola se ha più di un giorno.
- **Più guardati**: l'app conta il tempo di visione di ogni canale; il gruppo *★ Più guardati* in cima mostra i
  canali visti per almeno un minuto, in ordine di tempo, con il totale. *Azzera più guardati* li cancella.
- **Ricerca vocale**: premi *Cerca* (o tieni premuto ≡), poi il tasto microfono della tastiera Fire TV e di' il nome
  del canale («Rai tre», «canale cinque», «la sette», «venti»). I risultati compaiono mentre parli; «Vai» sulla
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
