# Validation de droid Forge mobile 0.3.0

Rapport du **10 octobre 2026** : les **45 contrôles tablette** ont réussi sur
les APK figés ci-dessous. La version porte le code Android `5`.
Aucune réussite de 0.2.1 n'est utilisée ici comme
preuve d'exécution de 0.3.0. Le [rapport historique 0.2.1](VALIDATION.md) reste
disponible.

## Variantes et protection de la session

| Variante | Identifiant | Rôle de la campagne |
| --- | --- | --- |
| `tabletValidation`, version `0.3.0-validation` | `fr.androforge.validation` | Application isolée pour les essais d'édition, dialogues, rotation et mort du processus ; stockage privé indépendant |
| `tabletPreview`, version `0.3.0-preview` | `fr.androforge` | Mise à jour de l'installation normale, puis contrôle limité de la session existante sans édition du patch |
| `release`, version `0.3.0` | `fr.androforge` | Préparation de l'APK distribué avec les sources ; signature externe absente, revue sans signature publique |

Les préversions utilisent la signature de développement locale. Leur nom
affiché est « droid Forge mobile ». Les essais sur l'application isolée ne
démontrent pas à eux seuls la migration de la session normale. Le contrôle
séparé de cette mise à jour a préservé le nom et le texte du patch existant,
à l'octet près, sans édition. Aucun contenu, nom de fichier ou hash du patch
privé n'est publié dans ce rapport.
Les trois variantes finales ont des contenus `classes.dex` et
`libandroforge.so` identiques. Leurs manifestes, ressources d'identification
et signatures restent distincts ; le stockage isolé ne devient pas celui
de l'application normale.

## Résultats observés

| Vérification de 0.3.0 | Statut |
| --- | --- |
| Moteur C++ Release sur Windows | 86 191 vérifications réussies |
| Même suite avec ASan et UBSan combinés | 86 191 vérifications réussies en 3,41 s |
| Moteur natif ARM64 sur la tablette | 85 805 vérifications réussies |
| Pont JNI avec `-Xcheck:jni` | 122 vérifications réussies sur le gel du moteur |
| Cellules Java `ForgeCells` sous JDK | 23 vérifications réussies |
| Codec UTF-8 strict sous JDK | 64 vérifications réussies |
| Correspondance des brouillons lors des transitions de blocs | 56 vérifications réussies |
| Outils de livraison, signature, archive et comparaison des sources | 38 tests Python réussis |
| Lint `release`, `tabletPreview` et `tabletValidation` | 0 erreur, 2 avertissements par variante : `OldTargetApi`, `ChromeOsAbiSupport` |
| Parcours tablette isolé, SM-X620 sous Android 16 | 42 contrôles réussis sur l'APK de validation |
| Mise à jour et lecture de la session normale | 3 contrôles réussis ; nom et source préservés, contrôle strictement en lecture seule |
| Alignement 16 Kio et protection des ELF/ZIP | Contrôles statiques réussis sur les nouveaux APK ; exécution 16 Kio non testée |
| Reconstruction des sources 0.3.0 | Instantané frais de 404 fichiers reconstruit hors ligne en 29 s, 49 tâches |

Le [rapport tablette final](validation-data/0.3.0/tablet-audit-0.3.0.json) indique
`passed: true` et `tests_count: 45`, avec les empreintes propres aux deux
variantes. La capture de l'interface (`dist/droid-Forge-mobile-0.3.0-tablet.png`, artefact local)
montre un patch d'essai public.

## APK figés pour la campagne

| Variante | Taille | SHA256 |
| --- | --- | --- |
| Préversion normale (`dist/droid-Forge-mobile-0.3.0-arm64-preview.apk`, artefact local) | 8 816 003 octets | `0e3023c61c4de43b38c202762c10abbe480c093c4e1f2a8be47ee7db3d588450` |
| Validation isolée (`dist/droid-Forge-mobile-0.3.0-arm64-validation.apk`, artefact local) | 8 816 007 octets | `8b6b9eadd58a6d0ffc42250f0311dac15a6f4d459d90774dc36cc50765fd4f15` |
| Release sans signature publique (`dist/droid-Forge-mobile-0.3.0-arm64-release-unsigned.apk`, artefact local) | 8 811 903 octets | `b241b4159f6cc9515dd403d164586a9feb7a47beceb80a24fb77099da5c67c67` |

