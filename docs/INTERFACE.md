# Interface de droid Forge mobile 0.4.0 et référence Forge

Cette version rapproche l’éditeur Android du Forge PC 1.7.1. La référence est le
code source au commit `60cbb7bbf9630c7d4a184e346a3d1120bde4d69f` et les captures
du [manuel officiel blue-7](https://dermannmitdermaschine.de/download/droid-manual-blue-7.pdf), chapitre
2, notamment pages 9 à 11.

| Élément du Forge PC | Reprise Android |
| --- | --- |
| Barre d’outils verticale, icônes avec libellés | Commandes principales à gauche, avec les icônes officielles du thème sombre |
| Rack horizontal au-dessus de l’édition | Façades officielles des modules déclarés, visibles pendant l’édition |
| Zoom et navigation dans le rack | Pincement, déplacement, boutons −/+ et retour à 100 %, avec position conservée |
| Étiquettes des prises | Nom court et description au format Forge, éditables depuis le rack |
| Sections à gauche et vue des circuits à droite | Navigation des sections présentes dans le fichier et grande zone de circuits |
| Bandeau de circuit et lignes de paramètres | Nom du circuit, paramètres et valeurs affichés ensemble |
| Commentaire d’un circuit | Texte visible et commande Commentaire, conservés dans les commentaires INI |
| Entrée représentée par trois cellules | Valeur × multiplicateur + décalage ; une sortie conserve une seule cellule de destination |
| Paramètres d'un nouveau circuit | Exemple typique issu des métadonnées du catalogue officiel |
| Câbles internes nommés | Sélection et actions contextuelles sur les cellules ; même nom pour les occurrences reliées |
| Entrées cyan, sorties rouges | Couleurs tirées du thème sombre original selon le catalogue officiel |
| Sélecteur de circuits dans une fenêtre | Catalogue ouvert depuis la commande Circuit |
| Fond sombre neutre | Gris du Forge, à la place des panneaux bleus de la version 0.1 |

Les cibles tactiles restent adaptées à Android. Les circuits utilisent une liste
virtualisée ; les images sont chargées à la taille utile et conservées dans le
cache borné. Le moteur C++ reste responsable du texte, des modifications et de
l’historique.

Le navigateur lit les séparateurs de sections du Forge dans les commentaires,
puis affiche les circuits de la section choisie. La création, la suppression et
le déplacement des sections, les sélecteurs complets de registres et les
fonctions MIDI restent à porter. Les contrôleurs sont affichés
dans le rack ; un toucher de leur façade ouvre leurs détails.

Le rack reprend ses proportions de modules, reconnaît les registres des prises
et affiche les étiquettes présentes dans le patch. Son zoom va de 50 à 400 % ;
le pincement et les boutons sont disponibles, et le pourcentage remet la vue
à 100 %. Un appui long sur une prise, le bouton « Labels » ou le menu
« Modifier les labels… » ouvre le nom court et sa description. Les modules G8 et X7 nécessaires sont déduits du patch,
avec le type de master déclaré ; ils ne sont pas ajoutés comme contrôleurs dans
le fichier. Un commentaire de circuit s’édite depuis sa carte. Les dialogues et
leurs brouillons sont conservés lors des changements d’activité.

Une valeur est appliquée avec la coche de sa ligne ou la touche de validation du
clavier. Les brouillons de valeurs sont conservés lors du défilement et de la
rotation, sans écrire dans le fichier avant validation. Chaque circuit peut être
plié ou déplié. Les raccourcis clavier `Ctrl+Z`, `Ctrl+Maj+Z` / `Ctrl+Y`, `Ctrl+O`
et `Ctrl+S` donnent accès aux actions d’édition et de fichier.

La version 0.2.1 renforce la conservation des brouillons lors des changements
d’activité et prépare l’export dans le stockage privé avant l’ouverture du
sélecteur Android. Les très longues listes de paramètres sont affichées par
pages afin de limiter les vues créées simultanément.

## Cellules et paramètres initiaux

Forge PC représente une entrée par trois atomes, sous la forme `A * B + C`.
Les cellules peuvent contenir des nombres, registres ou câbles autorisés par
la grammaire ; une sortie désigne une seule destination. Une division importée
et une expression non décomposable doivent être préservées, sans normalisation
silencieuse lors de leur affichage. L'édition de la ligne de source reste le
recours pour une expression qui dépasse les cellules prises en charge.

Les composantes vides sont omises : un multiplicateur `2` et un décalage `3`,
sans valeur, donnent `2+3`. Après application, l'interface conserve le rôle
de chaque cellule dans la session privée, y compris après rotation ou
redémarrage. Ces rôles ne sont pas ajoutés au fichier INI : un nombre seul
réouvert depuis une source externe est interprété comme une valeur simple.

Un nouveau circuit commence avec l'exemple typique du catalogue : paramètres
marqués usuels ou essentiels, groupes limités à leur taille initiale et valeurs
numériques initiales des entrées. Les sorties n'ont pas de destination
automatique. Les paramètres sans valeur restent des lignes actives à compléter,
comme dans Forge PC ; leurs diagnostics indiquent les valeurs manquantes.
Ils ne représentent pas une connexion matérielle déjà valide.

Le menu du fichier et l'aperçu d'ajout proposent les quatre sélections du PC :
tous les paramètres, exemple typique, essentiels et aucun. Le choix est conservé
dans la session ; l'exemple typique est le choix initial. Les groupes Android
respectent aussi leur index initial officiel, dont `tune0` pour `calibrator`.

## Câbles et menus contextuels

Un câble interne est un nom de signal, écrit `_NOM` dans le patch. Forge PC
utilise des prises colorées dans les cellules. Le glissement de la souris
sélectionne des cellules ; la création de connexion passe par les commandes
de début et de fin ou par un raccourci. Il ne dessine pas de fil courbe entre
les cartes de circuits.

La référence PC propose les actions commencer, terminer et annuler une
connexion, suivre la prochaine occurrence et renommer un câble. Le renommage
agit dans tout le patch ; un nom déjà utilisé demande confirmation parce
qu'il fusionne les deux signaux. Effacer une cellule retire sa seule composante,
tandis qu'effacer un paramètre retire la ligne.

Les menus Android reprennent « Commencer la connexion », « Terminer la
connexion », « Annuler la connexion » et « Suivre le câble ». Ils proposent
aussi « Choisir un câble… », « Créer un câble… », « Renommer le câble… »,
« Supprimer cette valeur » et « Éditer la valeur brute… ». Les actions
disponibles dépendent de la cellule et de l'état de connexion. Un appui long
sur une cellule, ou un toucher de son bouton contextuel, ouvre le menu.
Le bouton cible la composante sélectionnée de la ligne. Pendant une connexion,
toucher la cellule ou le port de destination termine le raccordement ;
l'appui long continue d'ouvrir le menu. La connexion doit relier une entrée
et une sortie connues. Elle préserve les autres composantes et constitue une
seule étape d'annulation.

« Suivre le câble » passe à ses occurrences dans les autres sections et pages
de paramètres, en dépliant la carte si nécessaire. Les brouillons sont pris
en compte. Le renommage propose « Annuler » ou « Fusionner » lorsqu'un nom
existe déjà ; il reste bloqué si l'inventaire des câbles est incomplet.
Les prises utilisent les PNG PC avec miroir pour les sorties et prise grisée
pour les éléments inactifs. Aucun fil courbe n'est ajouté entre les cartes.

Les 64 contrôles tablette de cette version sont consignés dans
[VALIDATION-0.4.0.md](VALIDATION-0.4.0.md) : 61 contrôles isolés et 3 contrôles de
mise à jour normale en lecture seule. Les
[45 contrôles de 0.3.0](VALIDATION-0.3.0.md) et la
[validation 0.2.1](VALIDATION.md) restent des rapports historiques.

## Exemples officiels et menus

Le menu Fichier propose « Exemples du manuel… ». Les 630 entrées sont classées
par origine : patches officiels, Moto Kit, circuits du manuel, introduction,
compléments et affectations documentaires. L’aperçu indique le type, les pages,
le firmware blue-7 et les précautions de contexte. Les programmes et fragments
restent fidèles à leur source : aucune ligne manquante n’est inventée et les
diagnostics d’origine restent visibles. L’ouverture vérifie l’empreinte du texte
et demande confirmation avant de remplacer le document.

La [documentation du corpus](EXAMPLES.md) précise sa couverture et ses tests.
La [matrice des menus PC et Android](MENUS.md) distingue les commandes portées,
les adaptations tactiles et les fonctions encore absentes. À propos conserve
l’attribution de DROID et Forge PC à Mathias Kettner / Der Mann mit der Maschine,
et la contribution au port Android de Dr Delos.

## Ressources et provenance

Les icônes de la barre d’outils proviennent directement des objets Git du dépôt
[DROID Forge](https://github.com/Zarkuun/droidforge), au commit stable épinglé.
Elles sont copiées sans retouche ni changement de couleur. Le manifeste
[forge-icons-manifest.json](../resources/official/forge-icons-manifest.json)
contient les chemins d’origine, les dimensions et les SHA256. Les notices GPL et
les crédits originaux sont inclus dans les assets de l’application.
Les 218 PNG de prises et câbles ajoutés en 0.3.0 proviennent du même commit,
avec leurs deux formats PC d'origine. La vérification couvre 230 fichiers,
dont 228 PNG et les deux notices originales.

```powershell
python tools/import_forge_icons.py
python tools/import_forge_icons.py --verify
```

Les sources de présentation PC utilisées pour cette adaptation sont
`droidforge/main/mainwindow.cpp`, `droidforge/colors_dark.h`,
`droidforge/patchview/circuitview.cpp` et
`droidforge/patchview/circuitchooser/circuitchoosedialog.cpp`.
Les comportements des paramètres et câbles proviennent aussi de
`droidforge/main/droidfirmware.cpp`, `droidforge/patch/patchsection.cpp`,
`droidforge/patch/jackassignmentinput.cpp`,
`droidforge/patchview/patchsectionview.cpp` et `droidforge/main/patchoperator.cpp`.
