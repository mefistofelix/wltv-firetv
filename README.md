# Fire TV Live

App Android TV / Fire TV (nome sulle TV: **Fire TV Live**) per guardare i canali in diretta delle liste **World Live TV**, senza Kodi.
Usa le stesse sorgenti dell'addon Kodi *World Live TV* (`plugin.video.wltvhelper`,
repository <https://github.com/WorldLiveTV/WorldLiveTV.github.io>): le liste M3U pubblicate su
`tvchannels.worldtvlive.eu` e, per i canali che nella lista sono link `plugin://plugin.video.wltvhelper/play/…`,
la stessa logica dei moduli *broadcaster* dell'addon (Rai, Mediaset, La7, Discovery, Sky, Pluto TV, Rakuten,
Samsung TV Plus, regionali…), riscritta in Kotlin. Il player è ExoPlayer (HLS, DASH, Widevine).

## Uso col telecomando

| Tasto | Video a schermo intero | Pannello aperto |
|---|---|---|
| ▲ ▼, CH+ CH− | canale precedente / successivo (e riquadro col programma) | si muove nell'elenco |
| OK, ◀ | apre il pannello **canali** | OK guarda il canale |
| OK tenuto premuto | ♥ preferito sì / no per il canale in onda | ♥ preferito sì / no per il canale selezionato |
| ▶, ⏩ | apre la **Guida TV** (programmi in onda) | nell'elenco ⏪ ⏩ = cartella precedente / successiva |
| ≡ Menu | **ricerca**: pannello aperto e tastiera nel campo Cerca | idem, l'elenco resta dov'era |
| ≡ Menu tenuto premuto | **riscarica gli elenchi** | idem |
| ◀ / ▶ | | dall'elenco ◀ va alle **impostazioni** (colonna a sinistra), ▶ torna sul canale lasciato |
| ▲ sul primo canale | | campo di ricerca; ▼ torna nell'elenco |
| 0–9 | numero del canale (LCN) | idem |
| Indietro | chiude il riquadro; due volte per uscire | tastiera aperta: la chiude e torna nell'elenco (ricerca tenuta); con una ricerca attiva: la **annulla** e torna dov'eri; altrimenti chiude il pannello |
| ⏯ | ricarica il canale | |

- **Pannello**: a sinistra dello schermo la colonna **impostazioni** (Cerca, Cartelle, Guida TV, Elenchi, Aggiorna,
  Azzera visti), a destra l'**elenco** dei canali con il campo di ricerca in cima; in mezzo resta visibile il video.
  L'elenco è **unico**: in cima *★ Più guardati*, poi tutti i canali con dei piccoli **divisori di cartella**
  («elenco › gruppo» e numero di canali); ogni canale riporta anche la sua cartella a destra del nome, così si sa
  dove si è anche a metà cartella. I più guardati compaiono di nuovo più sotto, al loro posto. *Cartelle* apre
  l'indice delle cartelle per saltare direttamente a una.
- **Ricerca**: cerca nel nome, nel numero, nel **programma in onda** e nella **cartella** (elenco, gruppo, categoria).
  Si apre con ≡ da qualunque punto senza perdere la posizione: se il canale su cui eri è tra i risultati resta
  selezionato, e Indietro (o cancellare il testo) rimette tutto l'elenco **sul punto dove eri**, oppure sul risultato
  su cui ti sei spostato o che hai guardato. La ricerca resta attiva chiudendo e riaprendo il pannello finché non la
  annulli.
