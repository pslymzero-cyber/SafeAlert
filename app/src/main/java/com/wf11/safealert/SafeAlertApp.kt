package com.wf11.safealert

import android.app.Application
import com.wf11.safealert.firebase.FirebaseConfig
import com.wf11.safealert.utils.BeaconRegistry
import com.wf11.safealert.utils.DevSettings
import com.wf11.safealert.utils.UwbCalibrator
import com.wf11.safealert.service.CalibrationEngine

class SafeAlertApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DevSettings.init(this)
        BeaconRegistry.init(this)
        UwbCalibrator.init(this)
        CalibrationEngine.init(this)
        FirebaseConfig.init()
    }
}
