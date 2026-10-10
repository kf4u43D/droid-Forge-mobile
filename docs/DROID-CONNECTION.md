# Connexion DROID — 0.5.0

Ce document décrit le protocole officiel et l’adaptation Android 0.5.0.
La revue locale et les tests des adaptateurs et de l’interface sont terminés.
Après reconnexion du X7 en mode carré, l’import de carte et la sauvegarde SD
ont été vérifiés à l’octet sur le matériel réel. L’original a été restauré
exactement par un harness privé de test, puis l’action DocumentsUI ouverte
par **Eject SD** a réellement démonté le volume DROID. Le transfert Android
MIDI vers le X7 a ensuite réussi. L’utilisateur ne constate aucune erreur après
Activate ; le fonctionnement complet du patch reste non certifié.
Le [rapport physique X7](validation-data/0.5.0/droid-hardware-audit-0.5.0.json) contient
24 assertions réussies : 19 pour la carte SD, l’import et le nettoyage,
puis 5 pour le transport MIDI Android. Elles restent distinctes des essais
avec fournisseur privé.
Les [preuves de 0.5.0](VALIDATION-0.5.0.md) distinguent ces deux périmètres.
La [validation 0.4.0](VALIDATION-0.4.0.md) reste un rapport historique séparé.

## Positions du X7 et nature du patch récupéré

Le manuel blue-7, chapitre 8, pages 89–91, donne trois positions :

| Position | Fonction USB | Conséquence |
| --- | --- | --- |
| Gauche, mode carré observé sur le X7 connecté | Stockage de masse, accès à la carte SD | Le master ne joue pas son patch pendant cet accès |
| Milieu | USB déconnecté | Après éjection par l’hôte, le master recharge automatiquement `droid.ini` |
| Droite | USB MIDI | Envoi et réception MIDI, activation d’un patch par SysEx |