- **Elenchi**: una sola schermata con **tutti gli elenchi**: in cima gli *Attivi*, poi le sezioni come nell'addon:
  *Liste WLTV* (DTT, Vari, Regionali, Radio, Pluto TV, Samsung TV, Rakuten, WeDoTv…), *TV dal mondo* (un elenco per
  paese, liste iptv-org), *Webcam* (SkylineWebcams per paese) e *Radio dal mondo* (per paese), ognuna con la riga
  *Tutti*, e in fondo l'**URL personalizzato** di una lista M3U. OK attiva o disattiva e **salva subito** (nessun
  pulsante di conferma). ≡ apre la **ricerca** (anche a voce: «Francia», «webcam Italia»), *Tutti* vale per quelli
  trovati; Indietro annulla la ricerca e torna sulla riga dove eri, Indietro ancora chiude e i canali si
  ricaricano. Si possono attivare **più elenchi insieme**. Il predefinito è *DTT*. I canali di tutti gli elenchi attivi finiscono
  in un'unica lista, divisa in cartelle per elenco e per gruppo, ognuna con il **numero di canali**.
  Gli elenchi restano in memoria e funzionano anche senza rete; si riscaricano tenendo premuto ≡ e da soli dopo un giorno.
- **Guida programmi**: la guida EPG di WLTV (`epg-guide.com/wltv.xz`, la stessa dell'addon) viene scaricata e letta
  sulla TV. Sotto ogni canale c'è il **programma in onda** con orario e barra di avanzamento. Cambiando canale
  compare per qualche secondo un riquadro con logo, programma, ora di inizio e di fine, **barra di avanzamento**,
  minuti mancanti e programma successivo.
- **Guida TV**: vista per **programmi** invece che per canali: *In onda ora* (in ordine di titolo), *Tra poco*
  (le prossime 4 ore, in ordine di orario); per ognuno il canale, la categoria, l'orario e
  quanto manca. OK guarda il canale che lo trasmette.
- **Preferiti**: **OK tenuto premuto** su un canale dell'elenco (o col video a schermo intero, sul canale che stai
  guardando) lo mette o lo toglie dai preferiti; sui canali preferiti compare ♥. Il blocco *♥ Preferiti* sta in cima
  all'elenco. I preferiti sono salvati per intero, quindi **restano anche se spegni l'elenco** da cui vengono e
  partecipano alla ricerca.
- **Più guardati**: l'app conta il tempo di visione di ogni canale; il blocco *★ Più guardati* in cima mostra i
  canali visti per almeno un minuto, in ordine di tempo, con il totale. *Azzera più guardati* li cancella.
- **Ricerca vocale**: premi ≡ (o *Cerca*), poi il tasto microfono della tastiera Fire TV e di' il nome
  del canale («Rai tre», «canale cinque», «la sette», «venti») oppure di un **programma in onda** («Un posto al
  sole»): trova il canale che lo sta trasmettendo. I risultati compaiono mentre parli; «Vai» sulla
  tastiera avvia il primo. Il tasto microfono del telecomando *fuori* dalla tastiera apre sempre Alexa: Fire OS non lo
  passa alle app.
- **Loghi**: quelli della lista o, se mancano, quelli della guida EPG di WLTV (la stessa che Kodi usa per i loghi);
  i TGR prendono il logo di Rai 3; senza logo compare un riquadro colorato con le iniziali.
- I canali YouTube sono **saltati**: non compaiono negli elenchi (nell'addon aprivano il plugin YouTube) e l'app non
  apre mai l'app YouTube.

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

## Aggiornamenti automatici

Come FireBro: all'avvio e ogni volta che torna in primo piano (al massimo ogni 2 minuti) WLTV legge
`wltv.json` nel repository pubblico [mefistofelix/firetv-apps](https://github.com/mefistofelix/firetv-apps).
Se c'è una versione più nuova la propone: *Installa* la scarica, ne controlla lo SHA-256 e apre la schermata di
installazione di Fire TV (serve confermare con «Installa»); *Più tardi* la ripropone dopo 12 ore. Dopo
l'aggiornamento WLTV si riapre da sola. La prima volta Fire OS chiede di attivare *Installa app sconosciute* per WLTV.

La pubblicazione si fa dal PC come in FireBro, con il login `gh` locale (nessun token salvato nel repository):
dopo `git push`, `bash scripts/release.sh` aspetta la build di GitHub Actions, scarica l'APK in `out/apk` e lo
pubblica con `scripts/publish-update.sh` (release `wltv-N` di firetv-apps e `wltv.json`).
