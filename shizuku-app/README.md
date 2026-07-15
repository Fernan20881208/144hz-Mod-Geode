# Geode 144 Shizuku

Aplicación complementaria para Android que intenta mantener **144 Hz** únicamente mientras `com.geode.launcher` está en primer plano.

## Funcionamiento

1. Se conecta a Shizuku mediante un `UserService` ejecutado con identidad `shell` o `root`.
2. Detecta si Geode Launcher es la actividad reanudada.
3. Antes de modificar nada guarda:
   - `peak_refresh_rate` y `min_refresh_rate`.
   - configuración `game_overlay` de Geode.
   - Game Mode actual, cuando Android lo expone.
   - modo de pantalla preferido por el usuario.
4. Mientras Geode está delante, reaplica:
   - `1080 × 2392 @ 144.00002 Hz`.
   - frecuencia mínima y máxima de 144 Hz.
   - Game Mode de rendimiento sin límite fijo de FPS ni downscaling.
5. Cuando Geode deja el primer plano, restaura los valores originales y borra el snapshot.

El snapshot se guarda temporalmente en `/data/local/tmp/geode144-shizuku.state`, de forma que el servicio pueda recuperarlo después de un cierre inesperado.

## Requisitos

- Android 11 o posterior recomendado.
- Shizuku iniciado mediante depuración inalámbrica, ADB o root.
- Geode Launcher instalado como `com.geode.launcher`.
- Pantalla que exponga el modo `1080 × 2392 @ 144.00002 Hz`.

## Compilar

Desde la rama `geode144-shizuku`, ejecuta el workflow **Build Geode144 Shizuku APK**. El artefacto se llama `Geode144-Shizuku-APK`.

También puede compilarse localmente con Java 17, Android SDK 35 y Gradle 8.10.2:

```bash
cd shizuku-app
gradle :app:assembleDebug
```

## Uso

1. Instala y abre Shizuku.
2. Inicia el servicio de Shizuku.
3. Instala el APK generado y concede su permiso en Shizuku.
4. La app conecta el servicio y activa el monitor automáticamente.
5. Usa **Generar diagnóstico** para revisar el modo activo y posibles overrides por UID.
6. Usa **Detener y restaurar valores** antes de desinstalar la aplicación.

## Límites

Shizuku con identidad `shell` depende de los permisos que el fabricante conserve para ADB. Algunas ROM pueden bloquear comandos concretos o volver a imponer un límite por UID. La pantalla puede funcionar físicamente a 144 Hz y, aun así, el renderizado de una aplicación quedar limitado por GameManager, SurfaceFlinger o una política OEM.
