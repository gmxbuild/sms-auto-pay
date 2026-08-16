package com.gmtour.smsreader;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.FirebaseDatabase;

import java.util.HashMap;

public class MainActivity extends Activity {

    private EditText etEmail, etPassword;
    private Button btnLogin, btnStart;
    private TextView tvStatus, tvLastSms, tvCount, tvLog;
    private LinearLayout loginSection;
    private FirebaseAuth mAuth;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        btnLogin = findViewById(R.id.btnLogin);
        btnStart = findViewById(R.id.btnStart);
        tvStatus = findViewById(R.id.tvStatus);
        tvLastSms = findViewById(R.id.tvLastSms);
        tvCount = findViewById(R.id.tvCount);
        tvLog = findViewById(R.id.tvLog);
        loginSection = findViewById(R.id.loginSection);

        mAuth = FirebaseAuth.getInstance();
        prefs = getSharedPreferences("sms_autopay", MODE_PRIVATE);

        // Request SMS permission
        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission("android.permission.RECEIVE_SMS")
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                    "android.permission.RECEIVE_SMS",
                    "android.permission.READ_SMS"
                }, 100);
            }
        }

        // Request battery optimization exemption
        requestBatteryExemption();

        // Check if already logged in
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser != null) {
            onLoginSuccess(currentUser.getEmail());
        }

        // Load saved data
        int count = prefs.getInt("sms_count", 0);
        tvCount.setText("SMS Processed: " + count);
        String lastSms = prefs.getString("last_sms", "");
        if (!lastSms.isEmpty()) {
            tvLastSms.setText(lastSms);
        }
        String log = prefs.getString("log", "---");
        tvLog.setText(log);

        // LOGIN BUTTON
        btnLogin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doLogin();
            }
        });

        // START BUTTON
        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doStart();
            }
        });
    }

    private void doLogin() {
        String email = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString().trim();

        if (email.isEmpty() || password.isEmpty()) {
            toast("Enter email and password!");
            return;
        }
        if (password.length() < 6) {
            toast("Password minimum 6 characters!");
            return;
        }

        btnLogin.setEnabled(false);
        btnLogin.setText("CONNECTING...");
        tvStatus.setText("Connecting...");
        tvStatus.setTextColor(Color.parseColor("#FFD740"));

        // Try sign in first, if fails then sign up
        mAuth.signInWithEmailAndPassword(email, password)
            .addOnSuccessListener(authResult -> {
                registerVerified(authResult.getUser());
                onLoginSuccess(email);
            })
            .addOnFailureListener(e -> {
                // Sign in failed, try sign up
                mAuth.createUserWithEmailAndPassword(email, password)
                    .addOnSuccessListener(authResult -> {
                        registerVerified(authResult.getUser());
                        onLoginSuccess(email);
                    })
                    .addOnFailureListener(e2 -> {
                        tvStatus.setText("Failed! " + simplifyError(e2.getMessage()));
                        tvStatus.setTextColor(Color.parseColor("#FF5252"));
                        btnLogin.setEnabled(true);
                        btnLogin.setText("LOGIN");
                        toast("Login failed! Check email/password.");
                    });
            });
    }

    private void registerVerified(FirebaseUser user) {
        if (user == null) return;
        String uid = user.getUid();
        FirebaseDatabase.getInstance()
            .getReference("smsreader_verified")
            .child(uid)
            .setValue(true);
    }

    private void onLoginSuccess(String email) {
        tvStatus.setText("Logged in: " + email);
        tvStatus.setTextColor(Color.parseColor("#00E676"));
        loginSection.setVisibility(View.GONE);
        btnStart.setEnabled(true);
        btnStart.setAlpha(1.0f);
        toast("Login successful!");

        addLog("Logged in: " + email);
    }

    private void doStart() {
        if (mAuth.getCurrentUser() == null) {
            toast("Please login first!");
            return;
        }

        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission("android.permission.RECEIVE_SMS")
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                    "android.permission.RECEIVE_SMS",
                    "android.permission.READ_SMS"
                }, 100);
                toast("Allow SMS permission!");
                return;
            }
        }

        // Start foreground service
        Intent serviceIntent = new Intent(this, SmsService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        tvStatus.setText("LISTENING...");
        tvStatus.setTextColor(Color.parseColor("#00E676"));
        btnStart.setText("LISTENING...");
        btnStart.setEnabled(false);
        btnStart.setAlpha(0.4f);
        toast("SMS Auto Pay is ACTIVE!");

        addLog("Service started - listening for SMS");
    }

    private void requestBatteryExemption() {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                }
            }
        } catch (Exception e) {
            // Some phones don't support this
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 100 && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            toast("SMS Permission Granted!");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        int count = prefs.getInt("sms_count", 0);
        tvCount.setText("SMS Processed: " + count);
        String lastSms = prefs.getString("last_sms", "");
        if (!lastSms.isEmpty()) {
            tvLastSms.setText(lastSms);
        }
        String log = prefs.getString("log", "---");
        tvLog.setText(log);
    }

    private void addLog(String entry) {
        String time = new java.text.SimpleDateFormat("HH:mm:ss",
            java.util.Locale.getDefault()).format(new java.util.Date());
        String oldLog = prefs.getString("log", "---");
        String newLog = time + " | " + entry + "\n" + oldLog;
        prefs.edit().putString("log", newLog).apply();
        tvLog.setText(newLog);
    }

    private String simplifyError(String msg) {
        if (msg == null) return "Unknown error";
        if (msg.contains("password is invalid")) return "Wrong password";
        if (msg.contains("no user record")) return "Account not found";
        if (msg.contains("email address is badly")) return "Invalid email";
        if (msg.contains("already in use")) return "Email already registered";
        if (msg.contains("network")) return "No internet";
        if (msg.contains("too many")) return "Too many attempts";
        return "Check email/password";
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