Les rapports statiques [preview](validation-data/0.3.0/apk-verification-preview-0.3.0.json)
et [validation](validation-data/0.3.0/apk-verification-validation-0.3.0.json) décrivent
les fichiers réellement soumis à la campagne. Le natif embarqué fait
1 917 168 octets : ses trois segments `LOAD` sont alignés sur 16 Kio,
la fin de RELRO est à `0x1d8000` et la bibliothèque commence à l'offset ZIP
65 536. La pile est non exécutable, les relocations sont résolues immédiatement
et les notices requises sont présentes. Le contrôle `zipalign -P 16`
complète l'inspection statique ; il ne démontre pas une exécution à pages de
16 Kio.

L'instantané des sources a été extrait dans un dossier neuf puis compilé
avec le SDK et les dépendances déjà en cache. L'APK reconstruit contient
342 contenus ZIP identiques ; seul `libandroforge.so` diffère de 20 octets,
tous dans le descripteur du build ID GNU découvert dans la note ELF. Chaque
autre octet du fichier natif est identique. Cette comparaison ne garantit
pas des APK identiques octet pour octet ; les dates ZIP et la signature
restent notamment hors comparaison des contenus.

## Benchmark du moteur

Le benchmark natif ARM64 utilise un document de **55 975 octets**, avec
**750 circuits** et des câbles tous reliés, sur **100 itérations** :

| Opération native | Temps moyen |
| --- | --- |
| Chargement et production de l'état JSON | 2,309 ms/opération |
| Édition, nouvelle analyse et production de l'état JSON | 2,175 ms/opération |

Ces mesures concernent le moteur sur la tablette, sans le rendu Android.
Le document et le JSON incluent le nouvel inventaire de câbles et les
expressions ; les chiffres du benchmark 0.2.1 ne sont pas directement
comparables. La consommation et la fluidité en session prolongée ne sont
pas mesurées ici.

## Parcours réellement exécutés sur la tablette

Les 42 contrôles isolés regroupent 9 vérifications des expressions,
17 des câbles, 13 compléments et 3 contrôles de clôture :

| Domaine | Résultat observé |
| --- | --- |
| Création d'un circuit | Paramètres typiques et valeurs officielles présents ; les quatre modes correspondent au catalogue ; choix conservé après mort réelle du processus |
| Cellules | Trois composantes pour les entrées, une destination pour les sorties ; application correcte et annulation restituant le texte exact ; expression complexe conservée brute |
| Brouillons et rôles des cellules | Facteur et décalage non appliqués conservés après rotation et mort du processus ; rôles appliqués avec base vide restaurés ; une nouvelle base conserve le multiplicateur |
| Menus de câbles | Appui long, création et choix d'un câble existant ; suppression de la seule composante visée ; renommage global sans confondre les préfixes ni toucher aux commentaires |
| Collision et fusion | Confirmation explicite ; annulation sans modification ; fusion et annulation de la fusion restituant le texte exact |
| Connexions | Deux extrémités appliquées en une étape d'annulation, dans les deux directions ; facteur et décalage conservés, y compris avec une base vide ; destination possible dans le multiplicateur ou le décalage |
| Suivi et dialogues | Occurrence reliée retrouvée sans édition, destination dépliée avec rôle de facteur en cache ; dialogues de nom et de valeur brute restaurés après rotation |
| Clôture | Nom d'application correct ; build optimisé non débogable ; aucun indice de crash, ANR ou fuite de fenêtre dans les journaux de l'UID pendant la campagne |

Les cinq fixtures créées par la campagne ont été supprimées, la variante
isolée désinstallée et le réglage original de rotation libre restauré.
Les trois contrôles de mise à jour normale ont ensuite confirmé la version
`0.3.0-preview`, code `5`, et la préservation exacte du patch existant.

Les essais du moteur, du JNI et du codec couvrent séparément l'UTF-8 strict,
Unicode, les limites de taille, les refus atomiques, les éléments inconnus
et les documents volumineux. Les anciens parcours Android de fichiers de
4 Mio et de récupération de l'export restent décrits dans le
[rapport historique 0.2.1](VALIDATION.md) ; ils ne sont pas comptés dans les
45 contrôles de cette campagne.

