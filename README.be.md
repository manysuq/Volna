[English](README.md) · [Русский](README.ru.md) · [Українська](README.uk.md) · [Беларуская](README.be.md) · [Français](README.fr.md) · [Español](README.es.md) · [中文](README.zh.md) · [العربية](README.ar.md)

<p align="center">
  <img src="docs/icon.png" width="180" alt="Volna">
</p>

# Volna

<p align="center">
  <a href="https://github.com/manysuq/Volna/releases/tag/v1.0.1"><img alt="⬇ Спампаваць" src="https://img.shields.io/badge/v1.0.1-⬇%20Спампаваць-2ea44f?style=for-the-badge"></a>
</p>


**VIBECODED**

Спакойны музычны прайгравач для Android. Шукае трекі ў **YouTube Music** і
 стрымуе аудыя напраму — без спампоўвання файла, без рэкламы і без уліковага
запісу.

## Скріншоты

<table>
  <tr>
  <td width="50%" valign="top">
    <img src="docs/screenshots/01-similar-light.jpg" alt="01-similar" width="100%">
    <p align="center"><sub>Падобныя трэкі</sub></p>
  </td>
  <td width="50%" valign="top">
    <img src="docs/screenshots/02-search-dark.jpg" alt="02-search" width="100%">
    <p align="center"><sub>Пошук, цёмная</sub></p>
  </td>
  </tr>
  <tr>
  <td width="50%" valign="top">
    <img src="docs/screenshots/03-player-dark.jpg" alt="03-player" width="100%">
    <p align="center"><sub>Зараз гульвае</sub></p>
  </td>
  <td width="50%" valign="top">
    <img src="docs/screenshots/04-artist-dark.jpg" alt="04-artist" width="100%">
    <p align="center"><sub>Выканаўца</sub></p>
  </td>
  </tr>
  <tr>
  <td width="50%" valign="top">
    <img src="docs/screenshots/05-search-empty-dark.jpg" alt="05-empty" width="100%">
    <p align="center"><sub>Пуста</sub></p>
  </td>
  </tr>
</table>

## Што ўмее

- **Пошук у YouTube Music** — у афіцыйных трэках выканавец і альбом ужо ў
  падпісе, таму ў выдачы не мяшаюць гадзіныя міксы і стрымы.
- **Запасны пошук па відэа** — калі трэка няма ў каталогу YouTube Music,
  праграма сказа пра гэта і знойдзе яго ў звычайным YouTube, а не ўключыць
  нешта іншае маўчама.
- **Прамы стрым** — ExoPlayer чытае аўдыяпоток па HTTP з дакачваннем, нічога
  не спампоўваючы цалкам.
- **Перападключэнне** — спасылка YouTube прывязаная да IP і старэе, таму пры
  403 праграма сама бярэ яе нанова.
- **Чарга** — наступныя трэкі падцягваюцца падчас гульні, пераключэнне не
  чакае сетку.
- **Альбомы і выканаўцы** — каталог з адкрытага API iTunes.
- **Падобныя трэкі** — радыё на рэкамендацыях YouTube Music, а не звычайнага
  YouTube.
- **Медыятэка** — «падабаецца» і ўласныя плэйлісты з перастаноўкай, усё
  захоўваецца на прыладзе.
- **Слухаць усё па выканаўцы** — адзін перамешаны набор з усіх яго альбомаў.
- **Афлайн** — спампоўванне ў агульную тэчку `Music/Volna`, граецца без сеткі.
- **Цёмная і светлая тэма**, Material 3 Expressive.
- **Восем моваў** — англійская, руская, украінская, беларуская, французская,
  іспанская, кітайская, арабская.

## Як гэта працуе

Пошук ідзе праз унутраны API InnerTube — той самы, што ў афіцыйнай праграме,
але без ключоў і капчы:

```
POST https://music.youtube.com/youtubei/v1/search   кліент WEB_REMIX
```

YouTube Music пазначае кожны элемент тыпам (`Song`, `Video`, `Album`,
`Playlist`, `Podcast`) і аддае `videoId` проста ў `playlistItemData`. Таму
афіцыйныя трэкі аддзяляюцца ад астатку адной праверкай тыпу — у звычайным
пошуку YouTube такой разметкі няма, і туды трапляюць кліпы і рэміксы.

Падбор трэка аддае перавагу выканаўцу, а не назве. Без гэтага кліп фандата
«Атлантыда [КЛІП]» перайграў бы сапраўную «Атлантыду»: назва супадае ідэальна,
а выканавец — не. Пераробкі (`slowed`, `reverb`, `remix`, `cover`, `nightcore`)
адсекаюцца, калі рэмікс не запытаны па імені.

