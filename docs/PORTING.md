# Choix techniques de droid Forge mobile 0.4.0

Les preuves de cette version figurent dans [VALIDATION-0.4.0.md](VALIDATION-0.4.0.md).
Les résultats [0.3.0](VALIDATION-0.3.0.md) et [0.2.1](VALIDATION.md)
restent séparés comme historiques.

## Répartition

`core/` contient le modèle de document, le parseur, les diagnostics, les modifications ciblées et l’historique en C++17. Il n’utilise ni Qt ni Android. Le schéma de validation est généré depuis le catalogue officiel blue-7 ; aucune liste de circuits n’est inventée à la main.

`app/src/main/cpp/bridge.cpp` fournit le pont JNI. L’interface Java gère les vues Android, les URI du Storage Access Framework et le cycle de vie. Les appels au moteur et les opérations disque utilisent une file de travail unique. Le moteur reste la source des données du document ; les vues reçoivent des instantanés JSON.

Le Forge PC est fondé sur Qt Widgets, avec des services de fichiers, PDF, MIDI et des générateurs externes spécifiques au bureau. Une copie de référence est conservée à part et son commit stable est reproductible. Le port reprend la grammaire, le catalogue et les assets officiels en remplaçant les couches de présentation et d’accès aux fichiers. Depuis la version 0.2.0, la disposition suit aussi le Forge PC : barre d’outils à gauche, rack supérieur, navigation et vue des circuits. Voir [la correspondance des interfaces](INTERFACE.md).

## Galaxy Tab S10 FE+

- ARM64 uniquement, optimisation Release et LTO lorsque le compilateur le permet.
- NDK r28, alignement ELF, fin de RELRO et emballage des bibliothèques pour les pages de 16 Kio, avec contrôles des APK/AAB produits.
- Mise en page selon la largeur disponible, sans taille en pixels ni orientation imposée.
- Grandes commandes tactiles ; comportement du S Pen physique à vérifier sur appareil.
- Listes virtualisées, aperçu du seul bloc sélectionné, catalogue chargé une fois.
- Absence de boucle de rendu continue : l’éditeur se met à jour sur les actions.
- Graphismes officiels chargés aux dimensions utiles, historique borné en mémoire et analyse bornée des entrées volumineuses.

Ces choix limitent le travail et les allocations. Les parcours testés sur la tablette et le benchmark natif sont décrits dans la validation ; la fluidité en session prolongée, la consommation et le comportement du S Pen physique restent à mesurer.

Depuis la version 0.2.1, R8 optimise le code Java en conservant les noms des méthodes JNI, les symboles natifs et la correspondance R8 pour les rapports d’incident. Le benchmark natif mesure le moteur et la production de JSON ; il ne mesure pas le rendu ni la consommation de l’application. Les nouvelles mesures et vérifications figurent dans [VALIDATION-0.4.0.md](VALIDATION-0.4.0.md) ; les rapports 0.3.0 et 0.2.1 conservent leurs propres mesures.

## Paramètres et câbles

La version 0.3.0 reprend les trois composantes des paramètres d'entrée,
`valeur * multiplicateur + décalage`, et la destination unique d'une sortie.
La présentation par cellules ne doit pas réécrire une expression importée
lors de son simple affichage. L'édition et les opérations de connexion passent
par le moteur C++ et son historique, afin de conserver une mutation atomique
pour l'annulation.

Les rôles des cellules appliquées sont conservés dans un cache privé distinct
des brouillons. Il associe les composantes au texte exact et évite de déplacer
un multiplicateur seul vers la cellule de base après application. Ce cache
est restauré avec la session, limité à 4 096 entrées et 1 048 576 caractères.
Une modification effective du source, de l'historique ou un nouveau document
efface les rôles devenus sans référence. Aucune métadonnée de rôle n'est
ajoutée au fichier INI ; une source externe est de nouveau interprétée selon
sa syntaxe.

Les paramètres initiaux proviennent du catalogue blue-7, avec les métadonnées
de sélection typique et de groupes. Le moteur propose quatre modes : tous,
typique, essentiels et aucun. Les groupes respectent `start_at` et la taille
initiale demandée. Les entrées ayant un défaut numérique commençant par un
chiffre sont initialisées comme dans le PC ; les autres restent vides, dont
les mentions `unpatched` ou les valeurs négatives. Une destination de sortie
ne se déduit pas du catalogue : elle doit être choisie par l'utilisateur.
Les lignes vides restent actives et sont signalées comme valeurs à compléter.

