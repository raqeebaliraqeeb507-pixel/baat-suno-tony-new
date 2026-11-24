package com.baatsunotony.assistant.hardware

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.baatsunotony.assistant.state.AssistantStateBus

class FlashlightController(context: Context) {

    private val cameraManager = context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    fun setFlashlight(enabled: Boolean): Boolean {
        return try {
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false

            cameraManager.setTorchMode(cameraId, enabled)
            AssistantStateBus.setFlashlightOn(enabled)
            AssistantStateBus.addActionLog(
                if (enabled) "FLASHLIGHT_ON" else "FLASHLIGHT_OFF",
                if (enabled) "Turned flashlight on" else "Turned flashlight off",
                true
            )
            true
        } catch (e: Exception) {
            AssistantStateBus.addActionLog(
                "FLASHLIGHT",
                "Flashlight error: ${e.localizedMessage ?: "unavailable"}",
                false
            )
            false
        }
    }
}
