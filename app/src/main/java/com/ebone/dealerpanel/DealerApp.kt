package com.ebone.dealerpanel
import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
class DealerApp:Application(){override fun onCreate(){super.onCreate();FirebaseApp.initializeApp(this,FirebaseOptions.Builder().setApplicationId("1:133465084190:android:1c70425fe7652e43d6390f").setApiKey("AIzaSyBr-xr3gO46WTzEEmhQdRSODwgpoPptxCw").setProjectId("ebone-admin-panel").setStorageBucket("ebone-admin-panel.firebasestorage.app").setGcmSenderId("133465084190").build())}}