Les câbles sont des atomes nommés, distincts du texte entre guillemets et des
commentaires. Le renommage doit cibler leurs occurrences dans les expressions,
sans remplacement global de chaînes arbitraires. Les noms sont comparés sans
tenir compte de la casse ; les occurrences désactivées sont aussi renommées.
La connexion requiert une entrée et une sortie connues, et peut relier des
cellules de différentes composantes. Un signal actif sans émetteur ou avec
plusieurs sorties actives produit une erreur si l'inventaire est complet et
ses occurrences connues. Une sortie sans récepteur produit une remarque.
Un inventaire borné ou contenant des occurrences inconnues signale une
validation partielle plutôt que de conclure à une erreur de câblage certaine.
Ces diagnostics n'interdisent pas la poursuite de l'édition locale. Les
collisions de noms demandent une confirmation de fusion dans l'interface ;
le moteur refuse par défaut une collision sans accord explicite. L'interface
bloque le renommage d'un inventaire incomplet. Une connexion modifie ses deux
extrémités en une étape d'historique et garde les composantes non ciblées.
Les trois cellules des brouillons et les dialogues de nom ou de valeur brute
sont conservés dans l'état de session. Le suivi d'un câble tient compte des
sections, de la pagination et des cartes pliées. Ces mutations et la récupération
des brouillons font partie de la validation 0.3.0.

## Rack, annotations et exemples officiels

Le rack utilise une vue Android avec façades officielles, proportions des modules,
panoramique et zoom borné de 50 à 400 %. Ses commandes tactiles et son état privé
évitent une boucle de rendu continue. Les registres des contrôleurs, les G8/X7
inférés et le type de master déclaré proviennent de l’instantané du moteur ; la
vue ne rajoute pas de déclarations matérielles dans le fichier. La parité des
commandes avec le PC est détaillée dans [MENUS.md](MENUS.md).

Le moteur lit et modifie les étiquettes au format Forge : registre, nom court
entre crochets et description. Le nom court est limité à 18 unités UTF-16, la
description à 4 Kio, et l’inventaire à 4 096 étiquettes. Les doublons restent
conservés à l’import ; l’étiquette effective est la dernière, comme dans le PC.
Effacer une étiquette retire ses définitions reconnues sans toucher les autres
annotations. Chaque mutation reste atomique et annulable. Les caractères et
formes qui changeraient la structure de l’INI sont refusés avant mutation.

Les commentaires de circuits sont reconnus séparément des sections, étiquettes
et affectations désactivées. Une édition ajoute des lignes de commentaire sans
régénérer le patch. La saisie est bornée à 16 Kio et 256 lignes ; une ligne qui
serait relue comme bloc, paramètre, section ou annotation doit être modifiée
dans le source. Les dialogues conservent leurs brouillons et proposent une
récupération explicite si le circuit d’origine ne peut plus être identifié.

Le catalogue des exemples est fourni dans les assets, avec une copie et son
manifeste dans les sources. Il contient 630 entrées, dont 36 INI officiels et
332 corps TXT à l’octet ; les 262 autres sont des transcriptions PDF identifiées
par page et des fragments documentaires. Le chargement vérifie le SHA-256 et
la limite de taille avant remplacement. L’aperçu est limité à 16 K caractères et 256 lignes,
mais le texte importé reste complet. Les [tests du corpus](EXAMPLES.md) exercent
chaque document dans le moteur et distinguent les diagnostics attendus des
fragments de toute promesse de validation du matériel.

## Fidélité des fichiers

Le document d’origine reste la référence. Les éditions remplacent les plages nécessaires plutôt que de régénérer un INI complet. Cela permet de conserver commentaires, métadonnées `# LABELS`, sections du Forge, blocs désactivés et éléments inconnus. Les données non reconnues sont signalées et conservées pour éviter de perdre un patch d’une autre version.

Le moteur diagnostique des erreurs de syntaxe et de catalogue, ainsi que des limites de taille. Le matériel peut imposer d’autres contraintes : nombre de registres par contrôleur, allocation mémoire, comportement des connexions et ordre d’exécution. Une validation matérielle complète exige de reprendre ces règles du Forge et de tester avec le système DROID.

Toutes les opérations refusent les sources dépassant 4 Mio avant modification, sans altérer l’historique. L’analyse s’arrête à 4 096 blocs, 32 768 affectations, 256 diagnostics, 1 Mio de champs cumulés ou 8 Kio par champ. Les messages sont bornés à 1 024 octets. Les commentaires seuls ne consomment pas le budget des champs. Une analyse partielle est signalée ; le texte intégral et l’export restent disponibles, mais ajout, suppression et édition structurée sont bloqués jusqu’à résolution. L’annulation et le rétablissement restent utilisables.

Les échanges JNI préservent Unicode, emoji, BOM, NUL et fins de ligne. Les paires UTF-16 incomplètes sont refusées au lieu d’être remplacées silencieusement. L’ajout d’une valeur dans un circuit désactivé conserve le caractère de commentaire ; les expressions invalides désactivées produisent des remarques plutôt que des erreurs actives.

