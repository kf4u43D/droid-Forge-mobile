# Validation 0.5.2 — pictogramme Android

La version 0.5.2, code 9, utilise le pictogramme carré noir et blanc fourni
par l’utilisateur. Le PNG de 465 × 465 pixels, de 8 758 octets, est conservé
à l’octet près ; son origine et son empreinte figurent dans le
[manifeste de l’icône](../resources/official/brand-logo-manifest.json).
Le cadrage adaptatif conserve son ratio sur fond noir. Les quatre marges
de 29,166667 % donnent une dimension nominale de 45 × 45 dp sur une couche
de 108 dp, avec une réserve pour les arrondis aux pixels d’Android.

Les variantes de préversion et de livraison compilent avec Android Lint.
Le contrôle des APK confirme ARM64, l’alignement 16 Kio, les protections ELF
et les notices. La signature de la préversion utilise le certificat de
développement existant ; l’APK de livraison est non signé. Les ressources
des 630 exemples embarqués ont été vérifiées.

Sur Galaxy Tab S10 FE+ / Android 16, la mise à jour puis l’ouverture depuis
la nouvelle icône ont conservé le texte du patch à l’octet près. L’APK
installé a été relu et comparé au fichier local : 9 241 305 octets,
SHA-256 `b6e32923e41a46f100243323fd39814affd2eecb6e5c48906b51071f60df4688`.
Le rendu a été vérifié visuellement dans les informations de l’application
et le lanceur Samsung ; toucher l’icône ouvre l’application principale.
Les captures et relevés temporaires de cette campagne ont été retirés
de la tablette, et l’éditeur laissé au premier plan.
Le [rapport de la campagne](validation-data/0.5.2/icon-audit-0.5.2.json)
ne contient ni contenu, ni nom, ni empreinte du patch utilisateur.

Le moteur natif est identique à l’octet à ceux des préversions 0.5.0 et
0.5.1. Les preuves de transfert SD/MIDI et l’observation du MASTER restent
celles de la [campagne 0.5.0](VALIDATION-0.5.0.md). Aucun nouveau transfert
DROID n’a été effectué pour cette modification. La
[validation 0.5.1](VALIDATION-0.5.1.md) reste conservée séparément.
Les autres formes de masque de lanceur n’ont pas été testées sur appareil ;
le dessin tient dans la zone sûre documentée de l’icône adaptative.
Aucun dessin monochrome spécifique aux icônes thématiques n’est fourni.
