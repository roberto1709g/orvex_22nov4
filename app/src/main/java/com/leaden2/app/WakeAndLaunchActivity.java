package com.leaden2.app;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * Transición visual mínima para solicitudes de llamada que llegan cuando ORVEX
 * está en segundo plano. Despierta la pantalla y respeta el bloqueo seguro:
 * WhatsApp solo se abre cuando Android indica que el dispositivo está desbloqueado.
 */
public class WakeAndLaunchActivity extends Activity {
    private static final String TAG = "ORVEXWakeLaunch";
    public static final String EXTRA_CONTACT_NAME = "orvex_contact_name";
    public static final String EXTRA_PHONE = "orvex_contact_phone";
    private boolean launchHandled = false;
    private boolean unlockRequestSent = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WhatsAppCallDiagnostics.recordDeviceState(this, "ACTIVITY: WakeAndLaunchActivity.onCreate ejecutado.");
        requestVisibleOverLockScreen();
        continueAfterUnlock();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        WhatsAppCallDiagnostics.recordDeviceState(this, "ACTIVITY: onNewIntent recibido.");
        launchHandled = false;
        continueAfterUnlock();
    }

    @Override
    protected void onResume() {
        super.onResume();
        WhatsAppCallDiagnostics.recordDeviceState(this, "ACTIVITY: onResume ejecutado.");
        // Se vuelve a comprobar al regresar del diálogo de desbloqueo del sistema.
        continueAfterUnlock();
    }

    @Override
    protected void onStart() {
        super.onStart();
        WhatsAppCallDiagnostics.recordDeviceState(this, "ACTIVITY: WakeAndLaunchActivity.onStart ejecutado.");
    }

    @Override
    protected void onPause() {
        WhatsAppCallDiagnostics.recordDeviceState(this, "ACTIVITY: WakeAndLaunchActivity.onPause ejecutado.");
        super.onPause();
    }

    @Override
    protected void onStop() {
        WhatsAppCallDiagnostics.recordDeviceState(this, "ACTIVITY: WakeAndLaunchActivity.onStop ejecutado.");
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        WhatsAppCallDiagnostics.recordDeviceState(this, "ACTIVITY: WakeAndLaunchActivity.onDestroy ejecutado.");
        super.onDestroy();
    }

    private void requestVisibleOverLockScreen() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
    }

    private void continueAfterUnlock() {
        if (launchHandled) return;

        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (keyguard == null || !keyguard.isKeyguardLocked()) {
            WhatsAppCallDiagnostics.record(this, "BLOQUEO: Android informa que el dispositivo no está bloqueado; se continuará a WhatsApp.");
            launchWhatsApp();
            return;
        }

        WhatsAppCallDiagnostics.record(this, "BLOQUEO: Android informa que el dispositivo sigue bloqueado.");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (unlockRequestSent) return;
            unlockRequestSent = true;
            WhatsAppCallDiagnostics.record(this, "BLOQUEO: solicitando a Android requestDismissKeyguard; requiere autorización normal del usuario.");
            keyguard.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override
                public void onDismissSucceeded() {
                    unlockRequestSent = false;
                    WhatsAppCallDiagnostics.recordDeviceState(WakeAndLaunchActivity.this, "BLOQUEO: callback onDismissSucceeded; Android confirmó el desbloqueo.");
                    runOnUiThread(() -> launchWhatsApp());
                }

                @Override
                public void onDismissCancelled() {
                    unlockRequestSent = false;
                    WhatsAppCallDiagnostics.recordDeviceState(WakeAndLaunchActivity.this, "BLOQUEO: callback onDismissCancelled; WhatsApp no se abrió.");
                    Log.i(TAG, "El usuario canceló el desbloqueo; WhatsApp no se abrirá todavía.");
                }

                @Override
                public void onDismissError() {
                    WhatsAppCallDiagnostics.recordDeviceState(WakeAndLaunchActivity.this, "BLOQUEO ERROR: callback onDismissError; Android no pudo completar la solicitud de desbloqueo.");
                    Log.w(TAG, "Android no pudo mostrar el flujo de desbloqueo; se esperará el desbloqueo manual.");
                }
            });
        } else {
            Toast.makeText(this, "Desbloquea el teléfono para continuar con WhatsApp.", Toast.LENGTH_LONG).show();
        }
    }

    private void launchWhatsApp() {
        if (launchHandled) return;

        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (keyguard != null && keyguard.isKeyguardLocked()) {
            WhatsAppCallDiagnostics.record(this, "APERTURA BLOQUEADA: launchWhatsApp se detuvo porque Android mantiene el bloqueo activo.");
            Log.i(TAG, "El teléfono sigue bloqueado; se esperará el desbloqueo manual.");
            return;
        }

        String phone = getIntent().getStringExtra(EXTRA_PHONE);
        String contactName = getIntent().getStringExtra(EXTRA_CONTACT_NAME);
        if (phone == null || phone.trim().isEmpty()) {
            WhatsAppCallDiagnostics.record(this, "APERTURA ERROR: falta el número validado; no se abrirá WhatsApp.");
            Log.e(TAG, "Falta el número validado; no se abrirá WhatsApp.");
            finish();
            return;
        }

        String digits = phone.replaceAll("\\D", "");
        Uri[] uris = new Uri[] {
                Uri.parse("whatsapp://send?phone=" + digits),
                Uri.parse("https://wa.me/" + digits)
        };
        String[] packages = {"com.whatsapp", "com.whatsapp.w4b"};

        for (String packageName : packages) {
            for (int uriIndex = 0; uriIndex < uris.length; uriIndex++) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, uris[uriIndex]);
                    intent.setPackage(packageName);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    WhatsAppCallDiagnostics.record(this, "APERTURA: intentando abrir paquete=" + packageName + " mediante URI " + (uriIndex == 0 ? "whatsapp://send" : "https://wa.me") + ".");
                    startActivity(intent);
                    launchHandled = true;
                    WhatsAppCallDiagnostics.recordDeviceState(this, "APERTURA: startActivity no lanzó excepción para paquete=" + packageName + "; WhatsApp aún debe confirmarse mediante accesibilidad.");
                    Log.i(TAG, "Chat de " + (contactName == null ? "contacto" : contactName)
                            + " solicitado mediante " + packageName + ".");
                    finish();
                    return;
                } catch (ActivityNotFoundException e) {
                    WhatsAppCallDiagnostics.record(this, "APERTURA: ActivityNotFoundException para paquete=" + packageName + " y URI " + (uriIndex == 0 ? "whatsapp://send" : "https://wa.me") + ".");
                    Log.i(TAG, "No se encontró manejador para " + packageName + " y URI " + uris[uriIndex]);
                } catch (SecurityException e) {
                    WhatsAppCallDiagnostics.record(this, "APERTURA BLOQUEADA: SecurityException para paquete=" + packageName + ": " + String.valueOf(e.getMessage()));
                    Log.w(TAG, "Android bloqueó la apertura de WhatsApp para " + packageName, e);
                }
            }
        }

        WhatsAppCallDiagnostics.record(this, "APERTURA ERROR: no se encontró un manejador de WhatsApp compatible después de probar los paquetes y URI disponibles.");
        Toast.makeText(this, "No pude abrir WhatsApp. Comprueba que esté instalado.", Toast.LENGTH_LONG).show();
        finish();
    }
}
