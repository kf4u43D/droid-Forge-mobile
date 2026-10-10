# Validation de droid Forge mobile 0.5.0

Rapport de la version **0.5.0**, code **7**. La revue locale et les essais
d’interface sont terminés : **22 scénarios GUI**, **56 contrôles Android
JNI/SAF** et **2 contrôles de nettoyage** ont réussi sur le paquet validation.
La campagne physique a repris après reconnexion du X7 : import et écriture SD
ont été vérifiés à l’octet. L’original a été restauré exactement dans le véritable
`droid.ini` par un harness privé de test, et l’action système ouverte par
**Eject SD** a réellement démonté le volume. Les **24 assertions X7** ont réussi :
19 pour la carte SD, l’import et le nettoyage, puis 5 pour le transport MIDI
Android. Elles sont distinctes des 22 scénarios GUI et 56 contrôles avec
fournisseur privé. L’utilisateur ne constate aucune erreur après Activate ;
cette observation est séparée des 24 vérifications instrumentées. Le
fonctionnement complet du patch reste non certifié.
Les résultats historiques [0.4.0](VALIDATION-0.4.0.md),
[0.3.0](VALIDATION-0.3.0.md) et [0.2.1](VALIDATION.md) restent séparés.

## Périmètre et état des preuves

Cette version ajoute les commandes **Activate**, **Save to SD**, **Eject SD**,
l’import de `droid.ini` depuis la carte et la récupération de ses originaux
sauvegardés. Le texte destiné au DROID est préparé séparément du document de
l’éditeur : ASCII/LF, contrôleurs actifs d’abord, circuits et paramètres actifs,
sans commentaires ni labels. Les sorties SD et MIDI utilisent ce même texte.

La [documentation de connexion](DROID-CONNECTION.md) donne les références
officielles, les positions du X7 et les limites de l’adaptation Android.
L’import de carte récupère un fichier enregistré ; il ne prouve pas que ce
fichier correspond au patch actif en RAM. Le protocole officiel d’activation
ne fournit pas d’accusé de réception du patch.

Les opérations destructrices doivent être confirmées dans l’interface. La
découverte USB et MIDI n’écrit rien. Une seule transaction peut être active,
y compris pendant une recréation de l’activité. Un original de carte remplacé
est conservé dans le stockage privé avant écriture ; sa récupération exige un
texte UTF-8 valide pour l’éditeur, ou utilise un export brut à l’octet.

Les 22 scénarios avec fournisseur privé ont utilisé `fr.androforge.validation`
pour isoler les fixtures. La préversion 0.5.0 de `fr.androforge` a été installée séparément :
les trois fichiers privés suivis avant et après mise à jour sont identiques à
l’octet, et le nom et le source affichés restent exacts. Les empreintes de cette
comparaison sont conservées seulement dans le cache privé de la campagne.
Aucun nom, contenu ou hash de patch utilisateur n’est publié.
La préversion conserve la signature de développement existante ; la signature
de production n’a pas été fournie. La Release reste non signée sans configuration
externe.
Le complément physique utilise cette préversion de l’application principale.
Avant l’import réel, le document utilisateur précédent a été conservé et sa
copie vérifiée exactement en privé ; ses empreintes ne sont pas publiées.

## Tests obtenus

| Vérification réexécutée pour 0.5.0 | Résultat |
| --- | ---: |
| Moteur C++ Release sur Windows | 86 971 contrôles |
| Préparation de déploiement C++ Release | 150 contrôles |
| Préparation de déploiement avec ASan et UBSan combinés | 150 contrôles |
| Pont JNI, `-Xcheck:jni` | 222 contrôles |
| Moteur natif ARM64 sur SM-X620 | 86 340 contrôles |
| Préparation de déploiement ARM64 | 138 contrôles |
| Corpus officiel ARM64 | 630 documents / 59 307 contrôles |
| Transport MIDI et réassemblage SysEx simulés sur JDK | 2 174 contrôles |
| Exclusion des transactions DROID sur JDK | 135 569 contrôles ; 32 threads, 19 200 transactions |
| Politique SD simulée sur JDK | 311 contrôles |
| Compilation Java des classes SD contre le SDK Android 36 | Réussie |
| Adaptateurs JNI/SAF sur Android avec fournisseur privé | 56 contrôles |
| Interface Android avec fournisseur privé et dialogues système | 22 scénarios |
| Nettoyage des fixtures du fournisseur privé | 2 contrôles |
| Outils Python de livraison | 38 tests |
| Build des trois variantes et lint | Réussis ; 158 tâches, dont 44 exécutées et 114 déjà à jour, 40 s |
| Campagne X7 réelle, périmètre SD | 19 assertions réussies |
| Complément X7 MIDI Android | 5 assertions réussies ; total X7 de 24 vérifications instrumentées |
| Observation utilisateur après Activate, hors compteur instrumenté | Aucune erreur constatée ; fonctionnement complet du patch non certifié |

