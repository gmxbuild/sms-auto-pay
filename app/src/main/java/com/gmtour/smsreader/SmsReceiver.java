package com.gmtour.smsreader;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.FirebaseDatabase;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SmsReceiver extends BroadcastReceiver {

    private static final String TAG = "SmsAutoPay";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        if (!intent.getAction().equals("android.provider.Telephony.SMS_RECEIVED")) return;

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.d(TAG, "Not logged in, skipping SMS");
            return;
        }

        try {
            Bundle bundle = intent.getExtras();
            if (bundle == null) return;

            Object[] pdus = (Object[]) bundle.get("pdus");
            if (pdus == null || pdus.length == 0) return;

            String format = bundle.getString("format");
            StringBuilder fullMsg = new StringBuilder();
            String sender = "";

            for (Object pdu : pdus) {
                SmsMessage sms;
                if (android.os.Build.VERSION.SDK_INT >= 23) {
                    sms = SmsMessage.createFromPdu((byte[]) pdu, format);
                } else {
                    sms = SmsMessage.createFromPdu((byte[]) pdu);
                }
                if (sms == null) continue;
                sender = sms.getDisplayOriginatingAddress();
                fullMsg.append(sms.getMessageBody());
            }

            String body = fullMsg.toString();
            if (body.isEmpty()) return;

            String upper = body.toUpperCase();
            Log.d(TAG, "SMS FROM: " + sender);
            Log.d(TAG, "SMS BODY: " + body);

            // ══════ DETECT AGENT ══════
            String agent = "";
            String type = "";

            if (upper.contains("BKASH") || sender.contains("16216")
                    || sender.contains("16247") || sender.toLowerCase().contains("bkash")) {
                agent = "bKash"; type = "B";
            } else if (upper.contains("NAGAD") || sender.contains("16167")
                    || sender.contains("16267") || sender.toLowerCase().contains("nagad")) {
                agent = "Nagad"; type = "N";
            } else if (upper.contains("ROCKET") || upper.contains("DBBL")
                    || sender.contains("16227") || sender.toLowerCase().contains("rocket")) {
                agent = "Rocket"; type = "R";
            }

            if (agent.isEmpty()) {
                Log.d(TAG, "Not a payment SMS");
                return;
            }

            // ══════ EXTRACT AMOUNT ══════
            double amount = extractAmount(body);
            if (amount <= 0) {
                Log.d(TAG, "Amount not found");
                return;
            }

            // ══════ EXTRACT TXID ══════
            String txid = extractTxid(body);
            if (txid.isEmpty()) {
                Log.d(TAG, "TxID not found");
                return;
            }

            Log.d(TAG, "DETECTED: " + agent + " | " + txid + " | Tk" + amount);

            // ══════ UPLOAD TO FIREBASE ══════
            HashMap<String, Object> data = new HashMap<>();
            data.put("amount", amount);
            data.put("agent", agent);
            data.put("type", type);
            data.put("time", System.currentTimeMillis());

            FirebaseDatabase.getInstance()
                .getReference("XNXANIKPAY")
                .child(txid)
                .setValue(data)
                .addOnSuccessListener(v -> Log.d(TAG, "UPLOADED: " + txid))
                .addOnFailureListener(e -> Log.e(TAG, "UPLOAD FAILED: " + e.getMessage()));

            // ══════ SAVE LOCAL LOG ══════
            SharedPreferences prefs = context.getSharedPreferences("sms_autopay", Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();

            int count = prefs.getInt("sms_count", 0) + 1;
            editor.putInt("sms_count", count);

            String lastInfo = agent + " | " + txid + " | Tk" + amount;
            editor.putString("last_sms", lastInfo);

            String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            String oldLog = prefs.getString("log", "---");
            String newLog = time + " | " + agent + " | " + txid + " | Tk" + amount + " [#" + count + "]\n" + oldLog;
            editor.putString("log", newLog);
            editor.apply();

        } catch (Exception e) {
            Log.e(TAG, "ERROR: " + e.getMessage());
        }
    }

    private double extractAmount(String body) {
        String[] patterns = {
            "(?i)(?:received|cash.?in|পেয়েছেন|পাঠিয়েছেন|প্রাপ্ত|জমা)\\s*(?:Tk\\.?|BDT|৳|Taka)?\\s*([\\d,]+\\.?\\d*)",
            "(?i)Tk\\.?\\s*([\\d,]+\\.?\\d*)",
            "(?i)BDT\\s*([\\d,]+\\.?\\d*)",
            "৳\\s*([\\d,]+\\.?\\d*)",
            "(?i)Taka\\s+([\\d,]+\\.?\\d*)",
            "(?i)amount[:\\s]*(?:Tk\\.?)?\\s*([\\d,]+\\.?\\d*)"
        };
        for (String p : patterns) {
            Matcher m = Pattern.compile(p).matcher(body);
            if (m.find()) {
                try {
                    double val = Double.parseDouble(m.group(1).replace(",", ""));
                    if (val > 0) return val;
                } catch (Exception e) { /* next */ }
            }
        }
        return 0;
    }

    private String extractTxid(String body) {
        String[] patterns = {
            "(?i)TrxID[:\\s]*([A-Za-z0-9]+)",
            "(?i)Trx\\s+ID[:\\s]*([A-Za-z0-9]+)",
            "(?i)TxnID[:\\s]*([A-Za-z0-9]+)",
            "(?i)Txn\\s+ID[:\\s]*([A-Za-z0-9]+)",
            "(?i)TXID[:\\s]*([A-Za-z0-9]+)",
            "(?i)txid[:\\s]*([A-Za-z0-9]+)",
            "(?i)trxid[:\\s]*([A-Za-z0-9]+)",
            "(?i)Transaction\\s+ID[:\\s]*([A-Za-z0-9]+)",
            "(?i)Ref\\.?\\s*No\\.?[:\\s]*([A-Za-z0-9]+)",
            "(?i)Reference[:\\s]*([A-Za-z0-9]+)",
            "(?i)Ref[:\\s]+([A-Za-z0-9]{6,})",
            "(?i)Trx[:\\s]*([A-Za-z0-9]{8,})",
            "\\b([A-Z0-9]{10,})\\b"
        };
        for (String p : patterns) {
            Matcher m = Pattern.compile(p).matcher(body);
            if (m.find()) {
                String found = m.group(1).replaceAll("[^A-Za-z0-9]", "").toUpperCase();
                if (found.length() >= 6) return found;
            }
        }
        return "";
    }
}
