package com.example.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.example.data.model.BatteryInfo
import com.example.data.model.PlugType

object BatteryHelper {

    fun getBatteryInfo(context: Context): BatteryInfo {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryIntent: Intent? = context.registerReceiver(null, intentFilter)

        if (batteryIntent == null) {
            return BatteryInfo(
                isPlugged = false,
                percent = 50,
                temperatureCelsius = 25.0f,
                voltageMv = 3800,
                plugType = PlugType.NONE,
                health = "Нормальний"
            )
        }

        val plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val isPlugged = plugged == BatteryManager.BATTERY_PLUGGED_AC ||
                plugged == BatteryManager.BATTERY_PLUGGED_USB ||
                plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS

        val plugType = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> PlugType.AC
            BatteryManager.BATTERY_PLUGGED_USB -> PlugType.USB
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> PlugType.WIRELESS
            else -> PlugType.NONE
        }

        val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) {
            (level * 100f / scale).toInt().coerceIn(0, 100)
        } else {
            0
        }

        val rawTemp = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        val tempCelsius = if (rawTemp > 0) rawTemp / 10.0f else 25.0f

        val voltageMv = batteryIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)

        val healthCode = batteryIntent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
        val health = when (healthCode) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Добре"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Перегрів"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Вичерпано"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Перенапруга"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Збій"
            BatteryManager.BATTERY_HEALTH_COLD -> "Холод"
            else -> "Норма"
        }

        return BatteryInfo(
            isPlugged = isPlugged,
            percent = percent,
            temperatureCelsius = tempCelsius,
            voltageMv = voltageMv,
            plugType = plugType,
            health = health
        )
    }
}
