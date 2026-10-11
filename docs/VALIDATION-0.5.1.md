# Validation 0.5.1 — icône Android

La version 0.5.1, code 8, remplace l’icône « F » par le logo de
Der Mann mit der Maschine. Le [PNG officiel](https://dermannmitdermaschine.de/images/Logo.png)
est conservé à l’octet près ; son origine et son empreinte sont dans le
[manifeste du logo 0.5.1](../resources/official/brand-logo-manifest-0.5.1.json).
Les couches de l’icône adaptative placent le logo entier sur fond noir,
sans déformer son ratio ni couper son texte.

Les variantes de préversion et de livraison compilent avec Android Lint.
Le contrôle des APK confirme ARM64, l’alignement 16 Kio et les protections ELF.
La signature de la préversion utilise le certificat de développement existant ;
l’APK de livraison est non signé. Les 630 exemples embarqués et leurs notices
correspondent au corpus officiel.

Sur Galaxy Tab S10 FE+ / Android 16, la mise à jour a conservé le texte du patch
à l’octet près. L’APK installé correspond au fichier local. L’icône a été
vérifiée dans les informations de l’application et le lanceur Samsung ;
l’ouverture depuis cette icône conserve le document. Les fichiers temporaires
de capture ont été retirés et l’éditeur laissé au premier plan.
Le [rapport de cette campagne](validation-data/0.5.1/icon-audit-0.5.1.json)
ne contient ni contenu, ni nom, ni empreinte du patch utilisateur.

Le moteur natif est identique à l’octet à celui de la préversion 0.5.0.
Les preuves de transfert SD/MIDI et l’observation du MASTER restent celles
de la [campagne 0.5.0](VALIDATION-0.5.0.md). Aucun nouveau transfert DROID n’a
été effectué pour cette modification. Aucun dessin monochrome spécifique
aux icônes thématiques n’est fourni.