Le [fabricant décrit l’accès SD comme celui d’une clé USB](https://shop.dermannmitdermaschine.de/products/x7),
y compris depuis un téléphone ou une tablette. Le X7 agit comme périphérique USB ;
Android doit jouer le rôle d’hôte. Le manuel p. 90 précise aussi les contraintes
de révision et de firmware pour un câble USB-C vers USB-C ; l’application ne
met pas à jour le firmware.

Lire `droid.ini` récupère **le fichier enregistré sur la carte**, sans conversion
de son texte. Ce fichier peut différer d’un patch précédemment activé en RAM par
MIDI. Ni le manuel blue-7 ni le transport MIDI du Forge PC de référence ne
documentent une commande de récupération du patch actif en RAM. Aucun message
de demande ni accusé de réception de patch n’est inventé.

Références : [manuel officiel blue-7](https://dermannmitdermaschine.de/download/droid-manual-blue-7.pdf),
copie locale `resources/official/droid-manual-blue-7.pdf`, pages 89–91 et 95–97.

## Activate, Save to SD et Eject SD dans Forge PC

La référence PC est le commit
`60cbb7bbf9630c7d4a184e346a3d1120bde4d69f` :

- `droidforge/main/patchoperator.cpp:420` : `saveToSD()` contrôle les problèmes
  du patch, prépare `toDeployString()`, écrit `droid.ini`, puis demande l’éjection.
- `droidforge/main/tuning.h:18` : `DROID_PATCH_FILENAME` vaut `droid.ini` ;
  `MAX_DROID_INI`, ligne 36, limite la charge utile à 64 000 octets.
- `droidforge/patch/patch.cpp:1338` : le déploiement assemble les contrôleurs puis
  les circuits. Les annotations de l’éditeur ne sont pas le texte déployé.
- `droidforge/patch/patch.cpp:1375`, notamment 1405–1408 : après écriture, Forge
  relit le contenu et le compare à celui demandé.
- `droidforge/main/patchoperator.cpp:494` : l’éjection utilise les fonctions de
  l’OS, notamment `diskutil` sur Mac et `udisksctl` sur Linux.
- `droidforge/os/midihost.cpp:6` : l’activation MIDI utilise le même texte,
  précédé de `F0 00 66 66 50` et suivi de `F7`. `50` est le caractère ASCII `P`.

Le manuel p. 95–97 documente l’envoi de ce SysEx avec le switch à droite. Le
contournement Mac des octets isolés dans Forge PC n’est pas une règle à appliquer
automatiquement au transport Android.

Le moteur Android prépare séparément un texte ASCII/LF : contrôleurs actifs
d’abord, circuits et paramètres actifs dans leur ordre, sans commentaires,
labels ni annotations. Il conserve la précision textuelle des nombres et refuse
les erreurs, l’analyse partielle, les paramètres actifs inconnus et les budgets
dépassés. Les options PC de déduplication, noms courts et compression des câbles
ne sont pas reprises dans cette première adaptation. Le source éditable reste
distinct et conserve ses métadonnées.

## Accès SD Android avec SAF

Le contrôleur UI demande un dossier par `ACTION_OPEN_DOCUMENT_TREE` et conserve
seulement l’autorisation accordée par Android. Choisir un dossier n’écrit rien.
La [documentation SAF](https://developer.android.com/guide/topics/providers/document-provider)
décrit ces accès et les racines temporaires de stockage USB.

`DroidSdStorage` opère sur le worker d’entrée/sortie :

1. `read(tree)` cherche un unique fichier `droid.ini` dans le dossier choisi et
   rend ses octets bruts, avec une limite de 4 Mio. Le décodage UTF-8 strict est
   une étape séparée avant remplacement du patch dans l’éditeur.
2. `save(tree, deployed)` refuse une charge vide, non ASCII ou supérieure à
   64 000 octets. Un fichier déjà identique n’est pas réécrit.
3. Avant remplacement, l’original est sauvegardé dans le stockage privé de
   l’application, puis relu à l’octet. Un changement concurrent de l’original
   interrompt l’opération avant écriture.
4. L’écriture ferme et synchronise le fichier, puis vérifie sa relecture exacte.
   Un échec peut laisser un fichier partiel sur la carte ; il reste explicitement
   signalé et la sauvegarde privée disponible. Aucune restauration ou éjection
   automatique n’est effectuée après cet échec.
5. `listBackups()` et `readBackup(record)` permettent la récupération des
   originaux privés. Les chemins sont limités au dossier de sauvegarde et la
   lecture à 4 Mio. Ces méthodes rendent des octets ; une source non UTF-8 doit
   pouvoir être conservée par export brut, sans conversion silencieuse.

Les sauvegardes ne sont pas supprimées automatiquement. Leur budget de 128
fichiers et 32 Mio provoque un refus visible **avant** un nouvel overwrite. La
vérification de relecture ne transforme pas SAF en transaction atomique du
fournisseur et ne certifie pas la résistance de la carte à une coupure électrique.

Le X7 connecté a été identifié par le pilote de tests comme un périphérique MSC
classe 8, VID `0483`, PID `a416`, sans périphérique MIDI dans ce mode. Le volume
monté par Samsung n’avait pas de chemin FUSE visible aux applications.
Ce constat ne suffit pas à conclure que SAF sera absent : le
[fournisseur AOSP](https://android.googlesource.com/platform/frameworks/base/+/master/packages/ExternalStorageProvider/src/com/android/externalstorage/ExternalStorageProvider.java)
peut utiliser un chemin interne si le volume est monté pour le bon utilisateur.
Après reconnexion, la racine DROID a réellement été choisie et autorisée dans
le sélecteur Samsung. Deux lectures SAF du fichier de carte ont donné les
mêmes 74 269 octets ; l’import accepté, son autosauvegarde et le redémarrage
à froid ont conservé ce texte exactement. L’annulation de la confirmation
d’import a également conservé le document courant.
L’application ne contourne pas ces droits en accédant directement à
`/mnt/media_rw` ou en utilisant un shell privilégié.

La préparation C++ a produit un déploiement de 49 163 octets, sans mutation du
source, de l’état ou de l’historique. Save to SD l’a écrit puis relu exactement
sur la carte ; une lecture instrumentée indépendante a confirmé l’égalité
avec le résultat C++. L’original de 74 269 octets a été sauvegardé exactement
dans le stockage privé avant remplacement. Une seconde sauvegarde sans
changement n’a ni réécrit le fichier ni créé un second original privé.

L’export brut de l’original a vérifié ses 74 269 octets dans un **fichier distinct**
créé par le sélecteur. Il n’a pas remplacé le `droid.ini` existant. La remise de
l’original dans ce fichier a ensuite été réalisée par un **harness privé de
test**, avec des autorisations SAF normales : trois contrôles préalables du
déploiement présent, écriture de l’original, synchronisation et trois relectures
exactes. Deux lectures SD indépendantes et une copie hôte ont confirmé les
74 269 octets d’avant campagne. Cette restauration n’est pas une commande de
l’application. Le nettoyage a supprimé seulement le fichier distinct créé
par l’export ; les autres identités d’entrées et les préférences sont inchangées,
sans comparaison à l’octet du contenu des autres fichiers de carte.
Le contenu, le nom du fichier distinct et les empreintes privées ne sont pas publiés.

## Éjection réelle

[`DocumentsContract.ejectRoot()`](https://developer.android.com/reference/android/provider/DocumentsContract#ejectRoot(android.content.ContentResolver,%20android.net.Uri))
est une API publique depuis Android 26, applicable aux racines portant
`FLAG_SUPPORTS_EJECT`. Elle peut échouer. Le
[contrôle AOSP du fournisseur](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/provider/DocumentsProvider.java)
exige une autorisation d’écriture sur l’URI de racine `/root/...` ; une permission
sur l’URI de dossier `/tree/...` ne l’accorde pas automatiquement.

`canEject(tree)` vérifie une racine identifiée, le droit spécifique et sa capacité.
Si l’application ne peut pas l’éjecter, un repli public consiste à ouvrir
le sélecteur système par `ACTION_OPEN_DOCUMENT_TREE`. Dans la liste des
stockages, l’utilisateur touche l’icône d’éjection à côté de DROID, attend sa
disparition ou la confirmation d’Android, puis revient sans choisir un dossier.
`EXTRA_INITIAL_URI` peut suggérer le dossier DROID déjà autorisé ; cette
[position initiale reste dépendante du système](https://developer.android.com/reference/android/provider/DocumentsContract#EXTRA_INITIAL_URI).
Le résultat de ce parcours d’éjection est ignoré : il ne déclenche ni
import, ni écriture, ni conservation d’une nouvelle autorisation.

Ce parcours est fondé sur le
[code DocumentsUI Android 16](https://android.googlesource.com/platform/packages/apps/DocumentsUI/+/refs/tags/android-security-16.0.0_r1/src/com/android/documentsui/sidebar/RootItem.java) :
la barre latérale affiche une icône pour les racines déclarant l’éjection,
indépendamment de l’action de sélection. Le
[worker système](https://android.googlesource.com/platform/packages/apps/DocumentsUI/+/refs/tags/android-security-16.0.0_r1/src/com/android/documentsui/sidebar/EjectRootTask.java)
appelle l’API d’éjection sous les droits de DocumentsUI. Ce parcours a réussi
sur la tablette Samsung : **Eject SD** a ouvert DocumentsUI, l’icône DROID
a été ciblée et le volume DROID est passé à l’état `unmounted`. La carte SD
de la tablette est restée montée. Une application ordinaire n’obtient pas
les droits de DocumentsUI en ouvrant le sélecteur. Les réglages de stockage et la notification
USB restent des replis secondaires.

Ouvrir un écran, annuler le sélecteur ou retirer une autorisation SAF
**n’est pas une éjection**. Aucun résultat d’intent ne garantit qu’un volume a
été démonté. Une demande API acceptée n’est pas non plus une preuve de fin
d’un démontage asynchrone.

Après une éjection réelle par Android, remettre le switch du X7 au milieu
déconnecte l’USB et lance le fichier de la carte, selon le manuel p. 91.
`DROIDERR.TXT` permet ensuite de consulter une éventuelle erreur du master.
Aucun fichier firmware n’est écrit par ces commandes.

## Transfert MIDI réel et observation du MASTER

Après l’éjection SD, l’utilisateur a confirmé le passage du X7 en mode MIDI.
Android a détecté **DROID X7 MIDI**, avec une entrée et une sortie USB.
L’application a demandé la confirmation pour cette cible et ses
**49 163 / 64 000 octets** de déploiement. Annuler n’a envoyé aucun octet.
Après une nouvelle confirmation acceptée, **Transfert MIDI envoyé** a signalé
que les **49 169 octets** de la trame avaient été remis au port Android.
Le dialogue précisait l’absence d’accusé de réception dans le protocole officiel.
Le source de l’éditeur est resté identique à l’octet après transfert ; Android
indique le port fermé et aucune connexion MIDI active pour ce périphérique.
Activate reste disponible. Aucun crash de l’application principale n’a été
observé dans le buffer de crash disponible.

Ce résultat vérifie la fin du transport Android. L’utilisateur rapporte
**« pas d’erreur »** après Activate ; cette observation est consignée séparément
des 24 vérifications instrumentées. Le fonctionnement complet du patch reste
non certifié. Aucun ACK n’est déduit de l’envoi ou de cette observation.
La version firmware n’est pas lue par ce protocole ; un descripteur USB ne
fournit pas cette preuve.

## Tests obtenus et limites matérielles

Les classes SAF se compilent avec `android-36.jar`. La politique de transaction
a passé **311 contrôles JDK** : conservation UTF-8/BOM/CRLF de l’original,
sauvegarde corrompue ou impossible, conflit concurrent, déconnexion pendant
l’écriture, relecture manquante/altérée, absence d’overwrite sur un fichier
identique, limites 64 000 octets/4 Mio et validation des 256 valeurs d’octet.
Ces tests utilisent un backend simulé ; ils ne constituent pas des écritures sur
la carte réelle.

**56 contrôles Android JNI/SAF** ont également réussi avec un fournisseur de
documents privé : autorisations et révocation, fichier déjà identique,
création/noms, écriture synchronisée, relecture, sauvegardes et déconnexion.
Ils ont été réexécutés sur l’APK validation final, sans écriture sur le X7.
**22 scénarios GUI** ont réussi avec le sélecteur Android et ce fournisseur :
choix et autorisation du dossier, confirmation SAVE, écriture/relecture,
import et sauvegarde du document précédent, récupération après rotation et
arrêt du processus, absence de seconde sauvegarde sur un fichier identique,
export brut exact de l’original après interruption du sélecteur, annulations
et absence de fausse confirmation d’éjection. Les deux contrôles de nettoyage
de la fixture ont également réussi.

Le [harness GPL reproductible](../tools/android_hardware_tests/README.md)
documente les autorisations et l’isolation des fixtures. Le
[rapport de validation 0.5.0](VALIDATION-0.5.0.md) identifie les APK finaux,
le [rapport tablette](validation-data/0.5.0/tablet-audit-0.5.0.json) détaille l’interface et
le [rapport des adaptateurs](validation-data/0.5.0/android-adapters-audit-0.5.0.json) les
contrôles JNI/SAF. Aucune de ces suites ne revendique une opération sur la
carte physique ou une activation MIDI du master.

```powershell
javac -encoding UTF-8 -d .cache/sd-io-test app/src/main/java/fr/androforge/DroidSdIo.java tools/DroidSdIoTest.java
java -ea -cp .cache/sd-io-test fr.androforge.DroidSdIoTest
```

Le complément physique a vérifié la lecture/importation de carte, l’écriture
confirmée et relue, la sauvegarde exacte de l’original et son export brut
distinct, puis une restauration par harness privé et l’éjection système réelle.
L’APK principal installé a été comparé à l’octet avec l’APK preview publié dans
`dist`, dont le SHA-256 est indiqué dans la validation. Le transport Android
MIDI a ensuite réussi, puis l’utilisateur n’a constaté aucune erreur. Le
[rapport physique X7](validation-data/0.5.0/droid-hardware-audit-0.5.0.json) consigne
24 assertions réussies : 19 SD et 5 MIDI Android. L’observation utilisateur
d’absence d’erreur reste séparée ; le fonctionnement complet du patch n’est
pas certifié.
Les preuves historiques 22 GUI/56 adaptateurs restent
inchangées, et leurs compteurs ne sont pas additionnés à ces assertions physiques.
Aucun envoi MIDI ou fonctionnement du master n’est
déduit d’une sauvegarde SD ou de la préparation d’une trame SysEx.
