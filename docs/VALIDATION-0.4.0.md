# Validation de droid Forge mobile 0.4.0

Rapport de la version **0.4.0**, code **6**. La campagne tablette a réussi ses
**64 contrôles** : 61 sur l’installation isolée et 3 sur la mise à jour normale.
Les résultats historiques [0.3.0](VALIDATION-0.3.0.md) et [0.2.1](VALIDATION.md)
restent séparés : leurs 45 et 42 contrôles ne sont pas ceux de 0.4.0.

## Périmètre

Cette version ajoute le zoom du rack, les étiquettes de registres, les commentaires
de circuits et l’ouverture des exemples officiels. Elle conserve les cellules
valeur × multiplicateur + décalage, les menus de câbles et les quatre sélections
de paramètres initiaux. Mathias Kettner / Der Mann mit der Maschine est crédité
pour DROID et Forge PC, et Dr Delos pour sa contribution au port Android.

La campagne Android utilise `fr.androforge.validation` pour isoler fixtures et
arrêts du processus. La mise à jour de `fr.androforge` est contrôlée séparément en
lecture seule. Aucun nom, texte ou hash de patch utilisateur n’est publié.
La préversion tablette utilise la signature de développement existante ; aucune
clé de production n’a été fournie. La variante Release reste non signée sans
configuration externe.

## Corpus officiel : preuves obtenues

| Vérification | Résultat |
| --- | ---: |
| Entrées officielles / documentaires incluses | 630 |
| Fichiers INI originaux | 34 patches + 2 Moto Kit |
| Corps TXT originaux | 332 |
| Transcriptions PDF et fragments | 262 |
| Lecture/export/relecture à l’octet | 630 / 630 |
| Analyse locale bornée achevée | 630 / 630 |
| Contrôles C++ dédiés | 59 307 |
| Même corpus avec ASan et UBSan combinés | 630 / 630 ; 59 307 contrôles |
| Même corpus natif ARM64 sur la tablette | 630 / 630 ; 59 307 contrôles |
| Contrôles catalogue indépendant et rapport | 27 477 |
| Paramètres connus / absents du catalogue | 24 935 / 22 |
| Erreurs / remarques documentaires attendues | 347 / 108 |
| Erreurs dans les 36 INI officiels complets | 0 |
| Remarques dans ces 36 INI | 8 |
| Pages PDF balayées | 413 |
| Lignes de code / affectations inline recensées | 3 010 / 207 |
| Candidats de code manquants dans le corpus | 0 |

Le [rapport public des exemples](validation-data/0.4.0/official-examples-0.4.0.json) contient les
hashes, pages, diagnostics et résultats de chaque entrée. Les
[conditions de conservation et la couverture](EXAMPLES.md) expliquent la
distinction entre un programme et un fragment documentaire. Les fichiers
officiels sont conservés sans correction : ces résultats ne constituent pas une
validation firmware ou matérielle de chaque exemple.
Le [rapport ASan+UBSan](validation-data/0.4.0/official-examples-asan-ubsan-0.4.0.json) confirme
la même suite et les mêmes diagnostics attendus, sans erreur de sanitizer.

Le runner C++ compare aussi les états après export/relecture, vérifie l’absence
de modification d’historique lors d’une importation sans changement et contrôle
les plages des valeurs dans le source original. Le contrôle Python déduit les
directions des paramètres directement du catalogue officiel et compare les
diagnostics avec leur référence liée au SHA-256 de chaque document.

Reproduction Windows des tests dédiés avec LLVM 22.1 et le Ninja du SDK installé :

```powershell
cmake -S . -B .cache/official-examples-build -G Ninja '-DCMAKE_CXX_COMPILER=C:/Program Files/LLVM/bin/clang++.exe' '-DCMAKE_MAKE_PROGRAM=C:/Android/SDK/cmake/3.22.1/bin/ninja.exe' -DCMAKE_BUILD_TYPE=Release
cmake --build .cache/official-examples-build --target androforge_official_examples_tests
python tools/verify_official_examples.py --engine .cache/official-examples-build/core/androforge_official_examples_tests.exe
cmake -S . -B .cache/official-examples-sanitized -G Ninja '-DCMAKE_CXX_COMPILER=C:/Program Files/LLVM/bin/clang++.exe' '-DCMAKE_MAKE_PROGRAM=C:/Android/SDK/cmake/3.22.1/bin/ninja.exe' -DCMAKE_BUILD_TYPE=RelWithDebInfo '-DCMAKE_CXX_FLAGS=-fsanitize=address,undefined -fno-sanitize-recover=all -fno-omit-frame-pointer' '-DCMAKE_EXE_LINKER_FLAGS=-fsanitize=address,undefined -ldbghelp -lshell32'
cmake --build .cache/official-examples-sanitized --target androforge_official_examples_tests
Copy-Item -LiteralPath 'C:\Program Files\LLVM\lib\clang\22\lib\windows\clang_rt.asan_dynamic-x86_64.dll' -Destination '.cache/official-examples-sanitized/core/clang_rt.asan_dynamic-x86_64.dll'
python tools/verify_official_examples.py --engine .cache/official-examples-sanitized/core/androforge_official_examples_tests.exe --report dist/official-examples-asan-ubsan-0.4.0.json
```

Le runtime Windows des sanitizers doit être accessible avant de lancer le
programme ; il est fourni ici seulement dans le cache de test. Il n’est pas
une dépendance de l’application Android ni un élément de son APK.

## Moteur et interface

Les résultats suivants ont été réexécutés pour 0.4.0. Les nombres de la version
0.3.0 ne servent pas de substitut à leur réexécution.

