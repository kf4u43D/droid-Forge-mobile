# Exemples officiels DROID blue-7

Le menu **Fichier → Exemples du manuel…** donne accès à 630 entrées embarquées.
L’aperçu indique leur provenance, leur page, le firmware blue-7, le type et les
précautions documentaires. Le chargement vérifie le SHA-256 puis demande
confirmation avant de remplacer le patch courant. Le texte complet est chargé,
même lorsque l’aperçu est limité à 16 K caractères ou 256 lignes.

## Corpus conservé

| Origine | Entrées | Conservation |
| --- | ---: | --- |
| Répertoire officiel `patches/` | 34 | Fichiers INI originaux à l’octet |
| Répertoire officiel `motokit/` | 2 | Fichiers INI lisibles originaux à l’octet |
| `droid-patch-snippets-blue-7.txt` | 332 | Corps originaux délimités par les séparateurs, à l’octet |
| Blocs de code de l’introduction du PDF | 96 | Transcriptions avec page, colonne et région |
| Blocs documentaires complémentaires du PDF | 11 | Fragments de sauvegarde et explications MIDI absents du TXT |
| Affectations isolées dans la prose du PDF | 155 | Fragments avec page et région ; aucun circuit inventé |

L’ensemble représente **845 074 octets de texte INI**, répartis en 46 programmes
complets, 355 extraits et 229 fragments. Un programme complet peut demander un
ensemble précis de contrôleurs ; cette catégorie ne constitue pas une validation
matérielle. Les deux INI Moto Kit sont destinés à l’étude : le README du fabricant
dit qu’ils sont trop grands pour un chargement direct sur le master et renvoie
aux patches compressés de son paquet officiel.

Le [manifeste](../resources/official/examples/manifest.json) contient les SHA-256,
tailles, chemins d’origine, numéros d’exemples et pages. Les extraits TXT indiquent
aussi les offsets exacts dans le fichier officiel. Les transcriptions PDF sont
conservées dans [leur inventaire](../resources/official/examples/manual-pdf-transcriptions.json).
Elles utilisent UTF-8 et des fins de ligne LF : on ne présente pas leurs octets
comme ceux d’un fichier INI original inexistant dans le PDF. Les fragments
volontairement incomplets, répétitions, paramètres anciens et erreurs du document
ne sont pas corrigés.

## Couverture du manuel

Le PDF blue-7 compte **413 pages**, dont les 124 premières précèdent la référence
des circuits. Le [rapport de couverture](../resources/official/examples/manual-coverage.json)
recense **3 010 occurrences de lignes de code autonomes** et **207 occurrences
d’affectations dans la prose**. Tous ces candidats se retrouvent dans le corpus.
Les occurrences identiques peuvent être couvertes par un même extrait.

Ce contrôle utilise la couche de texte et les polices de code du PDF. Il compare
des clés de recherche sans espaces, casse ou commentaire final ; cette opération
sert uniquement à mesurer la couverture et ne modifie jamais les fichiers.
Les captures d’interface, tableaux de registres, commandes de terminal et dumps
d’état ne sont pas des programmes INI. Le patch illustré dans la capture initiale
du Forge, `motoquencer_minimal`, est déjà inclus dans les fichiers officiels.

Pour reproduire l’audit avec Xpdf 4.04 et le PDF de référence :

```powershell
pdftohtml -f 1 -l 413 -r 20 -nofonts -q resources/official/droid-manual-blue-7.pdf .cache/manual-html
python tools/audit_manual_examples.py --html .cache/manual-html --report .cache/manual-coverage.json
```

## Lecture, export et diagnostics

Les 630 entrées ont passé **59 307 contrôles C++** de chargement, export exact,
relecture, stabilité d’état, historique inchangé et plages de texte. Les
**27 477 contrôles supplémentaires** comparent les directions des paramètres
avec le catalogue officiel indépendant et vérifient les diagnostics attendus.
Les 24 935 occurrences de paramètres connus sont reconnues ; 22 occurrences
de paramètres absents du catalogue blue-7 restent conservées et signalées.
Le parseur achève son analyse bornée pour les 630 documents.
La même suite native a réussi sur ARM64 et sur Windows avec ASan et UBSan
combinés. Le [rapport des sanitizers](validation-data/0.4.0/official-examples-asan-ubsan-0.4.0.json)
conserve les mêmes diagnostics attendus ; aucune erreur de sanitizer n’est apparue.