## Reproduction et preuves livrées

```powershell
cmake -S . -B core/build-clang -G Ninja '-DCMAKE_CXX_COMPILER=C:/Program Files/LLVM/bin/clang++.exe' '-DCMAKE_MAKE_PROGRAM=C:/Android/SDK/cmake/3.22.1/bin/ninja.exe' -DCMAKE_BUILD_TYPE=Release -DANDROFORGE_BUILD_BENCHMARK=ON
cmake --build core/build-clang
ctest --test-dir core/build-clang --output-on-failure
cmake -S . -B core/build-asan-ubsan -G Ninja '-DCMAKE_CXX_COMPILER=C:/Program Files/LLVM/bin/clang++.exe' '-DCMAKE_MAKE_PROGRAM=C:/Android/SDK/cmake/3.22.1/bin/ninja.exe' -DCMAKE_BUILD_TYPE=RelWithDebInfo '-DCMAKE_CXX_FLAGS=-fsanitize=address,undefined -fno-sanitize-recover=all -fno-omit-frame-pointer' '-DCMAKE_EXE_LINKER_FLAGS=-fsanitize=address,undefined -ldbghelp -lshell32'
cmake --build core/build-asan-ubsan
ctest --test-dir core/build-asan-ubsan --output-on-failure
.\gradlew.bat --offline --no-daemon --console=plain :app:assembleTabletValidation :app:lintTabletValidation
.\gradlew.bat --offline --no-daemon --console=plain :app:assembleTabletPreview :app:lintTabletPreview
powershell -NoProfile -ExecutionPolicy Bypass -File tools/test_jni_host.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File tools/test_forge_cells.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File tools/test_text_codec.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File tools/test_block_transitions.ps1
python -m unittest discover -s tools -p 'test_*.py'
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build_release.ps1 -Offline -SkipBundle
```

Les chemins LLVM et SDK ci-dessus sont ceux de la machine de vérification et
sont à adapter. Le mode hors ligne suppose le SDK et les dépendances déjà
installés ou en cache. `ExecutionPolicy Bypass` s'applique au seul processus lancé.
Les fichiers de livraison utilisent le préfixe `droid-Forge-mobile-0.3.0-` :
APK ARM64, archive `source.zip`, correspondance R8 et symboles natifs.
Les rapports publics de cette version utilisent notamment
`tablet-audit-0.3.0.json`, `apk-verification-preview-0.3.0.json`,
`apk-verification-validation-0.3.0.json`,
`release-apk-verification-0.3.0.json`, `release-sha256-0.3.0.json` et
`source-build-verification-0.3.0.json` dans `dist/`, pour conserver les preuves
historiques de 0.2.1.
Les rapports de livraison consignent les tailles, SHA256 et états de
signature. L'empreinte finale de l'archive reste hors des documents qu'elle
contient, pour éviter une dépendance circulaire.

L'import des graphismes PC a été vérifié séparément : 230 fichiers épinglés
au commit `60cbb7bbf9630c7d4a184e346a3d1120bde4d69f`, dont 228 PNG et les
notices GPL/crédits. Les 218 nouveaux PNG de prises et câbles restent
identiques à leurs objets Git. Cette vérification de ressources ne vaut
pas résultat de compilation ni essai de l'interface.

## Limites conservées

La cible déclarée reste ARM64, API 26 minimum et API 36 cible. Une exécution
réelle sur API 26 ou avec des pages mémoire de 16 Kio n'a pas été démontrée.
Le S Pen physique, le clavier externe, le multifenêtrage, les sessions
prolongées, la consommation, les autres fournisseurs et le matériel DROID
restent hors validation.

Les diagnostics locaux ne remplacent pas l'allocation des ressources et la
validation complète sur le système DROID. Les fonctions MIDI/SysEx, firmware,
bibliothèque PC, générateurs et compression compatible restent absentes.
Les budgets de fichier et d'interface sont décrits dans
[PORTING.md](PORTING.md). La signature publique nécessite toujours la clé
de l'éditeur ; aucune nouvelle clé n'est créée dans ce dépôt.
