# Validation d'AndroForge 0.2.1

**Campagne terminée le 10 octobre 2026 : 42 contrôles tablette réussis.** Les résultats ci-dessous
ont été observés sur le poste Windows et une **Galaxy Tab S10 FE+ SM-X620**, sous
**Android 16**, ABI **arm64-v8a**. La tablette exécute **0.2.1-preview**,
`versionCode = 4`, avec des pages mémoire de **4 Kio**.

La livraison visée est un APK Android accompagné de ses sources GPL. Les essais
couvrent l'édition locale, les fichiers et le cycle de vie Android. Ils ne
valident pas le déploiement sur un système DROID. Le code et l'APK de préversion
sont figés. La signature publique reste à configurer avec la clé de l'éditeur.

## Moteur, JNI et code Java testé sur hôte

| Vérification | Résultat observé |
| --- | --- |
| Moteur C++ sous Windows, Release | 18 159 vérifications réussies |
| Même suite avec AddressSanitizer et UndefinedBehaviorSanitizer combinés | Réussite ; 18 159 vérifications |
| Moteur compilé ARM64 et exécuté sur la tablette | 18 035 vérifications réussies |
| Vrai pont JNI sous Windows/JDK 17, avec `-Xcheck:jni` | 73 vérifications réussies |
| Codec de texte partagé par import/export et sauvegarde | 64 vérifications réussies |
| Correspondance des blocs pour les brouillons | 56 vérifications réussies ; intégration revue et validée |
| Outils de livraison et signature | 30 tests réussis |

Les tests du moteur et du JNI couvrent notamment Unicode, emoji, BOM, NUL,
CRLF, textes contenant `#`, circuits et paramètres désactivés, alias du
catalogue, éditions ciblées, annuler/rétablir, refus sans mutation, historique
borné, entrées volumineuses et analyse partielle. Les passages JNI refusent
les paires UTF-16 incomplètes plutôt que de remplacer silencieusement le texte.

Le codec vérifie le décodage UTF-8 strict, les lectures fragmentées ou nulles
des fournisseurs, les erreurs d'entrée/sortie, les frontières de taille et
la restitution exacte. Les tests des transitions de blocs ciblent les circuits
de même nom, les clones, les insertions et suppressions, l'annulation et les
correspondances ambiguës. La revue de cette intégration et les parcours
d'interface correspondants sur la tablette sont terminés.

Commandes de reproduction hôte :

```powershell
cmake -S . -B core/build-clang -G Ninja '-DCMAKE_CXX_COMPILER=C:/Program Files/LLVM/bin/clang++.exe' '-DCMAKE_MAKE_PROGRAM=C:/Android/SDK/cmake/3.22.1/bin/ninja.exe' -DCMAKE_BUILD_TYPE=Release -DANDROFORGE_BUILD_BENCHMARK=ON
cmake --build core/build-clang
ctest --test-dir core/build-clang --output-on-failure
powershell -NoProfile -ExecutionPolicy Bypass -File tools/test_jni_host.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File tools/test_text_codec.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File tools/test_block_transitions.ps1
python -m unittest discover -s tools -p 'test_*.py'
```

`ExecutionPolicy Bypass` s'applique uniquement au processus lancé pour le test ;
la politique PowerShell persistante n'est pas modifiée.

La compilation instrumentée utilise `core/build-asan-ubsan`, avec
`-fsanitize=address,undefined -fno-sanitize-recover=all`. Le test natif ARM64 s'exécute
séparément de l'interface Android ; ses résultats ne mesurent pas le rendu.

Les ressources officielles ont été vérifiées : 102 fichiers blue-7 par SHA256,
76 circuits et 2 594 paramètres après expansion des groupes, puis 12 fichiers
Forge supplémentaires, dont 10 PNG, la GPL et les crédits. Leur provenance
est épinglée au commit stable du dépôt PC.

```powershell
python tools/import_official.py --verify
python tools/import_forge_icons.py --verify
```

## Essais réellement observés sur la tablette

Le parcours final a réussi ses **42 contrôles** sur l'APK
figé décrit ci-dessous. Le [rapport tablette](validation-data/0.2.1/tablet-audit-0.2.1.json)
indique `passed: true` et associe les résultats à son empreinte SHA256.
Les vérifications de grandes cartes avec clavier ouvert ont également confirmé
la conservation du focus de `input1` et du brouillon `I2` après changement de page.

