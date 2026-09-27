package com.yogitha.dobby

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat

class FloatingDobbyService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var dobbyIcon: ImageView? = null
    
    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    
    private var isDragging = false
    private var lastActionTime: Long = 0
    
    companion object {
        const val CHANNEL_ID = "dobby_floating_channel"
        const val NOTIFICATION_ID = 1001
        
        const val ACTION_ACTIVATE = "com.yogitha.dobby.ACTIVATE"
        const val ACTION_STOP = "com.yogitha.dobby.STOP_FLOATING"
        
        private var instance: FloatingDobbyService? = null
        
        fun getInstance(): FloatingDobbyService? = instance
        
        fun isRunning(): Boolean = instance != null
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())
    private var voiceInputManager: VoiceInputManager? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        createFloatingView()
        
        voiceInputManager = VoiceInputManager(
            context = this,
            scope = scope,
            onPhaseChanged = { phase ->
                // Update icon on the main thread
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    updateIconState(phase)
                }
            }
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceInputManager?.destroy()
        removeFloatingView()
        instance = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Dobby Floating Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Dobby floating icon visible"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Dobby is ready")
            .setContentText("Tap the floating Dobby icon to speak")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createFloatingView() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_dobby, null)
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }

        dobbyIcon = floatingView?.findViewById(R.id.dobby_icon)
        
        dobbyIcon?.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    lastActionTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY
                    
                    if (kotlin.math.abs(deltaX) > 10 || kotlin.math.abs(deltaY) > 10) {
                        isDragging = true
                        params.x = initialX + deltaX.toInt()
                        params.y = initialY + deltaY.toInt()
                        windowManager?.updateViewLayout(floatingView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val timeDiff = System.currentTimeMillis() - lastActionTime
                    if (!isDragging && timeDiff < 300) {
                        // Tap detected - activate Dobby
                        activateDobby()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(floatingView, params)
    }

    private fun removeFloatingView() {
        floatingView?.let {
            windowManager?.removeView(it)
        }
        floatingView = null
        dobbyIcon = null
    }

    private fun activateDobby() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            voiceInputManager?.startListening()
        }
    }
    
    fun updateIconState(phase: DobbyPhase) {
        dobbyIcon?.let { icon ->
            val drawableRes = when (phase) {
                DobbyPhase.LISTENING -> R.drawable.dobby_listening
                DobbyPhase.THINKING -> R.drawable.dobby_thinking
                DobbyPhase.ACTING -> R.drawable.dobby_acting
                DobbyPhase.SPEAKING -> R.drawable.dobby_speaking
                DobbyPhase.DONE -> R.drawable.dobby_idle
                DobbyPhase.ERROR -> R.drawable.dobby_error
                DobbyPhase.TEACHING -> R.drawable.dobby_teaching
            }
            icon.setImageResource(drawableRes)
            icon.clearColorFilter()
            icon.alpha = 1.0f
        }
    }
}
