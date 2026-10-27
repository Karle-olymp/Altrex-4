# PRoot dans Altrex

Le terminal Linux d'Altrex lance un Alpine Linux sans root grâce à **PRoot**.

| Composant | Licence | Source | Utilisation |
|---|---|---|---|
| PRoot (`libproot.so`, `libproot-loader.so`) | GPL-2.0 | https://github.com/termux/proot (commit `b27d81a68d765f3462e807f4a14e01a03f8e89ea`) + `tools/proot/proot-android.patch` | Programme séparé, exécuté par l'appli |
| talloc 2.4.2 | LGPL-3.0 | https://www.samba.org/ftp/talloc/talloc-2.4.2.tar.gz | Lié statiquement dans PRoot |
| Alpine Linux minirootfs 3.24.3 (aarch64) | Licences de chaque paquet (MIT, GPL…) | https://alpinelinux.org | Téléchargé au premier usage, empreinte SHA-256 vérifiée |

Altrex reste sous Apache-2.0. PRoot n'est pas lié au code d'Altrex : c'est un programme distinct, placé dans l'APK et lancé comme un processus. Son code source est disponible à l'adresse ci-dessus. Pour reconstruire exactement les binaires livrés, il suffit d'avoir le NDK Android :

```
NDK=/chemin/vers/ndk tools/proot/build-proot.sh
```

## Pourquoi ces deux fichiers sont dans `jniLibs`

Une appli qui cible Android 10 ou plus (API 29+) ne peut pas exécuter un fichier qu'elle a elle-même écrit. Le seul endroit exécutable est le dossier des bibliothèques natives de l'APK, extraites à l'installation (`useLegacyPackaging = true`). PRoot y est donc livré sous le nom `libproot.so`, et son chargeur sous le nom `libproot-loader.so`.

Le chargeur ne lance pas les programmes Alpine comme des fichiers exécutables : il les charge en mémoire. Les fichiers d'Alpine peuvent ainsi rester dans les données de l'appli.