## Limites de l'interface et récupération

La limite de fichier de 4 Mio est distincte des budgets d'affichage. L'éditeur
de texte Android accepte au plus 256 K caractères, 4 096 lignes et 16 384
caractères par ligne. Ces caractères sont comptés en unités UTF-16 ; un emoji
peut en occuper deux. Un fichier plus grand reste conservé intégralement et
exportable, avec une invitation à l'éditer dans un outil externe avant de le
réimporter. Cette séparation évite de mettre en page un texte de plusieurs
millions de lignes dans un `TextView`.

Les cartes présentent les paramètres par pages de 32. Le panneau de module
présente les 32 premiers paramètres ; les autres restent dans le source. Les
champs de saisie des paramètres sont bornés à 4 096 caractères. Une valeur
importée plus longue est affichée comme un aperçu, sans réécriture ni
troncature du document. Pour les grands textes, la navigation est simplifiée
et les commentaires affichés sont bornés ; l'export reste fondé sur le texte
intégral du moteur.

Le document appliqué et les brouillons de l'interface sont sauvegardés
séparément, avec écritures atomiques dans le stockage privé. La saisie des
brouillons est limitée préventivement à 1 048 576 caractères cumulés, sans supprimer
les valeurs déjà présentes. Une erreur d'entrée/sortie lors de leur sauvegarde
reste visible. Les rotations et recréations restaurent les saisies et les
dialogues pris en charge. Une édition d'un champ doit préserver les brouillons
des autres champs. Après modification de la structure, les correspondances
de blocs évitent d'attribuer une saisie à un autre circuit de même nom ; les
cas ambigus nécessitent récupération explicite. Les 56 vérifications de cette
correspondance ont été réexécutées avec succès pour 0.4.0.

Avant d'ouvrir le sélecteur d'export, l'application conserve sur disque
l'instantané exact demandé. Cet instantané permet de terminer l'export après
la mort du processus Android pendant le choix du fichier. Les fichiers et
les sauvegardes utilisent un décodage/encodage UTF-8 strict ; les données
mal formées ou dépassant la limite sont refusées avant remplacement. Les
erreurs de sauvegarde ou d'export doivent rester visibles, pour que
l'utilisateur puisse préserver son patch.

Ces protections ont été exercées en 0.2.1 sur les fichiers de 4 Mio, un champ
de 8 193 octets, l'UTF-8 invalide et la mort réelle du processus ; ces preuves
restent dans [VALIDATION.md](VALIDATION.md). La campagne 0.3.0 a réussi
42 contrôles isolés et 3 contrôles de mise à jour normale, décrits
séparément dans [VALIDATION-0.3.0.md](VALIDATION-0.3.0.md).
La campagne 0.4.0 a réussi 61 contrôles isolés et 3 contrôles de mise à jour
normale. Le zoom, le déplacement et un pincement injecté à deux pointeurs ont été
exercés ; le patch de la session normale est conservé à l’octet.
Android API 26, les pages mémoire de 16 Kio à l'exécution, le S Pen physique, le
multifenêtrage, les autres fournisseurs et les sessions prolongées ne sont
pas couverts par ces résultats.

## Reconstruction des sources

Les sources de 0.4.0 ont été fraîchement extraites et compilées en Release
hors ligne, avec un SDK installé et les dépendances déjà en cache.
Les ressources nécessaires au build sont incluses ; le manuel PDF reste
facultatif et extérieur à l'APK. La comparaison des binaires reconstruits
retrouve 977 contenus ZIP identiques ; seul le natif diffère de 20 octets,
dans le descripteur du build ID GNU. Chaque autre octet ELF est identique.
Le projet ne garantit donc pas des APK identiques octet pour octet.
Le script de livraison rassemble les sources correspondantes avec leurs
empreintes et les notices. Le
[rapport de reconstruction de l’archive finale](validation-data/0.4.0/source-build-verification-0.4.0.json)
consigne l’inventaire exact et la comparaison. Voir les mesures et limites dans
[VALIDATION-0.4.0.md](VALIDATION-0.4.0.md).

## Étapes de parité restantes

1. Réordonnancement des modules du rack et des circuits, sélecteurs complets de registres.
2. Analyse mémoire et validation des ressources selon le master et les contrôleurs.
3. Gestion d’une bibliothèque personnelle, création et déplacement des sections, aide contextuelle avec navigation dans le manuel.
4. MIDI Android class compliant pour MASTER18/X7, transfert SysEx et retour d’état.
5. Compression compatible, générateurs et procédure de mise à jour des firmwares.

Les opérations sur le firmware restent absentes de cette version. Leur ajout devra suivre le protocole et les contraintes décrits dans le manuel officiel.
