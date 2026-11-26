<div align="center">

<img src="fastlane/metadata/android/en-US/images/icon.png" width=160 height=160 align="center">

# Seal Stems

### Télécharge un son, puis sépare la voix du beat — directement sur ton téléphone

</div>

**Seal Stems** est une version de [Seal](https://github.com/JunkFood02/Seal) (le téléchargeur audio/vidéo basé sur [yt-dlp](https://github.com/yt-dlp/yt-dlp)) à laquelle j'ai ajouté un **séparateur de pistes par IA** : à partir de n'importe quel morceau, l'appli crée deux fichiers, **Voix** et **Instrumental**.

- 🎤 **Voix / Instrumental** : idéal pour le karaoké, les remixes, les samples ou pour bosser une instru.
- 📴 **100 % sur le téléphone** : rien n'est envoyé sur Internet. Le modèle d'IA (67 Mo) est téléchargé une seule fois au premier usage.
- 📂 **Tous les fichiers** : MP3, M4A, Opus, WebM, MP4, WAV… depuis l'historique de téléchargement (bouton **Séparer**) ou n'importe quel fichier du téléphone (menu **Séparer les pistes**).
- 🎧 **Réglé pour la séparation** : par défaut l'appli télécharge l'audio seul, en meilleure qualité et sans réencodage.
- 💾 Les pistes sont enregistrées en WAV dans `Musique/Seal Stems/<titre>/`.
- 🆓 Gratuit, sans pub, open source (GPL-3.0).

## Télécharger

Les APK sont dans les [Releases](https://github.com/maythayus/SealStems/releases).

## Comment ça marche

Le séparateur utilise le modèle **UVR-MDX-NET-Inst_HQ_3** de l'équipe [Ultimate Vocal Remover](https://github.com/Anjok07/ultimatevocalremovergui) (licence MIT), exécuté avec [ONNX Runtime](https://onnxruntime.ai/). Le morceau est découpé en blocs d'environ 6 secondes, chaque bloc passe en spectrogramme (STFT), le modèle prédit l'instrumental, et la voix est obtenue par soustraction. Le code est dans `app/src/main/java/com/junkfood/seal/stems/`.

## Crédits

- **[JunkFood02](https://github.com/JunkFood02) et tous les contributeurs de [Seal](https://github.com/JunkFood02/Seal)** : toute la partie téléchargement et l'interface viennent de leur travail. L'historique Git de Seal est conservé dans ce dépôt. README d'origine : [README-Seal.md](README-Seal.md).
- [yt-dlp](https://github.com/yt-dlp/yt-dlp) et [youtubedl-android](https://github.com/yausername/youtubedl-android).
- L'équipe [Ultimate Vocal Remover](https://github.com/Anjok07/ultimatevocalremovergui) (Anjok07, aufr33) pour le modèle de séparation.
- [ONNX Runtime](https://github.com/microsoft/onnxruntime) et [JTransforms](https://github.com/wendykierp/JTransforms).

## Licence

[GPL-3.0](LICENSE), comme Seal.

> Télécharge uniquement des contenus que tu as le droit de télécharger.
