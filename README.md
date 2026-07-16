# Force 144 Hz — Geode para Android

Este repositorio reúne dos enfoques complementarios para intentar que Geometry Dash mediante Geode use la frecuencia máxima del dispositivo:

- **Mod de Geode:** solicita el modo de pantalla desde el propio proceso del juego.
- **Geode 144 Shizuku:** aplicación Android separada que actúa con identidad `shell` o `root`, aplica 144 Hz solo mientras Geode está en primer plano y restaura la configuración al salir.

## Aplicación Geode 144 Shizuku

El proyecto de la aplicación está en [`shizuku-app/`](shizuku-app/README.md) y se desarrolla en la rama `geode144-shizuku`.

Está preparado específicamente para el modo comprobado en el dispositivo de prueba:

- Resolución: `1080 × 2392`.
- Frecuencia: `144.00002 Hz`.
- Paquete vigilado: `com.geode.launcher`.

La versión actual es **1.1.2**. Además de adaptar los comandos a las restricciones del UID `shell`, reinicia automáticamente cualquier `UserService` daemon perteneciente a una instalación anterior. Esto evita que una actualización del APK siga conectándose al motor 1.1.0 almacenado en memoria.

La aplicación usa un `UserService` de Shizuku para:

1. Detectar si Geode Launcher está en primer plano.
2. Guardar los valores originales de frecuencia y modo de pantalla.
3. Solicitar 144 Hz mediante ajustes permitidos para UID `shell` y el display interno `0`.
4. Reaplicar la configuración periódicamente mientras Geode permanece activo.
5. Restaurar todos los valores cuando Geode deja el primer plano o el usuario detiene el monitor.
6. Generar un diagnóstico con modos expuestos, overrides por UID y políticas de DisplayManager.
7. Cerrar automáticamente servicios privilegiados obsoletos después de actualizar el APK.

### Compilar la aplicación

Abre **Actions → Build Geode144 Shizuku APK → Run workflow** desde la rama `geode144-shizuku`. El APK se publica como artefacto `Geode144-Shizuku-APK`.

## Mod de Geode

El mod intenta pedir 144 Hz al sistema Android desde el proceso de Geode.

### Qué hace

1. Lee los modos que Android expone a la aplicación.
2. Solicita la frecuencia configurada mediante `Surface.setFrameRate`.
3. Si existe un modo cercano a esa frecuencia, también fija su `preferredDisplayModeId`.
4. Muestra un diagnóstico indicando el máximo que Android expone a Geode.

### Limitación importante

El launcher oficial de Geode ya intenta seleccionar el modo de mayor frecuencia. Además, en el launcher actual, `Cocos2dxRenderer.setAnimationInterval()` está vacío porque no controla los FPS de Geometry Dash. Por eso este proyecto no es un “FPS bypass” clásico: refuerza la solicitud al sistema y permite saber si Android está ocultando el modo de 144 Hz.

Si el diagnóstico dice que el máximo expuesto es 120 Hz, el mod no puede crear un modo de pantalla que el firmware no ofrece a la aplicación. En ese caso revisa:

- Frecuencia de pantalla configurada en 144 Hz o modo Alto.
- Ahorro de batería desactivado.
- Ajuste de frecuencia por aplicación, si tu sistema lo incluye.
- Temperatura del dispositivo.
- La superposición “Mostrar frecuencia de actualización” en Opciones de desarrollador.

### Compilar el mod desde un teléfono Android

La vía recomendada es GitHub Actions. El equipo de Geode cerró como “not planned” la solicitud de compilar mods en ARM64 directamente dentro de Termux, debido a problemas con las herramientas de generación de código.

1. Abre la pestaña **Actions**.
2. Ejecuta el workflow de compilación Android64 disponible en el repositorio.
3. Descarga el artefacto generado y extrae el archivo `.geode`.
4. Cópialo a:

   `/storage/emulated/0/Android/media/com.geode.launcher/game/geode/mods/`

5. Abre Geode Launcher y reinicia Geometry Dash.
6. En Geode, entra en **Installed → Force 144 Hz → Settings** y deja `Frecuencia objetivo` en 144.

## Compatibilidad prevista

- Geode SDK 5.8.2.
- Geometry Dash Android 2.2081.
- Android de 64 bits.
- Android 11 o posterior recomendado.
- Shizuku API 13.1.5 para la aplicación complementaria.