| Vérification | Résultat obtenu |
| --- | ---: |
| Moteur C++ Release sur Windows | 86 971 contrôles |
| Moteur C++ avec ASan et UBSan combinés | 86 971 contrôles |
| Moteur natif ARM64 sur SM-X620 | 86 340 contrôles |
| Corpus officiel ARM64 | 630 documents / 59 307 contrôles |
| Pont JNI, `-Xcheck:jni` | 176 contrôles |
| Géométrie du rack | 1 313 contrôles |
| Codec de texte | 64 contrôles |
| Cellules Java | 23 contrôles |
| Correspondance des brouillons après mutations | 56 contrôles |
| Outils Python de livraison | 38 tests |
| Build des trois variantes et lint | Réussis ; 104 tâches exécutées et 54 déjà à jour, 59 s |
| Parcours tablette isolés | 61 contrôles réussis |
| Mise à jour normale, en lecture seule | 3 contrôles réussis |

Lint a terminé avec zéro erreur et deux avertissements par variante,
`OldTargetApi` et `ChromeOsAbiSupport`. Aucun baseline ni désactivation globale
n’a été ajouté. La cible API 36 et le périmètre ARM64 restent explicites.
Les payloads `classes.dex` et `libandroforge.so` sont identiques dans les trois
APK ; les manifestes, ressources d’identité et signatures restent distincts.

La vérification des [assets de la préversion](validation-data/0.4.0/official-examples-apk-preview-0.4.0.json),
de [la validation isolée](validation-data/0.4.0/official-examples-apk-validation-0.4.0.json) et
de [la Release sans signature](validation-data/0.4.0/official-examples-apk-release-0.4.0.json)
confirme les 630 textes et trois notices dans chacun des APK.

Le benchmark natif local utilise 55 975 octets, 750 circuits et 100 itérations :
chargement avec JSON à 2,923 ms/opération, édition/analyse avec JSON à
2,603 ms/opération. Il mesure le moteur et son état JSON, pas le rendu Android.

## Binaires exécutés ou contrôlés statiquement

| Variante | Utilisation | Octets | SHA-256 |
| --- | --- | ---: | --- |
| `tabletPreview` | Mise à jour normale, contrôle en lecture seule | 9 185 053 | `c4ba5d7874cae414cc090d382538d173288d726f7a2db8c3f605d9e1226ce0fc` |
| `tabletValidation` | 61 contrôles isolés | 9 185 057 | `cfb8085d2f403788de41d23dca820764bbf71df60e3f4e3d59c2a95cf28afeb0` |
| `release`, sans signature | Contrôles statiques et assets | 9 180 953 | `400805eb8a8af37ba7d354b5d2ae4bcfb49caf39453b790bff3c8a3330468dad` |

Les contrôles ELF/ZIP ont réussi pour les trois APK : natif de 1 958 208 octets,
segments LOAD alignés sur 16 384 octets, fin de RELRO à `0x1e4000` et début du
payload natif dans le ZIP à 81 920 octets. La pile est non exécutable et les
relocations utilisent `BIND_NOW`. Aucune permission n’est demandée.
Les signatures de développement des deux variantes tablette ont été vérifiées ;
leur certificat a pour SHA-256
`c612d14404ad8381ec8045abb319a6876d9caae723f70a9b3f6c02666a303fbd`.
Cette signature ne constitue pas la signature destinée à la distribution publique.

Le [rapport tablette final](validation-data/0.4.0/tablet-audit-0.4.0.json) détaille les 64
contrôles : zoom, déplacement, étiquettes, commentaires et brouillons, menus de
câbles, ouverture d’exemples, confirmation de remplacement et export exact.
Le pincement a été exercé par injection de deux pointeurs tactiles sur l’appareil.
Le nom et le source du patch utilisateur ont été conservés à l’octet après
la mise à jour normale par `install -r`, avec vérification en lecture seule.

Aucun indicateur de crash, ANR ou `WindowLeaked` n’a été observé dans les journaux
de la campagne. Les dix fichiers Download appartenant aux tests ont été supprimés,
ainsi que les deux paquets de test ; la rotation libre a été restaurée.
La capture de la tablette (`dist/droid-Forge-mobile-0.4.0-tablet.png`, artefact local) présente
une fixture publique, sans contenu de la session utilisateur.

## Limites

La cible exécutée est la Galaxy Tab S10 FE+ SM-X620 sous Android 16,
avec des pages mémoire de 4 Kio. Android API 26, pages de 16 Kio à l’exécution, S Pen physique,
clavier externe, multifenêtrage, sessions prolongées, autres fournisseurs de
documents et système DROID matériel restent non vérifiés sauf preuve spécifique
ajoutée à ce rapport. L’alignement 16 Kio ELF/RELRO/ZIP est un contrôle statique.

L’import/export passe par le sélecteur Android, avec UTF-8 strict et une limite
de source de 4 Mio. Le moteur conserve les documents inconnus ou incomplets et
signale ses budgets d’analyse. MIDI, SysEx, compression, générateurs et mise à
jour de firmware ne sont pas implémentés. La description publique doit rester
limitée aux fonctions disponibles et au catalogue blue-7.

Une reconstruction depuis des sources fraîchement extraites a réussi en Release
hors ligne, avec un SDK installé et les dépendances déjà en cache. La comparaison
retrouve 977 payloads ZIP identiques ; le natif est lui aussi identique hors des
20 octets du descripteur du build ID GNU. Le
[rapport de reconstruction de l’archive finale](validation-data/0.4.0/source-build-verification-0.4.0.json)
consigne l’archive exacte, son inventaire et la comparaison des binaires.
Ce test n’est pas une promesse d’identité binaire entre machines. La livraison
directe prévoit APK et sources correspondantes. Les empreintes
de l’archive finale restent dans les rapports de livraison pour éviter une
dépendance circulaire avec les documents qu’elle contient.