Les 36 fichiers INI officiels ne produisent aucune erreur locale et totalisent
huit remarques : quatre fichiers dépassent 64 000 octets, une affectation dépasse
63 octets et trois paramètres anciens ne figurent plus dans le catalogue.
Pour le corpus entier, les **347 erreurs et 108 remarques attendues** restent
visibles : fragments sans circuit, points de suspension, connexions extérieures,
paramètres anciens, doublons et littéraux incorrects d’origine.

Par exemple, le TXT officiel conserve `sine = sine` et les doublons
`compare1`/`ifequal1` de l’exemple `multicompare` p. 347, ainsi que
`0.0833333~V` dans l’exemple `watch` p. 411. Le PDF p. 94 écrit `_PTICH` à une
extrémité et `_PITCH` à l’autre. Aucun de ces éléments n’est remplacé en silence.
La [référence des diagnostics](../resources/official/examples/expected-diagnostics.json)
est associée aux hashes des sources ; un changement de diagnostic fait échouer
la vérification. Le [rapport public](validation-data/0.4.0/official-examples-0.4.0.json) détaille
chaque entrée sans inclure les données de la tablette.

```powershell
python tools/import_official_examples.py --verify
cmake -S . -B .cache/official-examples-build -G Ninja '-DCMAKE_CXX_COMPILER=C:/Program Files/LLVM/bin/clang++.exe' '-DCMAKE_MAKE_PROGRAM=C:/Android/SDK/cmake/3.22.1/bin/ninja.exe' -DCMAKE_BUILD_TYPE=Release
cmake --build .cache/official-examples-build --target androforge_official_examples_tests
python tools/verify_official_examples.py --engine .cache/official-examples-build/core/androforge_official_examples_tests.exe
```

Ces tests prouvent la conservation et la cohérence de l’analyse locale. Ils ne
certifient ni les ressources mémoire du firmware, ni l’ordre d’exécution des
signaux, ni le fonctionnement sur un système DROID physique. Le programme PC ne
fournit pas de mode CLI de validation : son argument ouvre un fichier dans son
interface et peut afficher des dialogues. Aucun parcours GUI PC automatique
n’est présenté comme une validation supplémentaire.

## Provenance et attribution

Les fichiers proviennent du [paquet officiel blue-7](https://dermannmitdermaschine.de/download/droid-blue-7.zip)
et du [manuel officiel](https://dermannmitdermaschine.de/download/droid-manual-blue-7.pdf).
Le [site de la marque](https://shop.dermannmitdermaschine.de/pages/der-mann-mit-der-maschine-improvised-electric-music)
identifie Mathias Kettner comme Der Mann mit der Maschine, concepteur du système.
Dr Delos est crédité pour sa contribution au port Android.

Le [README officiel](../resources/official/examples/UPSTREAM-README.txt) propose
les snippets pour les copier dans des patches. Il ne contient pas de licence
autonome explicite pour le corpus ; son autorisation GPL sur les graphismes ne
doit pas être étendue aux patches par déduction. Les READMEs, la provenance et
la [notice](../resources/official/examples/NOTICE.txt) accompagnent les fichiers
embarqués et les sources. La GPLv3 du logiciel conserve son propre périmètre.
Le manuel PDF reste une référence facultative hors APK et hors archive des
sources ; la compilation des exemples embarqués ne nécessite pas le PDF.

```powershell
python tools/import_official_examples.py --archive .cache/droid-blue-7.zip
python tools/import_official_examples.py --verify
```

L’importateur exige les empreintes épinglées du ZIP, du TXT, des READMEs, de
l’inventaire PDF et du manifeste. La vérification des copies dans les assets
fonctionne ensuite sans réseau ni archive fournisseur. Le `starter.ini` local
préexistant reste distinct de ce catalogue officiel.
