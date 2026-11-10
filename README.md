# Rando

Appli Android de randonnée avec une météo précise le long du parcours.

## Fonctions (v0.2)

- **Recherche autour de toi** (🔍) : à partir de ta position ou d'une adresse, avec un rayon, ouvre les résultats Komoot, AllTrails et Visorando dans le navigateur intégré ; « Importer cette rando » sur la fiche choisie.
- **AllTrails** : import via le navigateur intégré (lecture du tracé dans la page, ou GPX pour les comptes AllTrails+).
- **Y aller** : lance le trajet en voiture jusqu'au point de départ (Google Maps / Waze…).
- **Sac à préparer** : eau, nourriture, vêtements, matériel et sécurité calculés selon la durée, le dénivelé, l'altitude et la météo prévue (options chien / enfant porté), liste à cocher.
- **Import direct** d'une rando Komoot ou Visorando : coller le lien, ou « Partager → Rando » depuis l'appli Komoot / Visorando.
- **Navigateur intégré** pour les randos privées ou quand le site demande une connexion : le GPX téléchargé est intercepté et importé.
- **Import GPX** classique (fichier, ou « Ouvrir avec Rando »).
- **Carte topo** (OpenTopoMap), profil altimétrique, distance, D+/D-, durée estimée (méthode DIN 33466, 4 allures).
- **Météo le long du parcours** : pour chaque point de contrôle (départ, sommet, tous les 1 à 2 km, arrivée), prévision à l'heure où tu y passes et à l'altitude réelle :
  - modèle Météo-France AROME 1,3 km / ARPEGE (via Open-Meteo), complété par les modèles globaux au-delà ;
  - température et ressenti, pluie et probabilité, vent et rafales, nébulosité, visibilité, isotherme 0 °C, UV ;
  - alertes : orages (codes orage + instabilité CAPE), rafales sur les crêtes, gel/verglas, neige, brouillard, chaleur, arrivée après le coucher du soleil ;
  - suggestion du meilleur créneau de départ de la journée.
- Mode sombre / clair.

## Installation

Chaque push sur `main` compile l'APK via GitHub Actions et le publie dans **Releases**.
Télécharge `Rando-v0.1.X.apk` depuis le téléphone et installe-le. Toutes les versions sont signées avec la même clé : les mises à jour s'installent par-dessus.