Le nombre de contrôles de déploiement dépend des points d’allocation STL
exercés par la plateforme ; les 138 contrôles ARM64 ne sont pas un échec des
150 contrôles Windows. Les deux suites se sont terminées avec succès.

Lint a terminé avec zéro erreur et deux avertissements par variante,
`OldTargetApi` et `ChromeOsAbiSupport`. Les 56 contrôles des adaptateurs ont
été réexécutés avec succès sur l’APK validation final, avec l’option
`expectX7Msc=false`. Aucun résultat d’un APK intermédiaire ne remplace cette
réexécution.

Le benchmark natif local sur la tablette utilise 55 975 octets, 750 circuits
et 100 itérations : chargement avec JSON à **2,963 ms/opération**,
édition/analyse avec JSON à **2,812 ms/opération**. Il mesure le moteur et son
état JSON, sans mesurer le rendu Android ni le temps d’accès USB ou SD.

## Ce que les simulations vérifient

Les tests C++ de déploiement contrôlent l’ordre des éléments actifs, le retrait
des annotations, la conservation textuelle des nombres, la canonicalisation
des noms, les limites de 64 000 octets et 63 octets par ligne, et les refus
d’analyse partielle, d’éléments actifs inconnus ou de connectivité incomplète.
Les messages d’activation encadrent le texte par `F0 00 66 66 50` et `F7`.

Les tests MIDI utilisent des ports simulés : fragmentation et réassemblage
SysEx, erreurs, délai dépassé, fermeture et concurrence. Ils ne prouvent pas
la réception ni l’exécution par le master.

Les tests SD utilisent un backend simulé. Ils couvrent l’original UTF-8/BOM/CRLF,
les fichiers absents ou vides, l’absence de réécriture si le fichier est déjà
identique, une sauvegarde impossible ou corrompue, un changement concurrent,
une écriture partielle et une relecture absente ou altérée. Ils vérifient les
limites 64 000 octets/4 Mio, les 256 valeurs d’octet et la conservation brute
de données non UTF-8. La sauvegarde et l’original restent distincts du texte
ASCII destiné au DROID.

Les 56 contrôles Android utilisent un fournisseur de documents privé. Ils
exercent les autorisations SAF, le refus après révocation, les fichiers déjà
identiques, la création et les noms de fichiers, l’écriture synchronisée,
la relecture, les sauvegardes et la déconnexion. Ces tests passent par les
adaptateurs Android et JNI ; ils ne constituent pas des écritures sur la
carte physique du X7.

Le [harness Android GPL et son guide de reproduction](../tools/android_hardware_tests/README.md)
compilent un paquet de test séparé qui cible exclusivement l’installation
validation. Le fournisseur protège ses URI et limite les fixtures à son propre
stockage ; le harness ne transfère aucun octet au matériel.