| Domaine | Résultat observé sur l'appareil |
| --- | --- |
| Mise à jour de l'application | Patch existant de 317 octets conservé à l'identique |
| Éditeur du texte source et rotation | Brouillon conservé entre portrait et paysage ; appliquer puis annuler restaure exactement le patch |
| Plusieurs champs édités | Appliquer un champ conserve le brouillon d'un autre circuit ; annuler conserve le brouillon sans rapport avec l'édition |
| Brouillon et mort du processus en arrière-plan | Brouillon restauré après relance du processus |
| Export Android et mort du processus | Instantané d'export récupéré après un véritable `am kill`, avec absence du PID vérifiée ; fichier exporté identique octet pour octet |
| Import UTF-8 invalide | Refus explicite, sans remplacement du document courant |
| Import de 4 194 305 octets | Refus explicite ; le document courant reste inchangé |
| Aller-retour Unicode | UTF-8, BOM et CRLF conservés octet pour octet |
| Champ de 8 193 octets | Texte intégral conservé et réexporté ; analyse partielle signalée, modifications structurées bloquées, commandes de fichier disponibles |
| Patch dense de 4 Mio | Aller-retour exact ; analyse partielle signalée ; accès à l'éditeur intégré borné pour garder l'application utilisable |
| Commentaires seuls, 4 Mio et environ deux millions de lignes | Aller-retour exact ; le texte intégral reste exportable sans l'afficher dans un `TextView` géant |
| Ajout d'un paramètre et rotation | Nom sélectionné et valeur saisie conservés |
| Insertion d'un paramètre | Brouillon d'un circuit suivant conservé après insertion puis annulation |
| Valeur entourée d'espaces | Après application, aucun faux avertissement de brouillon lors de l'export |
| Catalogue officiel | Recherche, aperçu, ajout et annulation avec restitution exacte du source |
| Pagination | Pages de 32 paramètres ; brouillon non appliqué conservé lors des changements de page |
| Circuits de même nom, distincts ou identiques | Supprimer le circuit central, annuler puis rétablir conserve le brouillon du dernier circuit ; appliquer modifie uniquement celui-ci |
| Clones ambigus après édition du source | Brouillon conservé pour récupération explicite, y compris après rotation, sans attribution silencieuse à un autre bloc |
| Grandes cartes et clavier | Focus du champ `input1` maintenu avec le clavier ouvert ; brouillon `I2` conservé après page suivante puis précédente |
| Huit recréations de l'activité | Document conservé et interface utilisable |
| À propos | Version installée et notices GPL présentes |
| Arrêt forcé et relance | Source d'origine conservée exactement ; un éditeur de source ouvert peut être restauré avec son état |
| Fin de la campagne | Nom `droid.ini` et source d'origine de 317 octets restaurés exactement ; fichiers d'essai supprimés et rotation libre d'origine rétablie |

Les essais d'édition, du catalogue, des sections, du rack, du clavier, du
pliage et de la rotation réalisés en 0.2.0 servent de couverture antérieure.
Ils ne remplacent pas l'exécution finale de la version corrigée. Le suffixe
`.ini` à l'export est conservé ; la correction de l'ancien suffixe `.txt`
reste présente.

Les journaux recueillis pendant l'audit ne contiennent aucun indice de crash,
d'ANR ou de fuite de fenêtre pour l'application. Le rapport confirme le
nettoyage des fichiers d'essai et la restauration de la rotation libre.
Ces observations ne prouvent pas l'absence de défaut dans une session prolongée.

## Frontières de traitement et comportement visible

| Limite | Comportement prévu et vérifié au niveau indiqué |
| --- | --- |
| Fichier de patch : 4 Mio, soit 4 194 304 octets | L'octet supplémentaire est refusé avant remplacement ; aller-retour exact à la frontière testé |
| Analyse du moteur : 4 096 blocs, 32 768 affectations, 256 diagnostics, 1 Mio de champs cumulés, 8 Kio par champ | Analyse partielle explicite ; texte complet conservé, édition structurée bloquée, export disponible |
| Éditeur de source intégré : 256 K caractères, 4 096 lignes, 16 384 caractères par ligne | Textes plus grands conservés et exportables pour édition externe ; la vue Android ne tente pas leur mise en page intégrale |
| Champ de paramètre dans l'interface : 4 096 caractères | Les valeurs longues restent dans le document ; elles ne sont pas tronquées lors du rendu |
| Paramètres des cartes | Pagination de 32 paramètres ; panneau de module limité aux 32 premiers, le source conservant les suivants |
| Saisie des brouillons : 1 048 576 caractères cumulés | Limite préventive à la saisie, sans suppression des valeurs déjà présentes ; les erreurs d'entrée/sortie de sauvegarde restent visibles |

Les budgets de caractères de l'interface comptent les unités UTF-16 de Java :
un emoji peut en consommer deux. Les limites en octets du moteur et des fichiers
sont distinctes. Un patch conservé et exportable peut donc dépasser les limites
de l'éditeur intégré ou des diagnostics structurés.

## Mesure native sur la Galaxy Tab S10 FE+

Benchmark ARM64 exécuté sur la tablette : document de **53 085 octets**,
**750 circuits**, **100 itérations**.

| Opération | Temps moyen observé |
| --- | --- |
| Chargement et production du JSON | 1,426 ms par opération |
| Édition, analyse et production du JSON | 1,116 ms par opération |

Cette mesure concerne le moteur C++ natif. Elle ne mesure ni le rendu des
cartes, ni le clavier, ni la latence d'un fournisseur de documents, ni la
consommation. Elle n'est pas extrapolée à d'autres appareils.

## Compilation et livraison Android

