package com.zaid.geode144shizuku;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public final class MainActivity extends Activity {
    private static final int SHIZUKU_PERMISSION_REQUEST = 144;
    private static final String SERVICE_PREFS = "privileged_service";
    private static final String SERVICE_VERSION_KEY = "installed_version_code";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextView statusView;
    private IGeode144Service remoteService;
    private boolean serviceBound;
    private boolean bindingInProgress;
    private boolean restartInProgress;
    private boolean rebindScheduled;
    private boolean activityDestroyed;

    private final Shizuku.UserServiceArgs userServiceArgs =
            new Shizuku.UserServiceArgs(
                    new ComponentName(BuildConfig.APPLICATION_ID, Geode144UserService.class.getName()))
                    .daemon(true)
                    .processNameSuffix("geode144")
                    .debuggable(BuildConfig.DEBUG)
                    .version(BuildConfig.VERSION_CODE);

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            bindingInProgress = false;
            remoteService = IGeode144Service.Stub.asInterface(binder);
            serviceBound = true;

            if (needsUpgradeRestart()) {
                appendStatus(
                        "Se detectó un UserService de una instalación anterior. "
                                + "Restaurando valores y reiniciándolo…");
                rememberCurrentServiceVersion();
                restartPrivilegedServiceInternal("actualización del APK");
                return;
            }

            appendStatus(
                    "Servicio Shizuku conectado (UID privilegiado: "
                            + safeShizukuUid() + ").");
            runRemote("Iniciar monitor", () -> remoteService.startMonitor());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            remoteService = null;
            serviceBound = false;
            bindingInProgress = false;

            if (restartInProgress) {
                scheduleRebindAfterRestart();
            } else {
                appendStatus("El servicio privilegiado se desconectó.");
            }
        }
    };

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = () -> {
        appendStatus("Binder de Shizuku disponible.");
        ensurePermissionAndBind();
    };

    private final Shizuku.OnBinderDeadListener binderDeadListener = () -> {
        remoteService = null;
        serviceBound = false;
        bindingInProgress = false;
        restartInProgress = false;
        rebindScheduled = false;
        appendStatus("Shizuku se detuvo. Reinícialo y vuelve a abrir esta app.");
    };

    private final Shizuku.OnRequestPermissionResultListener permissionResultListener =
            (requestCode, grantResult) -> {
                if (requestCode != SHIZUKU_PERMISSION_REQUEST) return;
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    appendStatus("Permiso de Shizuku concedido.");
                    bindPrivilegedService();
                } else {
                    appendStatus("Permiso de Shizuku denegado.");
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContentView());

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionResultListener);

        appendStatus("Geode 144 Shizuku v" + BuildConfig.VERSION_NAME);
        appendStatus("Objetivo: 1080×2392 @ 144.00002 Hz para com.geode.launcher.");
        ensurePermissionAndBind();
    }

    private View createContentView() {
        int padding = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText(R.string.title);
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView explanation = new TextView(this);
        explanation.setText(R.string.explanation);
        explanation.setTextSize(15);
        explanation.setPadding(0, dp(8), 0, dp(14));
        root.addView(explanation);

        root.addView(button("Conectar / conceder permiso", v -> ensurePermissionAndBind()));
        root.addView(button("Reiniciar servicio privilegiado", v ->
                restartPrivilegedServiceInternal("reinicio manual")));
        root.addView(button("Iniciar monitor automático", v -> withService(
                () -> runRemote("Iniciar monitor", () -> remoteService.startMonitor()))));
        root.addView(button("Detener y restaurar valores", v -> withService(
                () -> runRemote("Restaurar", () -> remoteService.stopAndRestore()))));
        root.addView(button("Generar diagnóstico", v -> withService(
                () -> runRemote("Diagnóstico", () -> remoteService.diagnose()))));
        root.addView(button("Abrir Geode Launcher", v -> openGeode()));
        root.addView(button("Abrir Shizuku", v -> openShizuku()));
        root.addView(button("Depuración inalámbrica", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        }));

        TextView statusTitle = new TextView(this);
        statusTitle.setText("Estado y diagnóstico");
        statusTitle.setTextSize(18);
        statusTitle.setTypeface(Typeface.DEFAULT_BOLD);
        statusTitle.setPadding(0, dp(18), 0, dp(8));
        root.addView(statusTitle);

        statusView = new TextView(this);
        statusView.setTextSize(13);
        statusView.setTypeface(Typeface.MONOSPACE);
        statusView.setTextIsSelectable(true);
        statusView.setPadding(dp(12), dp(12), dp(12), dp(12));
        statusView.setBackgroundResource(R.drawable.status_background);
        root.addView(statusView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(root);
        return scrollView;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(6);
        button.setLayoutParams(params);
        return button;
    }

    private void ensurePermissionAndBind() {
        if (!Shizuku.pingBinder()) {
            appendStatus("Shizuku no está activo. Inícialo y vuelve a intentar.");
            return;
        }
        if (Shizuku.isPreV11()) {
            appendStatus("La API de Shizuku instalada es demasiado antigua (se requiere v11+).");
            return;
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            bindPrivilegedService();
            return;
        }
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            appendStatus("Shizuku bloqueó nuevas solicitudes. Habilita el permiso desde Shizuku.");
            return;
        }
        appendStatus("Solicitando permiso de Shizuku…");
        Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST);
    }

    private void bindPrivilegedService() {
        if (activityDestroyed || restartInProgress) return;
        if (serviceBound || remoteService != null) {
            appendStatus("El servicio ya está conectado.");
            return;
        }
        if (bindingInProgress) {
            return;
        }

        try {
            bindingInProgress = true;
            appendStatus("Conectando servicio con identidad shell/root…");
            Shizuku.bindUserService(userServiceArgs, serviceConnection);
        } catch (Throwable throwable) {
            bindingInProgress = false;
            appendStatus("No se pudo conectar: " + throwable);
        }
    }

    private boolean needsUpgradeRestart() {
        int installedVersion = getSharedPreferences(SERVICE_PREFS, MODE_PRIVATE)
                .getInt(SERVICE_VERSION_KEY, -1);
        return installedVersion != BuildConfig.VERSION_CODE;
    }

    private void rememberCurrentServiceVersion() {
        getSharedPreferences(SERVICE_PREFS, MODE_PRIVATE)
                .edit()
                .putInt(SERVICE_VERSION_KEY, BuildConfig.VERSION_CODE)
                .apply();
    }

    private void restartPrivilegedServiceInternal(String reason) {
        if (restartInProgress) {
            appendStatus("El servicio ya se está reiniciando.");
            return;
        }

        restartInProgress = true;
        rememberCurrentServiceVersion();
        appendStatus("Reiniciando servicio privilegiado (" + reason + ")…");

        IGeode144Service service = remoteService;
        worker.execute(() -> {
            if (service != null) {
                try {
                    service.stopAndRestore();
                } catch (Throwable ignored) {
                }
                try {
                    service.exit();
                } catch (Throwable ignored) {
                }
            }

            runOnUiThread(() -> {
                try {
                    Shizuku.unbindUserService(userServiceArgs, serviceConnection, true);
                } catch (Throwable ignored) {
                }
                remoteService = null;
                serviceBound = false;
                bindingInProgress = false;
                scheduleRebindAfterRestart();
            });
        });
    }

    private void scheduleRebindAfterRestart() {
        if (activityDestroyed || rebindScheduled) return;
        rebindScheduled = true;
        mainHandler.postDelayed(() -> {
            rebindScheduled = false;
            restartInProgress = false;
            appendStatus("Servicio anterior cerrado; conectando la versión actual…");
            bindPrivilegedService();
        }, 1500L);
    }

    private void withService(Runnable action) {
        if (remoteService == null) {
            appendStatus("El servicio aún no está conectado; intentando conectar.");
            ensurePermissionAndBind();
            return;
        }
        action.run();
    }

    private interface RemoteStringCall {
        String run() throws RemoteException;
    }

    private void runRemote(String label, RemoteStringCall call) {
        worker.execute(() -> {
            try {
                String result = call.run();
                appendStatus(label + ":\n" + result);
            } catch (Throwable throwable) {
                appendStatus(label + " falló: " + throwable);
            }
        });
    }

    private void openGeode() {
        Intent intent = getPackageManager().getLaunchIntentForPackage("com.geode.launcher");
        if (intent == null) {
            Toast.makeText(this, "Geode Launcher no está instalado", Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(intent);
    }

    private void openShizuku() {
        Intent intent = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
        if (intent == null) {
            Toast.makeText(this, "Shizuku no está instalado", Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(intent);
    }

    private int safeShizukuUid() {
        try {
            return Shizuku.getUid();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private void appendStatus(String text) {
        runOnUiThread(() -> {
            if (statusView == null || activityDestroyed) return;
            String current = statusView.getText().toString();
            statusView.setText(current.isEmpty() ? text : current + "\n\n" + text);
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        activityDestroyed = true;
        mainHandler.removeCallbacksAndMessages(null);

        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);

        if (serviceBound || bindingInProgress) {
            try {
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, false);
            } catch (Throwable ignored) {
            }
        }
        worker.shutdownNow();
        super.onDestroy();
    }
}
