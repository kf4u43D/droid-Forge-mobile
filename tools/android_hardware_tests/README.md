# Harness Android de validation isolée

Ces sources sont sous [GPL-3.0](../../LICENSE). Elles compilent un APK de test séparé,
`fr.androforge.validation.hardwaretests`, qui cible exclusivement
`fr.androforge.validation`. Elles sont incluses dans l’archive des sources ; aucun
APK de test, keystore ou donnée privée n’est nécessaire dans cette archive.

Le harness vérifie le vrai pont JNI de l’APK optimisé, découvre les interfaces
USB/MIDI en lecture seule et teste `DroidSdStorage` avec un `DocumentsProvider`
appartenant au paquet de test. Les fichiers SAF et les sauvegardes sont limités à
ses UUID privés. Il ne lit ni n’écrit de fichier sur le DROID, n’ouvre pas de port
MIDI ou USB et ne démarre pas d’Activity. Il ne contourne pas le verrouillage de la
tablette. Les actions interactives exigent le déverrouillage normal si Android le
demande.

## Compilation

Prérequis : Python 3.8+, JDK 17, Android SDK Platform 36 et Build Tools 36.0.0.
L’APK cible doit être construit en variante `tabletValidation`, installé et signé
avec la même clé que le harness. Les règles ProGuard du projet conservent
`NativeBridge` ainsi que les API publiques de `DroidSdStorage` et ses classes
internes utilisées par réflexion.

Depuis la racine du projet :

```text
python tools/android_hardware_tests/build_unsigned.py --sdk CHEMIN_SDK --jdk CHEMIN_JDK_17 --output-dir .cache/android_hardware_tests
```

`--sdk` est facultatif si `ANDROID_SDK_ROOT`, `ANDROID_HOME` ou `sdk.dir` dans
`local.properties` désigne le SDK. `--jdk` est facultatif avec `JAVA_HOME`.
Les valeurs explicites sont prioritaires. Le script fonctionne sous Windows et
sous les systèmes où les outils SDK/JDK correspondants sont disponibles. Il
produit `hardwaretests-aligned.apk` sans signature, vérifie l’alignement et retire
uniquement son répertoire temporaire de compilation. Il n’appelle ni Gradle, ni
ADB, ni un outil de signature et ne modifie pas l’APK de l’application.

## Signature de développement et installation

Exemple PowerShell avec la clé debug Android standard, qui doit aussi avoir signé
le paquet validation installé. Pour une autre clé de développement, indiquer sa
clé locale sans l’ajouter au dépôt ni à l’archive des sources.

```powershell
$hwSdk = $env:ANDROID_SDK_ROOT
$hwDebugKey = Join-Path $env:USERPROFILE '.android/debug.keystore'
& "$hwSdk/build-tools/36.0.0/apksigner.bat" sign --ks "$hwDebugKey" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out .cache/android_hardware_tests/hardwaretests.apk .cache/android_hardware_tests/hardwaretests-aligned.apk
& "$hwSdk/build-tools/36.0.0/apksigner.bat" verify --verbose .cache/android_hardware_tests/hardwaretests.apk
adb install -r .cache/android_hardware_tests/hardwaretests.apk
```

Le provider reste protégé par `MANAGE_DOCUMENTS`. Un service auxiliaire du paquet
de test vérifie l’UID exact de validation et la signature commune avant de
préparer ses propres fichiers et d’accorder leurs URI. Les opérations du stockage
utilisent ensuite le resolver validation, avec de vraies permissions SAF.

## Vérifications JNI, découverte et SAF

Sans matériel connecté :

```text
adb shell am instrument -w -e expectX7Msc false fr.androforge.validation.hardwaretests/fr.androforge.validation.hardwaretests.HardwareInstrumentation
```

Pour exiger également la présence d’un X7 connecté en mode carré, omettre
`-e expectX7Msc false`. Cette assertion ne transfère aucun octet au matériel.
Le résultat contient un JSON `report` et `passed=true` uniquement si tous les
contrôles et le nettoyage réussissent.

Les contrôles portent sur le déploiement compact et les limites JNI, la trame
SysEx officielle, les grants et refus SAF, le nom `droid.ini`, la création et la
relecture exacte, la sauvegarde de l’original, les écritures sans changement,
la révocation et une déconnexion simulée. Le nettoyage est automatique dans ce
mode. Le document natif initial du paquet validation est restauré ; son historique
en mémoire ne constitue pas une donnée à préserver pour ce paquet de test.

Ces contrôles ne prouvent pas une activation du MASTER, une écriture sur une carte
DROID réelle ou une éjection matérielle. Le provider de test refuse l’éjection.

## Fixture pour les vrais dialogues DocumentsUI

Créer une fixture persistante sans exécuter les tests JNI ni la découverte :

```text
adb shell am instrument -w -e setupUiFixture true fr.androforge.validation.hardwaretests/fr.androforge.validation.hardwaretests.HardwareInstrumentation
```

Conserver `fixture_uuid` du JSON retourné. Le dossier apparaît dans DocumentsUI
sous le nom **Validation DROID suivi des huit premiers caractères de cet UUID**.
Il contient un `droid.ini` synthétique en CRLF (`copy`, `I1` vers `O1`). Le setup
n’accorde aucune URI automatiquement : choisir ce dossier dans le sélecteur
Android du paquet validation pour tester import, Save to SD et les autorisations
réelles. L’export de l’original peut créer `droid-original.ini` dans ce même dossier.
Le provider accepte seulement ces noms connus, avec `renamed.ini` réservé au test
de faute. Les indicateurs du root autorisent sélection, vérification des enfants
et création ; le provider reste un stockage local privé, sans accès au matériel.

La fixture n’est volontairement pas nettoyée après le setup. Son marqueur privé
permet sa restauration après une mort du processus validation ou du provider.
Le choix normal du dossier et les confirmations de l’application sont nécessaires
pour toute opération interactive ; ce mode n’effectue aucune de ces opérations.

Quand la suite interactive est terminée, demander le nettoyage de l’UUID exact
retourné, puis désinstaller seulement le harness :

```text
adb shell am instrument -w -e cleanupUiFixture UUID_EXACT_RETOURNE fr.androforge.validation.hardwaretests/fr.androforge.validation.hardwaretests.HardwareInstrumentation
adb uninstall fr.androforge.validation.hardwaretests
```

Le nettoyage refuse un UUID invalide ou qui ne désigne pas une fixture. Il révoque
ses grants, supprime exclusivement les fichiers connus et son dossier privé exact,
puis notifie DocumentsUI. Il ne supprime ni l’application principale
`fr.androforge`, ni les sauvegardes utilisateur, ni un dossier de stockage externe.
