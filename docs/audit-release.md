# Audit de livraison publique — droid Forge mobile 0.4.0

Audit du 10 octobre 2026. Ce document décrit la livraison de l'éditeur local
ARM64 distribué par APK avec ses sources GPL. Il ne valide pas les fonctions
MIDI/SysEx ou firmware encore absentes. Les résultats d'exécution sont consignés dans
[VALIDATION-0.4.0.md](VALIDATION-0.4.0.md). Les rapports
[0.3.0](VALIDATION-0.3.0.md) et [0.2.1](VALIDATION.md) sont conservés comme historiques.

## Corrections apportées

- Variante `release` et préversion tablette optimisées par R8 et réduction des
  ressources, avec conservation explicite des noms du pont JNI.
- Nom affiché `droid Forge mobile`, version `0.4.0`, code `6`.
- Attribution de DROID et Forge PC à Mathias Kettner / Der Mann mit der Maschine ;
  contribution au port Android de Dr Delos.
- Préversion normale `fr.androforge` et variante de validation isolée
  `fr.androforge.validation`, optimisées sans mode débogage.
- Configuration de signature de production externe et facultative ; une
  configuration partielle est rejetée. Aucune clé de production n'est générée.
- Désactivation explicite de la sauvegarde cloud et du transfert des données
  privées sur Android 12+, en cohérence avec `allowBackup=false`. Les patches
  exportés par l'utilisateur restent dans le fournisseur de documents choisi.
- Ajout des notices du NDK 28.2.13676358 pour le runtime C++ lié statiquement.
  Une présentation courte des licences LLVM et des notices des bibliothèques
  C++ accompagne les notices complètes du NDK conservées dans l'APK.
- Import de 218 PNG officiels de prises et câbles, sans transformation,
  depuis le commit PC stable ; importer, SHA256, dimensions et provenance
  conservés dans les sources. La vérification couvre 230 fichiers Forge,
  dont 228 PNG et les notices GPL/crédits originales.
- Corpus de 630 exemples officiels et fragments documentaires : textes,
  provenance, pages, firmwares et SHA-256 embarqués ; les deux READMEs officiels
  et une notice distincte conservent le périmètre d’attribution du corpus.
- Conservation des symboles natifs et de la correspondance R8 pour analyser
  les rapports d'incident du binaire livré.
- Contrôle renforcé des binaires : segments ELF, fin de RELRO, stockage ZIP sur
  16 Kio, pile non exécutable, absence de segment à la fois modifiable et
  exécutable, résolution immédiate des relocations et notices requises.

