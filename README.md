# Rando

Appli Android de randonnée avec une météo précise le long du parcours.

## Fonctions (v0.1)

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
