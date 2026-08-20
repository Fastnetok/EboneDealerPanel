package com.ebone.dealerpanel

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth

class DealerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(
            this,
            FirebaseOptions.Builder()
                .setApplicationId("1:133465084190:android:1c70425fe7652e43d6390f")
                .setApiKey("AIzaSyBr-xr3gO46WTzEEmhQdRSODwgpoPptxCw")
                .setProjectId("ebone-admin-panel")
                .setStorageBucket("ebone-admin-panel.firebasestorage.app")
                .setGcmSenderId("133465084190")
                .build()
        )

        // NEW: sign in anonymously so Realtime Database reads that
        // require "auth != null" (e.g. paymentSettings, used to check
        // min/max payment limits before submitting) succeed. Without
        // this, every payment submission failed with "Unable to check
        // payment limit" because the app had no Firebase Auth user at
        // all — Firestore dealer-code login is separate from Firebase
        // Auth and doesn't satisfy these rules.
        if (FirebaseAuth.getInstance().currentUser == null) {
            FirebaseAuth.getInstance().signInAnonymously()
        }
    }
}