Le contrôle précédent vérifiait les segments ELF et le ZIP, mais omettait la fin
de RELRO. Dans l'APK 0.2.0 elle se trouvait à `0x195000`, qui n'est pas divisible
par 16 Kio. L'édition de liens utilise désormais également
`-z common-page-size=16384`. La
[documentation Android sur les pages de 16 Kio](https://developer.android.com/guide/practices/page-sizes)
décrit le risque de crash lorsque la fin de RELRO n'est pas alignée. L'exécution
sur un appareil à pages de 4 Kio ne suffisait pas à détecter ce risque.

## Signature et fichiers de livraison

La variante `tabletPreview` conserve la clé de développement locale pour mettre
à jour la tablette de test. Elle n'est pas un APK de production.
Elle utilise actuellement le même identifiant `fr.androforge` que `release`.
La variante `tabletValidation` ajoute le suffixe `.validation` pour garder
les fichiers d'essai et les arrêts du processus dans un stockage indépendant.
Elle ne remplace pas le contrôle séparé de la session normale après mise à jour.
Si la clé publique est différente, la migration des installations de test doit
prévoir l'export des patches avant le remplacement de l'application ; Android
n'accepte pas une mise à jour avec une signature incompatible.

Pour signer `release`, fournir les quatre variables d'environnement, sans les
mettre dans le dépôt ni les passer en clair dans les arguments de commande :

| Variable | Valeur |
| --- | --- |
| `ANDROFORGE_KEYSTORE` | Chemin du keystore détenu par l'éditeur |
| `ANDROFORGE_STORE_PASSWORD` | Mot de passe du keystore |
| `ANDROFORGE_KEY_ALIAS` | Alias de la clé |
| `ANDROFORGE_KEY_PASSWORD` | Mot de passe de la clé |

Les propriétés Gradle externes `androforgeStoreFile`,
`androforgeStorePassword`, `androforgeKeyAlias` et `androforgeKeyPassword` sont
aussi acceptées dans les propriétés utilisateur Gradle, hors projet.
Les secrets ne sont jamais imprimés par les scripts ajoutés. La conservation et
la sauvegarde de la clé appartiennent à l'éditeur ; Android exige une signature
compatible pour les mises à jour. Voir la
[documentation officielle de signature](https://developer.android.com/studio/publish/app-signing).

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build_release.ps1 -Offline -SkipBundle
# Après configuration externe de la signature :
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build_release.ps1 -Offline -RequireSigning -SkipBundle
# Pour préparer également l'AAB facultatif, omettre -SkipBundle.
```

`ExecutionPolicy Bypass` concerne uniquement ce processus ; il ne change pas
la politique PowerShell persistante. Les 64 contrôles tablette 0.4.0 ont réussi :
61 sur l’installation isolée et 3 sur la mise à jour normale, avec nom et source
du patch utilisateur conservés à l’octet. Le
[rapport tablette](validation-data/0.4.0/tablet-audit-0.4.0.json) distingue ces deux périmètres.
Les résultats 0.3.0 restent historiques ; aucun contenu privé de la tablette
n’est publié. La clé de production n'a pas été fournie.

Le script prépare l'APK, l'AAB facultatif, les sources correspondantes, la correspondance
R8, les symboles natifs disponibles et leurs SHA256 dans `dist/`. Il contrôle
lint, l'alignement et la signature. Le contrôle statique AAB lit
`BundleConfig.pb`, exige `PAGE_ALIGNMENT_16K`, contrôle les ELF et les tables
de symboles natifs, et vérifie la présence du manifeste, du code et des notices.
Le schéma utilisé est celui de
[bundletool](https://github.com/google/bundletool/blob/master/src/main/proto/config.proto).
Il ne remplace ni la génération et l'installation des APK issus du bundle ni
l'analyse Play Console. Les signatures APK et JAR/AAB sont vérifiées séparément :
une signature déclarée mais invalide provoque un échec, même pour la revue.
L'AAB signé doit couvrir toutes les entrées hors `META-INF`, avec des digests
valides et le même certificat que l'APK. Les certificats auto-signés Android
ne sont pas considérés invalides simplement parce qu'ils ne figurent pas dans
un truststore. Sans clé, les fichiers portent explicitement
`release-unsigned` et servent à la revue. Le préfixe des fichiers est
`droid-Forge-mobile-0.4.0-`, notamment
`droid-Forge-mobile-0.4.0-arm64-release-unsigned.apk` et
`droid-Forge-mobile-0.4.0-source.zip`. Aucun fichier n'est publié ou envoyé.

L'archive des sources utilise une liste explicite de répertoires et des dates
ZIP fixes ; elle exclut caches, appareil de test, mots de passe locaux et clés.
Elle contient les graphismes, le catalogue, le moteur généré et les scripts de
compilation, ainsi que les exemples embarqués, leurs transcriptions et leurs
références de diagnostics. Le manuel PDF de référence, qui n'est pas une dépendance de la
compilation, reste hors archive ; l'importateur permet de le récupérer pour
les vérifications complémentaires des ressources. Une empreinte de chaque
fichier et, si fourni, de l'APK correspondant est incluse dans
`SOURCE-MANIFEST.json`. L'empreinte finale de l'archive figure dans les rapports
de livraison, sans être recopiée dans les documents inclus dans cette archive.

Les sources de 0.4.0 ont été extraites et reconstruites hors ligne. La comparaison retrouve
977 contenus ZIP identiques ; seul le natif diffère de 20 octets, tous dans
le descripteur du build ID GNU. Tous les autres octets ELF sont identiques.
Le [rapport de reconstruction de l’archive finale](validation-data/0.4.0/source-build-verification-0.4.0.json)
consigne l’archive exacte et la comparaison ; le script rassemble les sources
correspondantes et leur manifeste.
Le mode hors ligne utilise un SDK installé et les dépendances déjà en cache ;
il ne permet pas une première compilation sur une machine vide. Le projet ne
garantit pas l'identité octet pour octet entre deux compilations ou deux machines.

Les rapports 0.4.0 sont versionnés afin de conserver les anciennes preuves :
`release-apk-verification-0.4.0.json`, `release-sha256-0.4.0.json`,
`apk-verification-preview-0.4.0.json`,
`apk-verification-validation-0.4.0.json` et
`source-build-verification-0.4.0.json` dans
`dist/`. Les rapports facultatifs AAB suivent la même convention de version.

## Permissions, notices et sources

Le wrapper Gradle 8.13 a été comparé aux
[empreintes officielles Gradle](https://gradle.org/release-checksums/) :
JAR `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`,
distribution `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`.
L'empreinte de la distribution est imposée dans `gradle-wrapper.properties`.

Le manifeste source ne demande aucune permission et n'ajoute aucun service,
fournisseur exporté, composant public secondaire ou SDK de télémétrie.
L'unique activité exportée est le lanceur de l'application. L'import/export
utilise les accès accordés par le sélecteur Android. Le projet ne déclare aucune
dépendance applicative tierce ; le runtime C++ provient du NDK.

L'APK inclut la GPL v3, l'autorisation des graphismes, la licence et les crédits
Forge, les notices NDK et les notices de provenance des exemples. Le README
officiel propose les snippets pour copie dans des patches ; il ne fournit pas
de licence autonome explicite pour le corpus. Son autorisation des graphismes
n’est pas une licence GPL déduite pour les patches. L’attribution d’origine et
les fichiers officiels restent conservés, comme décrit dans [EXAMPLES.md](EXAMPLES.md).
Pour distribuer le binaire, rendre les sources
correspondantes de cette version accessibles avec le téléchargement et conserver
les notices ; la
[GPL v3, section 6](https://www.gnu.org/licenses/gpl-3.0.html)
décrit les modes de redistribution. Le dépôt prévu
[kf4u43D/droid-Forge-mobile](https://github.com/kf4u43D/droid-Forge-mobile)
est accessible mais ne contient encore aucune référence Git lors du contrôle
en lecture seule. Le téléchargement public devra donc fournir l'archive des
sources correspondantes ou le contenu publié de cette version.

## Conditions encore ouvertes avant publication

- Signer avec la clé destinée à la distribution, puis contrôler et conserver
  son empreinte. La signature de développement de la tablette ne convient pas.
- Fournir le téléchargement des sources correspondant précisément au binaire.
- Vérifier réellement les versions Android annoncées et les pages mémoire de
  16 Kio ; les contrôles statiques ne remplacent pas un essai sur cet environnement.
- Limiter la description publique aux fonctions disponibles et au catalogue
  blue-7 ; l'éditeur ne valide pas encore toutes les ressources du matériel DROID.

`minSdk=26`, `targetSdk=36`, ARM64, AGP 8.13.2 et NDK r28 sont les valeurs
conservées pour la distribution directe. L'AAB reste un complément de revue.
Une éventuelle publication sur un magasin nécessitera la vérification de ses
exigences au moment de cette publication.

Lint reste bloquant sur les erreurs. Aucun baseline ni désactivation globale
d'avertissements n'a été ajouté. Les textes signalés par `SetTextI18n` sont
désormais dans les ressources et les quantités utilisent des pluriels.
Lint 0.4.0 a terminé sans erreur sur les trois variantes, avec deux avertissements
par variante, `OldTargetApi` et `ChromeOsAbiSupport`. La cible API 36 et le périmètre ARM64
restent explicites ; aucun baseline n’est introduit pour obtenir un résultat
artificiellement vide.

## Vérifications des outils de livraison

Les vérifications automatisées portent sur l'ELF et le durcissement du binaire,
la configuration AAB et la lecture protobuf, l'archive des sources et les
signatures. Les **38 tests Python** de cette suite ont réussi pour 0.4.0,
dont huit vérifications de découverte des notes GNU et de comparaison
stricte des binaires reconstruits : seuls les octets du build ID peuvent
différer, les autres changements doivent rester visibles.
Les essais de signature utilisent
la clé de développement déjà présente, dans des fichiers temporaires supprimés
ensuite ; aucune clé n'est créée ou modifiée. Ils couvrent notamment un
certificat Android auto-signé valide, un contenu signé altéré et un fichier
ajouté sans signature.

```powershell
python -m unittest discover -s tools -p 'test_*.py'
```

Les contrôles ELF et APK des trois variantes 0.4.0 confirment statiquement
l'alignement 16 Kio, la fin de RELRO à `0x1e4000`, la pile non exécutable et la résolution
immédiate des relocations. Ils ne valident pas un appareil à pages de 16 Kio.
Le contrôle facultatif AAB exige `PAGE_ALIGNMENT_16K` et les tables de symboles
natives. Les APK réellement exécutés sont décrits dans
[VALIDATION-0.4.0.md](VALIDATION-0.4.0.md), avec des empreintes distinctes pour
la validation isolée et la préversion normale. Les contrôles de livraison sont bloquants dans le script ;
les empreintes des fichiers de revue et des sources produits sont consignées
dans les rapports `dist/` pour chaque exécution.

Le corpus officiel 0.4.0 a passé 59 307 contrôles C++ et 27 477 contrôles
indépendants de catalogue/rapport. Les 630 allers-retours sont exacts et les
diagnostics documentaires restent attendus et visibles, sans modification des
patches originaux. L’audit des 413 pages du PDF retrouve les 3 010 lignes de code
et 207 affectations inline recensées dans sa couche de texte ; le périmètre
est précisé dans [EXAMPLES.md](EXAMPLES.md). Le contrôle supplémentaire
`tools/verify_example_assets.py` a confirmé les 630 textes et trois notices de
chacun des trois APK 0.4.0 par comparaison au manifeste épinglé. Ces tests ne
remplacent pas une validation des exemples sur le firmware et le matériel DROID.
