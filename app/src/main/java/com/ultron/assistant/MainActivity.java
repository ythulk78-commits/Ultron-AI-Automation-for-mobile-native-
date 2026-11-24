package com.ultron.assistant;

import android.Manifest;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * PHASE 1/2 - Entry point. Walks the user through the manual permission grants
 * the spec requires, then starts UltronService. Re-check permission status
 * every time the activity resumes since these are all granted outside the app.
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_MIC_PERMISSION = 100;
    private static final int REQ_CONTACTS_CALL_PERMISSION = 101;

    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.status_text);
        Button startButton = findViewById(R.id.start_button);
        Button stopButton = findViewById(R.id.stop_button);
        Button grantOverlayButton = findViewById(R.id.grant_overlay_button);
        Button grantAccessibilityButton = findViewById(R.id.grant_accessibility_button);
        Button grantDeviceAdminButton = findViewById(R.id.grant_device_admin_button);
        Button grantWriteSettingsButton = findViewById(R.id.grant_write_settings_button);
        Button grantContactsCallButton = findViewById(R.id.grant_contacts_call_button);
        Button batteryOptimizationButton = findViewById(R.id.battery_optimization_button);
        Button autostartButton = findViewById(R.id.autostart_button);
        Button settingsButton = findViewById(R.id.settings_button);
        Button talkButton = findViewById(R.id.talk_button);

        startButton.setOnClickListener(v -> requestMicThenStart());
        stopButton.setOnClickListener(v -> {
            Intent stopIntent = new Intent(this, UltronService.class);
            stopIntent.setAction(UltronService.ACTION_STOP);
            startService(stopIntent);
            Toast.makeText(this, "Ultron stopped", Toast.LENGTH_SHORT).show();
            refreshStatus();
        });

        grantOverlayButton.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });

        grantAccessibilityButton.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        grantDeviceAdminButton.setOnClickListener(v -> {
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    new ComponentName(this, DeviceAdminReceiver.class));
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Ultron needs this to lock your screen on voice command.");
            startActivity(intent);
        });

        grantWriteSettingsButton.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });

        grantContactsCallButton.setOnClickListener(v ->
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE},
                        REQ_CONTACTS_CALL_PERMISSION));

        batteryOptimizationButton.setOnClickListener(v ->
                BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(this));

        autostartButton.setOnClickListener(v ->
                BatteryOptimizationHelper.openAppInfoSettings(this));

        settingsButton.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        talkButton.setOnClickListener(v -> {
            Intent hudIntent = new Intent(this, UltronHudActivity.class);
            hudIntent.putExtra(UltronHudActivity.EXTRA_AUTO_LISTEN, false);
            startActivity(hudIntent);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void requestMicThenStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC_PERMISSION);
            return;
        }
        startUltronService();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC_PERMISSION
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startUltronService();
        } else {
            Toast.makeText(this, "Mic permission ke bina Ultron kaam nahi karega", Toast.LENGTH_LONG).show();
        }
    }

    private void startUltronService() {
        Intent serviceIntent = new Intent(this, UltronService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        Toast.makeText(this, "Ultron started - triple-press Volume Up or shake to wake", Toast.LENGTH_LONG).show();
        refreshStatus();
    }

    private void refreshStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("Mic: ").append(hasMic() ? "Granted" : "Not granted").append("\n");
        sb.append("Overlay: ").append(Settings.canDrawOverlays(this) ? "Granted" : "Not granted").append("\n");
        sb.append("Accessibility: ").append(isAccessibilityEnabled() ? "Enabled" : "Not enabled").append("\n");
        sb.append("Device Admin: ").append(isDeviceAdminActive() ? "Active" : "Not active").append("\n");
        sb.append("Write Settings: ").append(Settings.System.canWrite(this) ? "Granted" : "Not granted");
        statusText.setText(sb.toString());
    }

    private boolean hasMic() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isDeviceAdminActive() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isAdminActive(new ComponentName(this, DeviceAdminReceiver.class));
    }

    private boolean isAccessibilityEnabled() {
        String enabledServices = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabledServices)) return false;
        String myService = getPackageName() + "/" + UltronAccessibilityService.class.getName();
        return enabledServices.contains(myService);
    }
}
