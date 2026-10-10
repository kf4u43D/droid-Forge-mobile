# droid Forge mobile

Éditeur Android de patches DROID, basé sur le code source de DROID Forge de **Mathias Kettner**, aka **Der Mann mit der Maschine**. Port Android : **Dr Delos**. Moteur C++17 indépendant de Qt, interface Android native et fichiers `.ini` préservés lors de l’import. La version **0.5.0** ajoute **Activate!**, **Save to SD**, **Eject SD**, l’import du fichier `droid.ini` de la carte et la récupération des originaux. Elle conserve les commentaires de circuits, les labels, le zoom du rack et **630 exemples officiels** du manuel blue-7 et de son archive.

Dépôt du port Android : [kf4u43D/droid-Forge-mobile](https://github.com/kf4u43D/droid-Forge-mobile). Les sources GPL correspondantes sont également livrées en archive avec l’APK.

Le [workflow GitHub](.github/workflows/ci.yml) lance les tests du moteur C++, du corpus officiel et des outils Python, puis compile un APK Android non signé avec Android Lint et contrôle ses ressources et son alignement. Il ne publie aucun artefact ; son premier lancement sur GitHub reste à effectuer. Les [rapports de validation](docs/validation-data/) sont inclus dans le dépôt ; caches, clés, APK et patches privés en sont exclus.

La base stable est **Forge 1.7.1 / firmware blue-7**. Le catalogue comprend 76 circuits et 2 594 paramètres après expansion des groupes numérotés. Les sources de référence sont épinglées au commit `60cbb7bbf9630c7d4a184e346a3d1120bde4d69f` de [Zarkuun/droidforge](https://github.com/Zarkuun/droidforge).

La [validation 0.5.0](docs/VALIDATION-0.5.0.md) distingue les essais du moteur, ceux des adaptateurs Android et la campagne X7 réelle. Les tests du moteur et des 630 exemples passent sur Galaxy Tab S10 FE+. Les [24 assertions X7](docs/validation-data/0.5.0/droid-hardware-audit-0.5.0.json) ont réussi : 19 pour la carte SD, l’import et le nettoyage, puis 5 pour le transport MIDI Android. L’import de 74 269 octets est exact après redémarrage ; **Save to SD** a écrit et relu 49 163 octets. L’original a été restauré par un harness privé de test, puis réellement éjecté par DocumentsUI ouvert depuis **Eject SD**. La restauration par ce harness n’est pas une commande de l’application. **Activate!** a remis la trame de 49 169 octets au port MIDI du X7, avec source préservé et port libéré. L’utilisateur ne constate aucune erreur après Activate ; cette observation est séparée des vérifications instrumentées. Le protocole n’envoie aucun accusé de réception et le fonctionnement complet du patch reste non certifié. Les validations [0.4.0](docs/VALIDATION-0.4.0.md), [0.3.0](docs/VALIDATION-0.3.0.md) et [0.2.1](docs/VALIDATION.md) restent conservées.

Les artefacts préparés localement sont `dist/droid-Forge-mobile-0.5.0-arm64-preview.apk` et `dist/droid-Forge-mobile-0.5.0-source.zip`. Le dossier `dist/` est exclu du dépôt Git. Le point d’entrée pour une publication est la [page des releases GitHub](https://github.com/kf4u43D/droid-Forge-mobile/releases). La préversion utilise la signature de développement existante ; l’APK `release-unsigned` doit recevoir la signature de production avant publication. La [documentation DROID](docs/DROID-CONNECTION.md) explique les modes du X7, les accès Android et les limites du protocole.

## Utilisation

- Ouvrir le catalogue avec la commande **Circuit**, rechercher un circuit et l’ajouter au patch.
- Toucher la valeur, le multiplicateur ou le décalage d’une entrée, puis valider l’expression avec sa coche ; une sortie possède une seule valeur. L’éditeur de source reste accessible dans le menu du fichier.
- Ouvrir le menu d’une prise par appui long ou avec son bouton : commencer, terminer ou annuler une connexion, choisir, créer, renommer ou suivre un câble.
- Choisir les paramètres initiaux d’un circuit : tous, typiques, essentiels ou aucun. Les valeurs absentes du catalogue restent à compléter, comme dans Forge PC.
- Choisir une section à gauche ; toucher la façade d’un contrôleur dans le rack pour ouvrir ses détails.
- Agrandir les modules avec les boutons du rack ou un pincement, puis déplacer le rack. Le bouton de pourcentage revient à 100 %.
- Modifier un label par le bouton **Labels**, le menu du module ou un appui long sur sa prise. Le raccourci et la description utilisent le format du Forge PC.
- Modifier un commentaire de circuit dans son menu **⋮**. Fermer conserve le brouillon ; **Appliquer** modifie le document, avec annulation possible.
- Ouvrir les **Exemples du manuel…** depuis le menu du fichier. L’aperçu indique la provenance et le contexte ; le remplacement du patch exige une confirmation.
- Ouvrir et exporter un fichier `.ini` avec le sélecteur de documents Android, y compris un lecteur de carte accessible à Android.
- Utiliser **Activate!** avec le X7 à droite pour envoyer le patch appliqué en MIDI ; vérifier ensuite le MASTER, le protocole ne fournissant pas d’accusé de réception.
- Utiliser **Save to SD** en mode carré, choisir la racine de la carte DROID et confirmer le remplacement. L’original est sauvegardé avant écriture et le résultat relu à l’octet. **Eject SD** utilise Android ; attendre son éjection avant de passer le X7 au milieu.
- Importer le `droid.ini` enregistré avec le menu **Importer depuis la carte DROID…**. Une copie du document précédent et de ses brouillons est conservée. Ce fichier peut différer du patch en RAM.
- Retrouver les originaux dans **Sauvegardes DROID…** ; leur export brut conserve aussi le BOM, les fins de ligne et les octets non UTF-8. Le sélecteur crée un fichier distinct ; cet export ne remplace pas automatiquement le `droid.ini` existant.
- Annuler/rétablir les modifications ; le dernier document est sauvegardé dans le stockage privé de l’application.

Les diagnostics couvrent la syntaxe et le catalogue. Ils ne remplacent pas la validation sur le matériel DROID. Les commentaires, les lignes inconnues, les sections et les fins de ligne importées restent dans le document. L’export du document conserve ses annotations. Le déploiement crée une copie ASCII/LF sans commentaires ni labels, limitée à 64 000 octets et 63 octets par ligne ; les options de compression supplémentaires du Forge PC restent à porter.

## Compilation Android

Ouvrir ce dossier dans Android Studio. Prérequis : JDK 17, SDK Android 36, Build Tools 36.0.0, NDK 28.2.13676358 et CMake 3.22.1. Renseigner le SDK dans `local.properties` si nécessaire.

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug
.\gradlew.bat :app:assembleTabletPreview :app:lintTabletPreview
.\gradlew.bat :app:assembleTabletValidation :app:lintTabletValidation
```

L’APK est produit dans `app/build/outputs/apk/debug/app-debug.apk`. La cible est ARM64, avec Android 8.0 minimum déclaré ; cette version ancienne n'a pas été testée sur appareil. L’interface est conçue pour la Galaxy Tab S10 FE+ et adapte sa disposition à la largeur disponible. Portrait et paysage ont été testés ; le multifenêtrage et le S Pen physique restent à vérifier. Aucune permission générale de stockage ni connexion Internet n’est demandée par l’application.

Pour les essais sur tablette, `tabletPreview` produit un APK optimisé avec ThinLTO et R8 dans `app/build/outputs/apk/tabletPreview/`. Cette variante est signée avec la clé de développement locale. La variante `release` utilise une configuration de signature externe ; sans celle-ci, les APK/AAB sont explicitement non signés et servent à la revue.

`tabletValidation` utilise le même code optimisé sous l’identifiant `fr.androforge.validation`, dans un stockage privé séparé. Cette installation permet de vérifier des patches d’essai et les scénarios de mort du processus. L’identifiant de l’application principale reste `fr.androforge` afin de mettre à jour les installations existantes avec la même clé.

La distribution prévue est un **APK accompagné des sources GPL**. Le script `powershell -NoProfile -ExecutionPolicy Bypass -File tools/build_release.ps1 -Offline -SkipBundle` prépare l'APK, vérifie le binaire et rassemble les sources correspondantes, notices, symboles et empreintes. Le mode hors ligne nécessite le SDK et les dépendances déjà installés ou en cache. Omettre `-SkipBundle` prépare aussi l'AAB facultatif. `ExecutionPolicy Bypass` ne concerne que le processus lancé. Voir [l’audit de livraison](docs/audit-release.md) pour la signature et les conditions de publication.

## Vérification du moteur

Avec CMake et un compilateur C++17 :

```powershell
cmake -S . -B build-host -A x64
cmake --build build-host --config Release
ctest --test-dir build-host -C Release --output-on-failure
```

Avec Ninja ou un autre générateur, omettre `-A x64` et adapter `--config`.

Le pont Java/C++ peut également être testé sous Windows/JDK avec `powershell -NoProfile -ExecutionPolicy Bypass -File tools/test_jni_host.ps1`, sous contrôle JNI strict. Les tests natifs peuvent être exécutés sur un appareil ARM64 connecté avec `tools/test_core_android.ps1 -DeviceSerial <numéro-adb>` ; ils ne modifient aucun patch. L’alignement et les protections du binaire sont vérifiables avec `python tools/verify_apk.py chemin/vers/application.apk` ; `tools/verify_aab.py` inspecte statiquement l’AAB.

## Ressources reproductibles

Les ressources nécessaires à la compilation sont déjà présentes. Leur origine et leurs empreintes figurent dans `resources/official/manifest.json`.

```powershell
python tools/import_official.py --verify
python tools/generate_catalogue.py
python tools/import_official_examples.py --verify
```

Pour réimporter les ressources officielles : `python tools/import_official.py`. Pour récupérer le Forge PC de référence : `powershell -NoProfile -ExecutionPolicy Bypass -File tools/fetch_upstream.ps1`. Le manuel officiel est conservé hors APK dans `resources/official/` et exclu de l'archive des sources. Sur une extraction de cette archive, réimporter les ressources avant la vérification complète ci-dessus si le manuel est absent ; la compilation n'en dépend pas.

## État du port

Cette version couvre l’édition de circuits et de source, les câbles nommés, les commentaires de circuits, les labels, l’import/export et la sauvegarde locale. Le rack reconnaît MASTER16/18 et déduit G8/X7 des paramètres selon le Forge PC. Le déploiement MIDI/SysEx, la sauvegarde SD avec contrôle et l’import du fichier enregistré sont implémentés. La bibliothèque complète du PC, les générateurs Python, la validation complète des ressources du MASTER, le suivi temps réel et la mise à jour des firmwares restent à porter. L’interface Qt du PC n’est pas compilée dans l’APK.

La taille maximale d’un patch est de 4 Mio. Si les limites d’analyse sont atteintes, le texte intégral reste conservé et exportable ; les éditions structurées sont suspendues pour éviter de modifier un modèle incomplet. Les limites d’affichage et d’édition des très grands documents sont décrites dans [les choix techniques](docs/PORTING.md).

Voir [les ressources et le matériel cible](docs/RESOURCES.md), [les choix techniques](docs/PORTING.md) et [la validation 0.5.0](docs/VALIDATION-0.5.0.md).

La [comparaison avec l’interface du Forge PC en 0.4.0](docs/INTERFACE.md) reste conservée. Le [tableau des commandes](docs/MENUS.md), actualisé pour 0.5.0, décrit les fonctions présentes et celles encore à porter.

## Licence

GPL-3.0, voir [LICENSE](LICENSE). Les circuits et graphismes proviennent de Der Mann mit der Maschine / Mathias Kettner. L’autorisation spécifique des graphismes figure dans [GRAPHICS-LICENSE.txt](resources/official/GRAPHICS-LICENSE.txt). Conserver les notices et fournir le code source correspondant lors d’une redistribution de l’application.