Самы аўдыяпоток аддаецца толькі звычайным кліентам InnerTube, таму атрыманне
спасылкі ідзе асобна — на `www.youtube.com`, кліентамі `ANDROID`, `ANDROID_VR`
і `IOS` па чарзе.

## Зборка

Патрэбныя JDK 17 і Android SDK (compileSdk 35).

```bash
./gradlew :app:assembleDebug          # APK для адладкі
./gradlew :app:assembleRelease        # рэлізны APK
./gradlew :app:testDebugUnitTest      # модульныя тэсты
```

Кожная зборка рэлізу капіруецца ў `dist/`, таму зборкі не заціраюць адна адна:

```
dist/volna-1.0.1-release.apk             звычайная зборка, com.volna.player
dist/volna-1.0.1-FOR_ISLAND-<пакет>.apk варыянт для дынамічнага астравка, гл. ніжэй
```

Не выкарыстоўвай `app/build/outputs/apk/release`: гэта службовая тэчка, якую
кожная зборка перапісвае, таму файл, які там ляжыць, можа быць не тым
варыянтам, які ты збіраўся ўсталяваць.

### Падпіс рэлізу

Рэлізны ключ ляжыць побач з праектам, але не трапляе ў git — замест яго
паказвае `keystore.properties`:

```properties
storeFile=volna-release.jks
storePassword=<пароль сховішча>
keyAlias=volna
keyPassword=<пароль ключа>
```

Абодва файлы ў `.gitignore`. Стварыць свой ключ:

```bash
keytool -genkeypair -v -keystore volna-release.jks \
  -keyalg RSA -keysize 4096 -validity 10000 -alias volna
```

Калі `keystore.properties` няма, зборка не падаець, а застаецца непадпісанай —
так яе можна запусціць на чыстай машыне, не маючы ключа.

Падпіс уключае схемы v1, v2 і v3: v2 пакрывае Android 7+, v3 патрэбны для
абнаўлення паверх ужо ўсталяванага на Android 11+.

> **Ключ у гэтай зборцы тэставы, пароль `volna-release` вядомы па змаўчанні.**
> Замяні яго сваім і трымай у тайне: страчаны ключ не дасцца выпусціць
> абнаўленне пад той жа асобай, а скампраметаваны дазволіць камусь падпісваць
> зборкі ад твайго імені.

## Структура

```
app/                      праграма (Compose, Material 3)
  search/                 InnerTube: YouTubeMusicSearch + запасны YouTubeSearch
  stream/                 атрыманне і праверка спасылкі на аўдыя
  player/                 MediaSessionService на ExoPlayer
  catalog/                альбомы і выканаўцы праз iTunes
  library/                «падабаецца» і плэйлісты
  download/               афлайн-спампоўванні
ytdl/                     бібліятэка атрымання патоку (чыстая Java, без залежнасцей)
design/                   варыянты іконкі
```

Пакеты: `com.volna.player` — праграма, `com.ytdl.core` — бібліятэка.

## Тэсты

Парсер адказу InnerTube пакрыты модульнымі тэстамі на сапраўдных адказах API —
фікстуры ляжаць у `app/src/test/resources`. Гэта патрэбна, бо YouTube
перыядычна змяняе разметку выдачы, і без іх змена вёрсткі выглядала б проста
як «пошук перастаў знаходзіць».

## Варыянт FOR_ISLAND

Некаторыя абалонкі тэлефонаў (vivo/OriginOS і іншыя кітайскія) паказваюць
разгарнуты плаваючы прайгравач толькі для праграм са спісу, запісанага ў кодзе.
Калі твой тэлефон з такіх, дапамагае зборка з іншым імем пакета:

```bash
./gradlew assembleRelease -PforIslandPackage=com.tencent.qqmusic
```

Змяняецца **толькі `applicationId`** — імя пакета на прыладзе. Код,
`namespace` і ўсё іншае застаецца ранейшым, таму звычайная зборка не
закранутая.

Што варта памятаць:

- Гэта кастыль пад пэўныя абалонкі, а не ўніверсальнае рашэнне.
- Такі APK займае чужое імя пакета, таму ўсталяваць яго разам з арыгіналам
  нельга — прыйдзецца выдаліць арыгінал.
- Метка `FOR_ISLAND` і прычына трапляюць у `versionName`, каб варыянт было
  відна на спіску пакетаў.
- Асноўны пакет — `com.volna.player`, і ён не змяняецца.

## Застерега

Праграма не спампоўвае музыку: яна стрымуе аўдыяпоток, як любой сецевы
прайгравач. Файлы запісваюцца толькі па яўнай пстрычцы кнопкі «Спампоўваць».
Выкарыстанне — на ўласны рыск, згодна з
[умовамі YouTube](https://www.youtube.com/t/terms).

## Ліцэнзія

MIT — гл. [LICENSE](LICENSE).
