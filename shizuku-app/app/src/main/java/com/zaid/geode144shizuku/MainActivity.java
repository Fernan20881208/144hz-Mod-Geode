package com.zaid.geode144shizuku;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.IBinder;
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

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView statusView;
    private IGeode144Service remoteService;
    private boolean serviceBound;

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
            remoteService = IGeode144Service.Stub.asInterface(binder);
            serviceBound = true;
            appendStatus("Servicio Shizuku conectado (UID privilegiado: " + safeShizukuUid() + ").");
            runRemote("Iniciar monitor", () -> remoteService.startMonitor());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            remoteService = null;
            serviceBound = false;
            appendStatus("El servicio privilegiado se desconectó.");
        }
    };

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = () -> {
        appendStatus("Binder de Shizuku disponible.");
        ensurePermissionAndBind();
    };

    private final Shizuku.OnBinderDeadListener binderDeadListener = () -> {
        remoteService = null;
        serviceBound = false;
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
        if (serviceBound || remoteService != null) {
            appendStatus("El servicio ya está conectado.");
            return;
        }
        try {
            appendStatus("Conectando servicio con identidad shell/root…");
            Shizuku.bindUserService(userServiceArgs, serviceConnection);
        } catch (Throwable throwable) {
            appendStatus("No se pudo conectar: " + throwable);
        }
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
            if (statusView == null) return;
            String current = statusView.getText().toString();
            statusView.setText(current.isEmpty() ? text : current + "\n\n" + text);
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);

        if (serviceBound) {
            try {
                // Mantiene el UserService daemon, pero elimina el callback de esta Activity.
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, false);
            } catch (Throwable ignored) {
            }
        }
        worker.shutdownNow();
        super.onDestroy();
    }
}
