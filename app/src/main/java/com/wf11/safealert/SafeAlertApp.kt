package com.wf11.safealert

import android.app.Application
import com.wf11.safealert.firebase.FirebaseConfig
import com.wf11.safealert.firebase.FirebaseManager
import com.wf11.safealert.utils.BeaconRegistry
import com.wf11.safealert.utils.DevSettings
import com.wf11.safealert.utils.UwbCalibrator
import com.wf11.safealert.service.CalibrationEngine
import com.wf11.safealert.service.LoneWorkerSosSync

class SafeAlertApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DevSettings.init(this)
        BeaconRegistry.init(this)
        UwbCalibrator.init(this)
        CalibrationEngine.init(this)
        FirebaseConfig.init()
        FirebaseManager.init(this)   // Alert records are held until login and sent afterwards
        // On app start, re-schedule the mail job that a force-stop wiped (an empty queue costs one file read)
        if (LoneWorkerSosSync.mailEnabled) LoneWorkerSosSync.mail(this)
    }
}
