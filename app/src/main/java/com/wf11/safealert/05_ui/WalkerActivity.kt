package com.wf11.safealert.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.wf11.safealert.service.BleService
import com.wf11.safealert.firebase.FirebaseManager
import com.wf11.safealert.ui.adapter.DeviceListAdapter
import com.wf11.safealert.databinding.ActivityWalkerBinding

class WalkerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWalkerBinding
    private lateinit var adapter: DeviceListAdapter
    private var walkerId: String = ""

    private val dangerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            binding.tvAlertLevel.text = "위험"
            binding.tvAlertLevel.setBackgroundColor(getColor(android.R.color.holo_red_light))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWalkerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        walkerId = intent.getStringExtra("id") ?: "WALKER_001"
        binding.tvWalkerId.text = "태그 ID: $walkerId"
        binding.tvAlertLevel.text = "안전"
        binding.tvAlertLevel.setBackgroundColor(getColor(android.R.color.holo_green_light))

        adapter = DeviceListAdapter()
        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDevices.adapter = adapter

        startService(Intent(this, BleService::class.java).apply {
            action = BleService.ACTION_START_WALKER
            putExtra(BleService.EXTRA_ID, walkerId)
        })

        FirebaseManager.getTodayAlertCount { count ->
            runOnUiThread { binding.tvAlertCount.text = "오늘 경보: $count 회" }
        }

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
