# Force 144 Hz — Geode para Android

Este proyecto intenta pedir 144 Hz al sistema Android desde un mod de Geode.

## Qué hace

1. Lee los modos que Android expone a la aplicación.
2. Solicita la frecuencia configurada mediante `Surface.setFrameRate`.
3. Si existe un modo cercano a esa frecuencia, también fija su `preferredDisplayModeId`.
4. Muestra un diagnóstico indicando el máximo que Android expone a Geode.

## Limitación importante

El launcher oficial de Geode ya intenta seleccionar el modo de mayor frecuencia. Además, en el launcher actual, `Cocos2dxRenderer.setAnimationInterval()` está vacío porque no controla los FPS de Geometry Dash. Por eso este proyecto no es un “FPS bypass” clásico: refuerza la solicitud al sistema y permite saber si Android está ocultando el modo de 144 Hz.

Si el diagnóstico dice que el máximo expuesto es 120 Hz, el mod no puede crear un modo de pantalla que el firmware no ofrece a la aplicación. En ese caso revisa:

- Frecuencia de pantalla configurada en 144 Hz o modo Alto.
- Ahorro de batería desactivado.
- Ajuste de frecuencia por aplicación, si tu sistema lo incluye.
- Temperatura del dispositivo.
- La superposición “Mostrar frecuencia de actualización” en Opciones de desarrollador.

## Compilar completamente desde un teléfono Android

La vía recomendada es GitHub Actions. El equipo de Geode cerró como “not planned” la solicitud de compilar mods en ARM64 directamente dentro de Termux, debido a problemas con las herramientas de generación de código.

### Pasos

1. Desde el navegador del teléfono, crea un repositorio vacío en GitHub.
2. Extrae este ZIP.
3. Sube todo su contenido al repositorio, conservando las carpetas ocultas como `.github`.
4. Abre la pestaña **Actions**.
5. Selecciona **Build Android64** y pulsa **Run workflow**.
6. Al terminar, abre la ejecución y descarga el artefacto `force-144hz-android64`.
7. Extrae el archivo `.geode`.
8. Cópialo a:

   `/storage/emulated/0/Android/media/com.geode.launcher/game/geode/mods/`

9. Abre Geode Launcher y reinicia Geometry Dash.
10. En Geode, entra en **Installed → Force 144 Hz → Settings** y deja `Frecuencia objetivo` en 144.

## Compatibilidad prevista

- Geode SDK 5.8.2
- Geometry Dash Android 2.2081
- Android de 64 bits
- Android 11 o posterior para `Surface.setFrameRate`
