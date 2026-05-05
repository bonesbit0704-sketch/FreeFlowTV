# Как опубликовать на GitHub

## Вариант через сайт

1. Откройте GitHub и создайте репозиторий `FreeFlowTV`.
2. Загрузите содержимое этой папки в репозиторий.
3. В описании репозитория укажите:

```text
Android TV IPTV player with M3U playlists, demo channels, EPG, favorites, categories and stream diagnostics.
```

4. Включите темы репозитория:

```text
android-tv, iptv, m3u, kotlin, exoplayer, media3, smart-tv
```

5. Создайте релиз `v1.0.8`.
6. В релиз прикрепите файл:

```text
release/FreeFlowTV-1.0.8.apk
```

## Вариант через git

```powershell
git init
git branch -M main
git add .
git commit -m "Publish FreeFlowTV 1.0.8"
git remote add origin https://github.com/<owner>/FreeFlowTV.git
git push -u origin main
```

После пуша создайте релиз `v1.0.8` и прикрепите APK.