Reproduction des tests JDK depuis la racine du projet, avec un JDK 17 ou plus
récent dans `JAVA_HOME` :

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/test_midi_transport.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File tools/test_droid_operation_gate.ps1
javac -encoding UTF-8 -d .cache/sd-io-test app/src/main/java/fr/androforge/DroidSdIo.java tools/DroidSdIoTest.java
java -ea -cp .cache/sd-io-test fr.androforge.DroidSdIoTest
```

## Scénarios d’interface et binaires finaux

Les 22 scénarios ont exercé les commandes visibles et le refus d’activation
sans X7 MIDI. Le sélecteur Android a réellement accordé un dossier du
fournisseur privé, puis l’interface a demandé la confirmation avant d’écrire
et de relire **33 octets**. L’absence de changement n’a pas créé une seconde
sauvegarde.

L’import a conservé le document précédent et son brouillon `I2`, puis leur
récupération exacte a été vérifiée après rotation et arrêt du processus.
L’original de fixture de **81 octets en CRLF** a été récupéré à l’octet et
exporté brut ; cet export a repris après une mort réelle du processus pendant
le dialogue `ACTION_CREATE_DOCUMENT`.

Les parcours de sélection de dossier pour SAVE et READ ont également été
vérifiés après redémarrage : confirmation requise avant SAVE et annulation
préservant le document. Le retour du parcours d’éjection DocumentsUI n’a
produit aucune annonce d’éjection réussie. Le fournisseur privé ne simule pas
une carte physique éjectable.

| Variante finale | Utilisation | Octets | SHA-256 |
| --- | --- | ---: | --- |
| `tabletPreview` | Mise à jour normale ; session préservée | 9 235 113 | `8f66211ff06492839a9894eaa60515564aa9a6553e087c133a1de0d97235599d` |
| `tabletValidation` | 22 scénarios GUI et 56 contrôles JNI/SAF | 9 235 121 | `df3d4ee9c24a319aa77887ebd23a7f28fdf6b92e5b329b7766258647ccf7067f` |

Les contrôles statiques de [la préversion](validation-data/0.5.0/preview-apk-verification-0.5.0.json)
et de [la validation](validation-data/0.5.0/validation-apk-verification-0.5.0.json) ont réussi :
natif de 1 975 448 octets, segments LOAD alignés sur 16 384 octets, fin de
RELRO à 1 998 848 et payload natif du ZIP à 114 688 octets. La pile est non
exécutable et les relocations utilisent `BIND_NOW`. Ces contrôles statiques
ne sont pas un test du runtime à pages de 16 Kio.
La [vérification des exemples embarqués](validation-data/0.5.0/example-assets-verification-0.5.0.json)
confirme les 630 textes et les trois notices de corpus dans la préversion.

Les rapports publics de la campagne avec fournisseur privé sont le
[rapport d’interface tablette](validation-data/0.5.0/tablet-audit-0.5.0.json) et le
[rapport des adaptateurs Android](validation-data/0.5.0/android-adapters-audit-0.5.0.json).
Les fixtures sont synthétiques ; ils ne contiennent pas le patch utilisateur.
Ils décrivent l’état matériel à leur propre clôture et restent inchangés.

## X7 réel : opérations obtenues et étapes restantes

Le X7, absent lors de la clôture des suites isolées, a été reconnecté en position
carré/gauche. Le stockage de masse USB classe 8, VID `0483`, PID `a416`, est
détecté et la carte DROID a été montée pour les opérations suivantes, avant
l’éjection finale. La racine était accessible dans le sélecteur
SAF Samsung malgré l’absence de chemin FUSE visible aux applications.
L’application ne contourne pas les droits SAF par un shell privilégié.

Les résultats suivants sont confirmés sur l’APK principal **0.5.0-preview**,
code **7**, référencé dans le tableau des binaires. L’APK extrait de l’installation
est identique à l’octet à la préversion de `dist` : 9 235 113 octets, SHA-256
`8f66211ff06492839a9894eaa60515564aa9a6553e087c133a1de0d97235599d`.

| Opération physique | Résultat confirmé |
| --- | --- |
| Lecture SAF du fichier de carte | Deux lectures identiques de 74 269 octets |
| Annulation de la confirmation d’import | Document courant conservé exactement |
| Import accepté dans l’éditeur | Texte de 74 269 octets exact, autosauvegarde et redémarrage à froid exacts |
| Analyse C++ du fichier importé | 454 circuits, 4 contrôleurs, 2 726 paramètres, 63 labels ; zéro erreur, une remarque sur la taille du source brut |
| Préparation du déploiement lors de la phase SD | 49 163 octets ; trame SysEx de 49 169 octets préparée sans envoi pendant cette phase |
| Absence de mutation par cette préparation | Source, état et historique inchangés |
| Save to SD confirmé | 49 163 octets relus exactement, puis comparaison indépendante avec le résultat C++ |
| Sauvegarde privée de l’original de carte | 74 269 octets conservés exactement avant remplacement |
| Seconde commande Save to SD sans changement | Contenu exact, aucune sauvegarde supplémentaire ; inventaire privé inchangé |
| Export brut de l’original | 74 269 octets vérifiés dans un fichier distinct créé par le sélecteur |
| Restauration du véritable `droid.ini`, par harness privé | 74 269 octets exacts après trois contrôles préalables, synchronisation du descripteur et trois relectures |
| Vérification indépendante après restauration | Deux lectures SD et copie hôte identiques à l’original d’avant campagne |
| Nettoyage de la copie créée par l’export UI | Seule cette copie supprimée ; autres identités d’entrées et préférences inchangées |
| Eject SD par l’action système | DocumentsUI ouvert depuis l’application, icône DROID ciblée ; volume DROID démonté, carte SD de la tablette toujours montée |

Le source brut dépasse 64 000 octets, tandis que sa copie destinée au DROID
respecte cette limite après retrait des annotations. La remarque de taille
du source n’a donc pas empêché le déploiement compact. Ces mesures ne publient
ni contenu, ni nom, ni empreinte de patch utilisateur.

L’export brut n’a pas restauré le `droid.ini` existant : il a créé un autre
fichier. La restauration ultérieure a utilisé un **harness privé de test**,
avec les autorisations SAF normales, après vérification que le fichier présent
correspondait au déploiement attendu de 49 163 octets. Ce harness n’est pas une
commande de restauration dans l’application. Les autres entrées de la carte
ont été comparées par leur identité ; leur contenu n’a pas été comparé à l’octet.

Après l’éjection SD, l’utilisateur a confirmé le passage en mode MIDI.
Android a reconnu **DROID X7 MIDI**, avec une entrée et une sortie USB.
La confirmation Activate ciblait ce X7 et indiquait **49 163 / 64 000 octets**.
Son annulation n’a envoyé aucun octet. Après une seconde confirmation acceptée,
le dialogue **Transfert MIDI envoyé** a indiqué la fin de la remise de la trame
de **49 169 octets** au port Android, avec la limite d’absence d’accusé de
réception explicitement affichée.
Le source affiché est identique à l’octet avant et après transfert. Le port
de transfert est fermé, le compteur de connexions MIDI du périphérique est
revenu à zéro et Activate reste disponible. Aucun crash de l’application
principale n’a été observé dans le buffer de crash disponible.

L’utilisateur rapporte **« pas d’erreur »** après Activate. Cette observation
complète les 24 vérifications instrumentées et reste consignée séparément.
Le fonctionnement complet du patch demeure non certifié. La version du firmware
matériel n’est pas lue par ce protocole ; la valeur d’un descripteur USB ne
permet pas de la déduire. Une lecture du fichier enregistré ne prouve pas non
plus l’identité du patch actif en RAM.

Le [rapport physique X7](validation-data/0.5.0/droid-hardware-audit-0.5.0.json) consigne
24 assertions réussies : 19 SD et 5 MIDI Android, avec l’observation utilisateur
d’absence d’erreur séparée. Le fonctionnement complet du patch reste non certifié.
Ces assertions ne s’additionnent pas aux compteurs des suites historiques
avec fournisseur privé.
Ouvrir le sélecteur ou les réglages Android
n’équivaut pas à une éjection ; remettre des octets au port MIDI Android
n’équivaut pas à un accusé de réception du master.

## Limites conservées

L’utilisateur ne constate aucune erreur après Activate ; le fonctionnement
complet du patch reste non certifié. La remise des octets au port Android
ne fournit aucun ACK matériel.
La restauration effectuée est un contrôle privé de la campagne,
pas une fonctionnalité de l’interface. Les autres
fournisseurs SAF, Android API 26, le runtime à pages de 16 Kio, le S Pen physique et une
session prolongée restent à vérifier. Une relecture exacte ne certifie pas
une transaction atomique du fournisseur ni la résistance de la carte à une
coupure électrique. Le budget privé de 128 sauvegardes/32 Mio refuse un nouvel
overwrite avant écriture ; aucune suppression automatique n’est effectuée.
Les copies du document avant import sont également limitées à 128 documents /
64 Mio ; atteindre ce budget bloque le remplacement du document.

## Reconstruction des sources

La reconstruction depuis l’archive finale fait l’objet d’un contrôle de livraison
distinct. La preuve de livraison locale
`dist/source-build-verification-0.5.0.json`, exclue du dépôt Git, consigne le
résultat de la comparaison des contenus de l’APK et du binaire natif.
Tout changement de ces documents nécessite une nouvelle archive et une nouvelle
vérification du snapshot correspondant avant livraison.
Le SHA-256 de l’archive de sources n’est pas incorporé à ses propres documents.
Aucune preuve de la version 0.4.0 ne remplace un contrôle final du binaire 0.5.0.