JDK 17, AGP 8.13.2, Gradle 8.13, SDK 36, Build Tools 36.0.0,
NDK 28.2.13676358 et CMake 3.22.1. La variante optimisée utilise C++ Release,
ThinLTO, R8 et réduction des ressources, avec le pont JNI conservé. Le dernier
build analysé par lint termine avec **0 erreur et 2 avertissements** :
`OldTargetApi` et `ChromeOsAbiSupport`. La cible 36 et le périmètre ARM64 sont
conservés ; ces avertissements ne sont ni masqués ni placés dans un baseline.
Ce résultat concerne le dernier build du code figé.

Le manifeste conserve `fr.androforge`, Android 8.0 minimum déclaré (API 26),
cible API 36 et aucune permission de réseau ou de stockage général. La
sauvegarde cloud et le transfert des données privées sont exclus. Le manuel
PDF de référence reste hors APK.

Les nouveaux binaires ont passé le contrôle statique des segments ELF, de la
fin de RELRO et du rangement ZIP des bibliothèques sur **16 Kio**. La fin de
RELRO produite est alignée à `0x198000`, avec une pile non exécutable et des
relocations résolues immédiatement. Le précédent APK 0.2.0 possédait une fin
RELRO alignée uniquement à 4 Kio ; ce défaut a été corrigé. L'exécution réelle
sur un système à pages de 16 Kio reste **non testée**.

L'AAB complémentaire passe les vérifications statiques de configuration
`PAGE_ALIGNMENT_16K`, des ELF, des symboles et des notices. Ces contrôles ne
constituent pas une validation Google Play ou une installation des APK produits
depuis l'AAB. La distribution prévue reste l'APK et les sources.

```powershell
.\gradlew.bat --offline --no-daemon --console=plain :app:assembleTabletPreview :app:lintTabletPreview
python tools/verify_apk.py dist/AndroForge-0.2.1-arm64-preview.apk --report dist/apk-verification.json
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build_release.ps1 -Offline -SkipBundle
```

L'APK validé sur la tablette est
AndroForge-0.2.1-arm64-preview.apk (`dist/AndroForge-0.2.1-arm64-preview.apk`, artefact local),
**7 251 983 octets**, SHA256 :
`9191832221b90eb8750fc55165ffab537dd156794889fed74eb034dbc9f022f4`.
La signature de développement a été vérifiée avec `apksigner`, puis
l'alignement ZIP avec `zipalign -P 16` et le vérificateur d'APK.

L'APK de la tablette est signé avec la clé locale de développement. Une
configuration externe est prévue pour la signature publique ; aucune clé de
production n'a été créée. Une signature déclarée mais invalide provoque un échec,
même pour un fichier de revue. Voir [l'audit de livraison](audit-release.md).

Le script de livraison prépare et contrôle les autres fichiers attendus :
`dist/AndroForge-0.2.1-arm64-release-unsigned.apk` pour la revue sans clé,
et `dist/AndroForge-0.2.1-source.zip` pour les sources correspondantes.
Les tailles, SHA256 et états de signature de ces fichiers sont consignés par le script dans les
rapports `dist/apk-verification.json`, `dist/release-apk-verification.json`,
`dist/release-sha256.json` et le manifeste de l'archive. Ces rapports font
référence pour chaque exécution ; l'empreinte de l'archive n'est pas recopiée
dans les documents qu'elle contient.

## Reconstruction depuis l'archive des sources

Une extraction fraîche des **177 fichiers sources** de l'archive a réussi
`--offline :app:assembleRelease` en **29 secondes**, avec **49 tâches**.
Le SDK et les dépendances Gradle étaient déjà installés ou présents dans le
cache global du poste ; ce résultat ne garantit pas une première compilation
hors ligne sur une machine vide.

Les entrées des deux APK de revue comparés sont identiques à l'exception de
la bibliothèque native. La comparaison de ses 28 sections ELF et des en-têtes
confirme un code, des données et des relocations identiques. La différence se
limite à **19 octets du build ID GNU de 20 octets**, dans `.note.gnu.build-id`.
Les chemins de compilation présents dans les informations de débogage sont
une explication possible, non démontrée. Cette reconstruction valide les
sources nécessaires à la compilation ; elle ne démontre pas une reproduction
du binaire octet pour octet.
Le [rapport de reconstruction](validation-data/0.2.1/source-build-verification.json)
consigne les empreintes et la comparaison détaillée des fichiers.

## Essais et fonctions encore hors validation

Restent non vérifiés : Android API 26 réellement exécuté, environnement à pages
de 16 Kio, utilisation physique du S Pen et d'un clavier externe,
multifenêtrage, sessions prolongées, consommation, autres fournisseurs de
documents et lecteurs de carte, connexion USB/MIDI avec un module DROID.

Les diagnostics locaux ne couvrent pas encore l'allocation complète des
ressources du matériel DROID. Bibliothèque PC, générateurs, compression
compatible, MIDI/SysEx, suivi temps réel et mise à jour des firmwares restent
à porter. La qualité de l'éditeur local ne vaut pas validation de ces fonctions.
