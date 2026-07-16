# Geode 144 Shizuku

Aplicación complementaria para Android que intenta mantener **144 Hz** únicamente mientras `com.geode.launcher` está en primer plano.

## Funcionamiento

1. Se conecta a Shizuku mediante un `UserService` ejecutado con identidad `shell` o `root`.
2. Detecta si Geode Launcher es la actividad reanudada.
3. Antes de modificar nada guarda:
   - `peak_refresh_rate` y `min_refresh_rate`.
   - las claves globales del modo de pantalla preferido.
   - el modo preferido específico del display interno `0`.
   - `game_overlay` únicamente cuando Shizuku se inició con root.
4. Mientras Geode está delante, reaplica cada 10 segundos:
   - `1080 × 2392 @ 144.00002 Hz`.
   - frecuencia mínima y máxima de 144 Hz.
   - las claves globales `user_preferred_refresh_rate`, `user_preferred_resolution_width` y `user_preferred_resolution_height`.
   - el modo preferido del display interno `0`.
5. Cuando Geode deja el primer plano, restaura los valores originales y borra el snapshot.

El snapshot se guarda temporalmente en `/data/local/tmp/geode144-shizuku.state`, de forma que el servicio pueda recuperarlo después de un cierre inesperado o una actualización desde la versión 1.1.0.

## Actualización 1.1.2

Los `UserService` configurados como daemon pueden seguir ejecutándose después de instalar un APK nuevo. Esto hacía que la interfaz 1.1.1 se conectara al motor antiguo 1.1.0 y siguiera mostrando comandos eliminados.

La versión 1.1.2:

- detecta el primer inicio después de cada actualización;
- restaura los valores administrados por el daemon anterior;
- cierra y elimina automáticamente el `UserService` obsoleto;
- enlaza una instancia nueva antes de iniciar el monitor;
- evita solicitudes de conexión duplicadas;
- añade el botón **Reiniciar servicio privilegiado** para recuperación manual.

## Diferencia entre Shizuku ADB y root

Con Shizuku iniciado mediante depuración inalámbrica o ADB, el `UserService` usa UID `2000` (`shell`). En algunas ROM, incluida la probada, ese UID puede escribir los ajustes de frecuencia, pero Android bloquea:

- el comando global `cmd display set-user-preferred-display-mode` sin un ID de display;
- las escrituras arbitrarias de `device_config game_overlay`;
- Game Mode de rendimiento para aplicaciones que no declaran soporte;
- determinadas variantes OEM de `cmd game set`.

La app evita esos comandos en modo shell. Usa el display interno `0` y escribe directamente las claves globales empleadas por DisplayManager. Si Shizuku se inicia con root, también puede intentar `game_overlay`.

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
3. Instala o actualiza el APK y concede su permiso en Shizuku.
4. En el primer inicio tras actualizar, espera a que aparezca **Servicio anterior cerrado; conectando la versión actual…**.
5. La app enlazará una única instancia y activará el monitor automáticamente.
6. Si todavía aparece salida de una versión anterior, pulsa **Reiniciar servicio privilegiado**.
7. Abre Geode y después usa **Generar diagnóstico** para comprobar:
   - `peak_refresh_rate` y `min_refresh_rate`;
   - las tres claves globales del modo preferido;
   - el modo preferido del display `0`;
   - cualquier `mFrameRateOverrides` aplicado al UID de Geode.
8. Usa **Detener y restaurar valores** antes de desinstalar la aplicación.

## Límites

Shizuku con identidad `shell` depende de los permisos que el fabricante conserve para ADB. La app puede solicitar y fijar el modo de pantalla, pero no puede eliminar una política privada del fabricante que limite por UID dentro de SurfaceFlinger o GameManager. El diagnóstico permite distinguir entre frecuencia física de la pantalla y frecuencia de renderizado asignada a Geode.
