# Commandes du Forge PC et du port Android

Comparaison avec Forge 1.7.1, firmware blue-7, à partir de
`upstream/droidforge/droidforge/main/mainwindow.cpp` au commit
`60cbb7bbf9630c7d4a184e346a3d1120bde4d69f`.

Ce tableau distingue les commandes accessibles dans le port et les fonctions
du PC encore absentes. Les boutons de la barre mobile déclenchent les opérations
qu'ils annoncent.

| Domaine | Présent dans le port | Encore absent |
| --- | --- | --- |
| Fichiers | Nouveau patch, import et export `.ini`, édition du texte source, sauvegarde privée, catalogue des 630 exemples officiels | Bibliothèque complète du PC, options supplémentaires de compression, générateurs Python, intégrer un autre patch, exporter une sélection/section, patches récents et fenêtres multiples |
| Circuits et paramètres | Ajouter/supprimer des circuits, ajouter des paramètres, cellules arithmétiques des entrées, commentaires de circuits, édition brute, aide du catalogue, paramètres initiaux, annuler/rétablir | Couper/copier/coller, dupliquer et déplacer un circuit, activer/désactiver un bloc, trier et étendre les paramètres, supprimer une ligne de paramètre par menu |
| Câbles | Commencer/terminer/annuler une connexion, choisir/créer/renommer/suivre, fusion confirmée, effacer une cellule | Réécriture en masse des noms de câbles |
| Organisation | Navigation par section, pliage des circuits, conservation des commentaires et séparateurs importés, recherche du catalogue | Édition structurée des sections, signets, commentaires de paramètres, recherche dans le patch, suivre un registre/circuit homonyme/même paramètre |
| Rack | Façades officielles, détails des contrôleurs, labels, MASTER16/18 reconnus, G8/X7 déduits des paramètres selon le PC | Duplication/déplacement des modules, choix structuré du type de MASTER |
| Affichage | Adaptation portrait/paysage et écrans étroits, zoom du rack de 50 à 400 %, déplacement du rack | Zoom des circuits et toutes les options d'affichage du PC, miroir, affichage forcé d'un nombre minimum de G8/X7 |
| DROID | Diagnostics locaux, préparation du texte DROID, Activate! MIDI/SysEx via X7, Save to SD avec sauvegarde et relecture, import de droid.ini, récupération des originaux, Eject SD via Android | Validation/allocation complètes et analyse RAM, suivi temps réel, mise à jour et lecture du firmware matériel, lecture du patch actif en RAM |
| Informations | Version installée, firmware du catalogue pris en charge, crédits et licences | — |
| Aide | Informations du catalogue et exemples officiels | Lecteur/liens du manuel complet ou ciblé circuit/module, vidéos et Discord |

Les dix actions de la barre d’outils originale ont maintenant un accès mobile :
Nouveau, Ouvrir, Sauver, Circuit, Paramètre, Module, Problèmes, **Activate!**,
**Save to SD** et **Eject SD**. L’éjection dépend des droits Android : si
l’application ne peut pas la demander directement, elle ouvre DocumentsUI
pour utiliser l’icône d’éjection du volume. Le retour du sélecteur ne confirme
pas que la carte a été éjectée.

Le comportement diffère aussi : **Nouveau** recrée un patch MASTER18 vide,
alors que le PC conserve son rack ; **Sauver** ouvre l’export Android, sans
réécrire directement le fichier précédemment importé. **Save to SD** écrit
une copie préparée pour le DROID ; le document de l’éditeur garde ses annotations.

Voir [la connexion DROID](DROID-CONNECTION.md) et [la validation 0.5.0](VALIDATION-0.5.0.md)
pour les modes du X7 et les essais effectués. Une analyse locale réussie ne
démontre pas que le patch fonctionne sur un système DROID réel.
