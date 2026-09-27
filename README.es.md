[English](README.md) · [Русский](README.ru.md) · [Українська](README.uk.md) · [Беларуская](README.be.md) · [Français](README.fr.md) · [Español](README.es.md) · [中文](README.zh.md) · [العربية](README.ar.md)

<p align="center">
  <img src="docs/icon.png" width="180" alt="Volna">
</p>

# Volna

<p align="center">
  <a href="https://github.com/manysuq/Volna/releases/tag/v1.0.2"><img alt="⬇ Descargar" src="https://img.shields.io/badge/v1.0.2-⬇%20Descargar-2ea44f?style=for-the-badge"></a>
</p>


**VIBECODED**

Un reproductor de música tranquilo para Android. Busca en **YouTube Music** y
reproduce el audio en directo: sin descargar el archivo entero, sin anuncios y
sin cuenta.

## Capturas de pantalla

<table>
  <tr>
  <td width="50%" valign="top">
    <img src="docs/screenshots/01-similar-light.jpg" alt="01-similar" width="100%">
    <p align="center"><sub>Canciones similares</sub></p>
  </td>
  <td width="50%" valign="top">
    <img src="docs/screenshots/02-search-dark.jpg" alt="02-search" width="100%">
    <p align="center"><sub>Búsqueda, oscuro</sub></p>
  </td>
  </tr>
  <tr>
  <td width="50%" valign="top">
    <img src="docs/screenshots/03-player-dark.jpg" alt="03-player" width="100%">
    <p align="center"><sub>Sonando</sub></p>
  </td>
  <td width="50%" valign="top">
    <img src="docs/screenshots/04-artist-dark.jpg" alt="04-artist" width="100%">
    <p align="center"><sub>Artista</sub></p>
  </td>
  </tr>
  <tr>
  <td width="50%" valign="top">
    <img src="docs/screenshots/05-search-empty-dark.jpg" alt="05-empty" width="100%">
    <p align="center"><sub>Vacío</sub></p>
  </td>
  </tr>
</table>

## Qué hace

- **Búsqueda en YouTube Music**: las pistas oficiales llevan artista y álbum en el
  subtítulo, así que los mixes de una hora y las transmisiones no se cuelan.
- **Búsqueda en vídeo como alternativa**: si la pista no está en el catálogo de
  YouTube Music, la app lo avisa y la busca en YouTube normal, en lugar de
  reproducir otra cosa en silencio.
- **Reproducción directa**: ExoPlayer lee el flujo de audio por HTTP con
  reanudación, sin descargar nada entero.
- **Reconexión automática**: un enlace de YouTube está atado a tu IP y caduca, así
  que ante un 403 la app consigue uno nuevo por su cuenta.
- **Cola**: las pistas siguientes se resuelven mientras escuchas.
- **Álbumes y artistas**: catálogo de la API pública de iTunes.
- **Pistas similares**: radio basada en las recomendaciones de YouTube Music.
- **Tu biblioteca**: favoritos y tus propias listas, reordenables, guardados en
  el dispositivo.
- **Escuchar todo de un artista**: un conjunto barajado de todos sus álbumes.
- **Sin conexión**: descarga a la carpeta compartida `Music/Volna`.
- **Temas claro y oscuro**, Material 3 Expressive.
- **Ocho idiomas**: inglés, ruso, ucraniano, bielorruso, francés, español, chino,
  árabe.

## Cómo funciona

La búsqueda usa la API interna InnerTube: la misma que la app oficial, sin claves
ni captcha:

```
POST https://music.youtube.com/youtubei/v1/search   client WEB_REMIX
```

YouTube Music etiqueta cada resultado con un tipo (`Song`, `Video`, `Album`,
`Playlist`, `Podcast`) y devuelve el `videoId` dentro de `playlistItemData`. Esa
única comprobación de tipo separa las pistas oficiales del resto: la búsqueda de
YouTube normal no tiene ese marcado, por eso se colan clips y remixes.

El selector de la mejor coincidencia da más peso al artista que al título. Sin
eso, una subida «Atlantida [CLIP]» ganaría a la pista real: el título coincide
perfectamente y el artista no. Las versiones (`slowed`, `reverb`, `remix`,
`cover`, `nightcore`) se descartan salvo que pidas ese remix por su nombre.

