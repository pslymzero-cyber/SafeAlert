package com.wf11.safealert.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.wf11.safealert.service.BleService
import com.wf11.safealert.ui.adapter.WalkerListAdapter
import com.wf11.safealert.databinding.ActivityDeviceBinding

class DeviceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceBinding
    private lateinit var adapter: WalkerListAdapter
    private var deviceId: String = ""

    private val dangerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            binding.cardStatus.setBackgroundColor(getColor(android.R.color.holo_red_light))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceId = intent.getStringExtra("id") ?: "DEVICE_001"
        binding.tvDeviceId.text = "장비 ID: $deviceId"
        binding.tvStatus.text = "광고 송출 중"

        adapter = WalkerListAdapter()
        binding.rvWalkers.layoutManager = LinearLayoutManager(this)
        binding.rvWalkers.adapter = adapter

        startService(Intent(this, BleService::class.java).apply {
            action = BleService.ACTION_START_DEVICE
            putExtra(BleService.EXTRA_ID, deviceId)
        })

        binding.btnStop.setOnClickListener { finish() }

        registerReceiver(dangerReceiver, IntentFilter(BleService.BROADCAST_ALERT),
            RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(dangerReceiver)
        startService(Intent(this, BleService::class.java).apply { action = BleService.ACTION_STOP })
    }
}
