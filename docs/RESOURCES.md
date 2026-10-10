# Références et ressources officielles

Vérification effectuée le 10 octobre 2026. droid Forge mobile utilise un moteur
C++17 indépendant de Qt et une interface Android Java. Le code C++/Qt du Forge
PC reste une référence pour les comportements, la syntaxe et les étapes suivantes.

## Versions retenues

La [page officielle de téléchargements](https://shop.dermannmitdermaschine.de/pages/downloads)
annonce le firmware **blue-7**, publié le **5 décembre 2025**, avec **DROID Forge
1.7 pour Windows** et **1.7.1 pour Mac Apple Silicon**. Les
[notes de version blue-7](https://dermannmitdermaschine.de/download/Release%20notes%20blue-7.pdf)
précisent que Forge et firmware doivent rester compatibles et donnent le lien du
dépôt source.

Le dépôt de référence est [Zarkuun/droidforge](https://github.com/Zarkuun/droidforge),
copié localement dans `upstream/droidforge`. La référence PC est épinglée au
[commit 60cbb7bbf9630c7d4a184e346a3d1120bde4d69f](https://github.com/Zarkuun/droidforge/tree/60cbb7bbf9630c7d4a184e346a3d1120bde4d69f),
correspondant à Forge 1.7.1. Le HEAD observé au démarrage,
`cfdbc8fb37ae204ba88640a3cb1d77038577ad3d`, contient déjà un catalogue de firmware
`magenta-1` : il ne doit pas être étiqueté blue-7. Le catalogue de l'application
provient donc explicitement du paquet stable blue-7 de la marque.

Le [README du dépôt](https://github.com/Zarkuun/droidforge/blob/60cbb7bbf9630c7d4a184e346a3d1120bde4d69f/README)
et son [fichier LICENSE](https://github.com/Zarkuun/droidforge/blob/60cbb7bbf9630c7d4a184e346a3d1120bde4d69f/LICENSE)
identifient la licence GPL v3. Toute distribution d'un port reprenant ce code doit
respecter cette licence et conserver les mentions et sources correspondantes.

La version 0.2.0 reprend aussi neuf icônes du thème sombre et l’icône d’application
du dépôt PC, sans retouche. Le [manifest de ces icônes](../resources/official/forge-icons-manifest.json)
épingle leur origine et leurs SHA256. L’importateur
[import_forge_icons.py](../tools/import_forge_icons.py) lit les objets Git du commit
stable ; `--verify` vérifie les fichiers importés sans réseau ni dépôt PC. Les
notices `LICENSE` et `CREDITS` originales sont incluses avec les assets Android.

La version 0.3.0 ajoute **218 PNG de prises et câbles**, copiés à l'identique
depuis le même commit stable :

| Graphismes Forge PC | Destination Android | Nombre et dimensions | Taille des fichiers |
| --- | --- | --- | --- |
| `droidforge/images/cableicons/` | `app/src/main/assets/forge/cableicons/` | 109 PNG de 216 × 112 px | 527 159 octets |
| `droidforge/images/cables/` | `app/src/main/assets/forge/cables/` | 109 PNG de 472 × 112 px | 717 954 octets |

Chaque dossier contient les variantes de douze couleurs et `ghost.png`.
L'ajout représente **1 245 113 octets** avant emballage APK. Le manifeste
Forge contient désormais **230 fichiers**, dont **228 PNG**, pour
**1 371 383 octets** avec la licence et les crédits. Le README GPL du dépôt
et les crédits couvrent ces images ; aucune permission supplémentaire ni
modification de leurs couleurs n'a été ajoutée.

L'importateur lit les objets Git du commit épinglé ; les SHA256 attendus
sont conservés dans son code et comparés avant écriture. La vérification
de tous les fichiers et du manifeste fonctionne sans dépôt PC ni réseau :

```powershell
python tools/import_forge_icons.py --verify
```

## Fichiers présents dans le projet

L'importateur [tools/import_official.py](../tools/import_official.py) télécharge le
[paquet officiel blue-7](https://dermannmitdermaschine.de/download/droid-blue-7.zip).
Il extrait uniquement le catalogue, les graphismes et le manuel ; les fichiers
firmware `.fw` restent dans l'archive de référence et ne sont pas importés.

| Ressource | Source dans le ZIP | Destination locale |
| --- | --- | --- |
| Catalogue stable, 76 circuits | `utilities/editors/circuits.json` | `resources/official/circuits.blue-7.json` |
| 18 façades de modules PNG | `graphics/faceplates/*.png` | `app/src/main/assets/modules/` |
| 81 icônes PNG | `graphics/circuits/*.png` | `app/src/main/assets/circuits/` |
| Manuel blue-7, hors APK | `manual/droid-manual-blue-7.pdf` | `resources/official/droid-manual-blue-7.pdf` |
| Autorisation d'usage des graphismes | paragraphe `graphics` de `README.txt` | `resources/official/GRAPHICS-LICENSE.txt` |
| Provenance et empreintes des fichiers | calculées lors de l'import | `resources/official/manifest.json` |

Les chemins internes ci-dessus sont relatifs au dossier `droid-blue-7/` du ZIP.
Les 99 PNG représentent environ **5,27 Mo** de fichiers compressés. Les icônes
principales ont une taille de 512 × 512 px ; les façades sont plus grandes, par
exemple MASTER18 à 692 × 2917 px. Leur décodage doit utiliser la taille réellement
affichée et un cache borné pour limiter les allocations Android.

Le paquet complet fait **38 243 756 octets** et son SHA256 vérifié est :

```text
e3f3ef93bd70b88e101fbc0254df0c26534a432d241ba6b177d5648a1b68258c
```

Le manifeste conserve également la taille, le chemin original et le SHA256 de
chacun des **102 fichiers** importés. Le catalogue comporte
`firmware_version = blue-7`, `jacktable_initial_size = 168` et les budgets mémoire
DROID `master16 = 112867`, `master18 = 109015`. Ces valeurs concernent les modules
DROID, pas la mémoire de la tablette.

```powershell
python tools/import_official.py
python tools/import_official.py --verify
```

Le deuxième appel vérifie les fichiers sans accès réseau. L'archive est conservée
dans `.cache/droid-blue-7.zip`, à exclure du contrôle de version. Une archive
différente de l'empreinte déjà enregistrée provoque une erreur pour permettre la
revue d'une éventuelle modification de la release officielle.

Le README officiel autorise les images dans des projets privés ou des projets
open source sous GNU GPL ou une licence similaire ; il restreint les autres
usages. Cette autorisation est conservée avec sa provenance dans
[GRAPHICS-LICENSE.txt](../resources/official/GRAPHICS-LICENSE.txt).

Le code source PC contient aussi `droidforge/droidfirmware.json`,
`droidforge/images/faceplates/`, `droidforge/images/circuits/` et
`droidforge/resources.qrc`. Ils servent à comparer le comportement PC ; les
ressources Android importées sont identifiées séparément par le manifeste stable.

## Téléchargements complémentaires

| Référence officielle | Utilisation |
| --- | --- |
| [Forge Windows 1.7](https://dermannmitdermaschine.de/download/DROID%20Forge%201.7%20Windows.exe) | Comparer l'interface et les patches ; installation optionnelle, hors projet Android |
| [Forge Mac Apple Silicon 1.7.1](https://dermannmitdermaschine.de/download/DROID%20Forge%201.7.1%20M1.dmg) | Référence PC/Mac optionnelle |
| [Manuel blue-7](https://dermannmitdermaschine.de/download/droid-manual-blue-7.pdf) | Référence directe ; copie du même manuel déjà importée depuis le ZIP |
| [Paquet firmware blue-7](https://dermannmitdermaschine.de/download/droid-blue-7.zip) | Source déjà utilisée pour l'import ; firmware inclus uniquement pour une mise à jour matérielle future |
| [Notes blue-7 / Forge 1.7](https://dermannmitdermaschine.de/download/Release%20notes%20blue-7.pdf) | Compatibilité, DB8E et nouveaux circuits `display`, `watch`, `clockedtrigger` |

Le paquet contient notamment `droid-blue-7.fw`, `master18-blue-7.fw`,
`x7-orange-1012.fw`, `m4-orange-4.fw`, `e4-magenta-1.fw` et `db8e-neol-1.fw`.
Ces versions de contrôleurs sont indépendantes du numéro de firmware des masters.
L'importateur ne copie aucun de ces fichiers dans l'application.

## Exemples officiels ajoutés en 0.4.0

Le [catalogue des exemples](../resources/official/examples/manifest.json) reprend
34 INI du répertoire `patches/`, deux INI Moto Kit, les 332 extraits du TXT
officiel et 262 transcriptions PDF ou fragments documentaires complémentaires.
Ces **630 entrées** représentent **845 074 octets de texte INI**. Les copies
destinées à l’APK sont dans `app/src/main/assets/examples/` ; leurs sources,
empreintes et références restent dans `resources/official/examples/`.

Les INI et corps TXT sont conservés à l’octet. Les transcriptions PDF portent
leurs pages et régions et utilisent UTF-8/LF ; aucune ligne manquante, contrôleur
ou correction sémantique n’est ajouté. Le manuel de **413 pages** a été balayé
pour les blocs de code et affectations de sa couche de texte, y compris les pages
d’introduction absentes du TXT. Le [rapport de couverture](../resources/official/examples/manual-coverage.json)
retrouve les 3 010 lignes de code et 207 affectations inline recensées. La
[documentation des exemples](EXAMPLES.md) définit ce périmètre et ses limites.

Les exemples sont séparés en programmes, extraits et fragments. Certains sont
anciens et les configurations de contrôleurs sont nécessaires. Le
[README Moto Kit](../resources/official/examples/MOTO-KIT-README.txt) précise que
les deux grands INI lisibles servent à l’étude et sont trop volumineux pour un
chargement direct sur le master. Les versions compressées matérielles ne sont
pas transformées en patches éditables inventés.

L’importateur [import_official_examples.py](../tools/import_official_examples.py)
épingle ZIP, TXT, READMEs, inventaire PDF et manifeste ; sa vérification fonctionne
sans réseau. Le runner C++ et le contrôle indépendant du catalogue testent toutes
les entrées, en conservant les diagnostics documentaires attendus. Le contrôle
[verify_example_assets.py](../tools/verify_example_assets.py) vérifie aussi les
630 textes et trois notices directement dans un APK construit.

```powershell
python tools/import_official_examples.py --archive .cache/droid-blue-7.zip
python tools/import_official_examples.py --verify
```

Le [site officiel de l’artiste](https://shop.dermannmitdermaschine.de/pages/der-mann-mit-der-maschine-improvised-electric-music)
identifie **Mathias Kettner**, Der Mann mit der Maschine, comme concepteur du
système. La contribution au port Android est créditée à **Dr Delos**. Les
notices, READMEs et fichiers de licence d’origine accompagnent l’application.
Le README propose de copier les snippets dans ses patches, mais ne donne pas de
licence autonome explicite au corpus ; la clause des graphismes ne doit pas être
étendue par déduction aux patches. La GPLv3 du logiciel conserve son périmètre.

## Cible Samsung Galaxy Tab S10 FE+

Les caractéristiques ci-dessous viennent de la
[fiche Samsung FE+ 8/128 Go](https://www.samsung.com/us/tablets/galaxy-tab-s/galaxy-tab-s10-fe-plus-blue-128gb-sm-x620nlbaxar/)
et de l'[annonce officielle de la série FE](https://news.samsung.com/us/galaxy-tab-s10-fe-series-intelligent-experiences-premium-versatile-design/).

| Élément | Caractéristique vérifiée | Conséquence pour droid Forge mobile |
| --- | --- | --- |
| Processeur | Exynos 1580, octocœur | Livraison ARM64, analyse C++ en tâche de fond |
| Écran | LCD/TFT 13,1 pouces, 2880 × 1800 px, jusqu'à 90 Hz | Mise en page paysage, panneaux simultanés, dimensions en dp ; suivi de la fréquence réelle |
| Mémoire et stockage | 8 Go / 128 Go ou 12 Go / 256 Go selon modèle | Fonctionner sur la variante 8 Go ; cache limité et chargement des images à la demande |
| Stylet | S Pen inclus, sans BLE | Cibles tactiles utilisables au doigt ; prise en compte du stylet et des entrées clavier/souris |
| USB | USB Type-C, USB 2.0 | Import/export de fichiers ; USB MIDI à valider sur matériel au jalon correspondant |
| Stockage externe | microSD jusqu'à 2 To | Utiliser le sélecteur Android de documents pour les emplacements accessibles |
| OS au lancement | Android 15 | Tester Android 15 et les versions effectivement installées ; ne pas supposer que l'OS actuel est celui du lancement |

Les mesures locales du moteur et les parcours réellement exécutés sur SM-X620
sont consignés dans les rapports versionnés de validation. Elles ne constituent
pas une mesure de consommation ni de rendu prolongé. L’ergonomie au S Pen
physique et le multifenêtrage restent à vérifier sur appareil.

## Format et étapes de communication futures

Le premier jalon couvre l'édition locale et l'import/export du texte. Il
n'effectue **ni flash de firmware ni envoi MIDI**. Les éléments suivants sont
documentés pour préparer les futurs jalons matériels.

Le manuel blue-7, chapitre 5, pages 50 à 60, décrit un fichier texte `droid.ini`
placé à la racine de la microSD : déclarations de contrôleurs et de circuits entre
crochets, puis paramètres `nom = valeur`. Les circuits peuvent être répétés ;
l'ordre doit être préservé. Les commentaires commencent par `#`, les câbles
internes par `_` et les valeurs peuvent utiliser les unités et notations DROID.
Les chaînes entre guillemets nécessitent un traitement distinct des commentaires.
Le chapitre 5.8, page 59, les limite à l'ASCII imprimable (32 à 126) et ne permet
ni guillemet double interne ni séquence d'échappement avec une barre oblique inverse.
Les annotations ajoutées par Forge doivent aussi être préservées lors des
allers-retours. La validation complète de cette grammaire se construit à partir
du manuel et du parseur officiel, sans utiliser un parseur INI générique qui
fusionnerait les sections répétées.

Pour le transfert par fichiers, le manuel chapitre 5.1, page 50, indique que le
commutateur du X7/MASTER18 à gauche expose la microSD via USB. Après écriture de
`droid.ini` et éjection correcte du volume, le retour au centre recharge le patch.
L'application Android utilise d'abord le Storage Access Framework pour écrire
vers un emplacement choisi par l'utilisateur ; l'accès au volume USB exact reste
à vérifier sur la tablette.

Pour l'envoi rapide, le chapitre 8.9, pages 95 à 97, décrit le transfert USB MIDI
SysEx avec le commutateur à droite. Le message contient cinq octets d'en-tête, le
texte du patch, puis l'octet de fin. Les fichiers officiels
`utilities/sysex/windows/sysexhead.txt`, `sysextail.txt`, le script Linux
`utilities/sysex/linux/droidpatch` et le
[code Forge MIDIHost](https://github.com/Zarkuun/droidforge/blob/60cbb7bbf9630c7d4a184e346a3d1120bde4d69f/droidforge/os/midihost.cpp)
confirment les octets suivants :

```text
F0 00 66 66 50  <patch texte ASCII, octets de données sur 7 bits>  F7
```

`50` est le caractère ASCII `P`. L'utilitaire officiel compacte le texte et
supprime les caractères non ASCII ; la sérialisation doit conserver les espaces
et `#` qui appartiennent aux chaînes. Le fichier éditable UTF-8 et le message de
déploiement ASCII doivent donc être traités séparément, avec diagnostic des
caractères qui ne peuvent pas être transmis fidèlement. Un hack d'espacement
présent dans le code Mac traite une particularité de paquets USB ; il ne doit pas
être appliqué à Android sans test.

Le chapitre 9.5, page 101, décrit MASTER18 comme un périphérique USB MIDI
class compliant compatible avec le X7, parfois annoncé sous le nom « DROID X7 ».
Il reçoit sur tous les câbles MIDI USB et émet sur le câble 0. Il ne joue pas le
rôle d'hôte USB. Une future implémentation Android devra découvrir les ports,
supporter les deux types de masters, gérer déconnexion et permission USB,
et vérifier les grands messages SysEx sur un module réel.

Le flash suit les procédures propres au master et aux contrôleurs, décrites
séparément dans le manuel, notamment chapitre 8.10 pour le X7. La disponibilité
du ZIP ne vaut pas implémentation d'une mise à jour matérielle par l'éditeur.