El flujo de audio solo se sirve a los clientes InnerTube habituales, así que la
resolución del enlace va aparte, en `www.youtube.com`, con los clientes
`ANDROID`, `ANDROID_VR` e `IOS` por turnos.

## Compilación

Necesitas JDK 17 y el SDK de Android (compileSdk 35).

```bash
./gradlew :app:assembleDebug          # APK de depuración
./gradlew :app:assembleRelease        # APK de release
./gradlew :app:testDebugUnitTest      # tests unitarios
```

Cada build de release se copia a `dist/`, así que las compilaciones no se pisan:

```
dist/volna-1.0.2-release.apk             build normal, com.volna.player
dist/volna-1.0.2-FOR_ISLAND-<pkg>.apk    variante de isla dinámica, más abajo
```

No uses `app/build/outputs/apk/release`: es una carpeta de trabajo que cada build
sobrescribe, así que el archivo que haya puede ser otra variante.

### Firma de la release

La clave está junto al proyecto pero no entra en git; la apunta
`keystore.properties`:

```properties
storeFile=volna-release.jks
storePassword=<contraseña del almacén>
keyAlias=volna
keyPassword=<contraseña de la clave>
```

Ambos archivos están en `.gitignore`. Para crear tu propia clave:

```bash
keytool -genkeypair -v -keystore volna-release.jks \
  -keyalg RSA -keysize 4096 -validity 10000 -alias volna
```

Sin `keystore.properties` la compilación funciona igual y produce un APK sin
firmar.

La firma activa v1, v2 y v3: v2 cubre Android 7+, v3 permite actualizar sobre una
instalación existente en Android 11+.

> **La clave de este repositorio es de prueba y su contraseña es pública.**
> Cámbiala por la tuya y guárdala en secreto: perder la clave impide publicar una
> actualización con la misma identidad, y filtrarla permite a otro firmar como tú.

## Estructura

```
app/                      la app (Compose, Material 3)
  search/                 InnerTube: YouTubeMusicSearch + alternativa YouTubeSearch
  stream/                 resolución y verificación del enlace de audio
  player/                 MediaSessionService sobre ExoPlayer
  catalog/                álbumes y artistas vía iTunes
  library/                favoritos y listas
  download/               descargas sin conexión
ytdl/                     biblioteca de resolución (Java puro, sin dependencias)
design/                   variantes del icono
```

Paquetes: `com.volna.player` para la app, `com.ytdl.core` para la biblioteca.

## Tests

El analizador de respuestas de InnerTube tiene tests unitarios ejecutados contra
respuestas reales de la API: las fixtures están en `app/src/test/resources`.
Importa porque YouTube cambia el marcado cada cierto tiempo y, sin ellas, un
cambio de maquetación parecería simplemente que «la búsqueda ya no encuentra
nada».

## La variante FOR_ISLAND

Algunas capas del sistema (vivo/OriginOS y otros ROM chinos) solo muestran su
reproductor flotante expandido para paquetes de una lista fija. Si tu teléfono es
de esos, compilar con otro nombre de paquete lo arregla:

```bash
./gradlew assembleRelease -PforIslandPackage=com.tencent.qqmusic
```

Solo cambia el `applicationId`, el nombre de paquete en el dispositivo. El
código, el `namespace` y todo lo demás siguen igual, así que la build normal no
se ve afectada.

Ten en cuenta:

- Es un apaño para capas concretas, no una solución universal.
- Ese APK ocupa el nombre de paquete de otro, así que no se puede instalar junto
  al original: habrá que quitar el original.
- La etiqueta `FOR_ISLAND` y el motivo aparecen en `versionName`.
- El paquete normal es `com.volna.player` y nunca cambia.

## Aviso

La app no descarga música: reproduce el flujo de audio, como cualquier
reproductor de red. Los archivos solo se escriben cuando pulsas Descargar
explícitamente. Úsala bajo tu propia responsabilidad, conforme a los
[Términos del servicio de YouTube](https://www.youtube.com/t/terms).

## Licencia

MIT — ver [LICENSE](LICENSE).
