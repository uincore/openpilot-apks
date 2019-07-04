package ai.comma.plus.frame

import android.app.Activity
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.TextView
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.LinearLayout
import android.graphics.drawable.Drawable
import android.support.v4.content.ContextCompat

import android.app.ActivityView;
import android.content.*
import android.net.ConnectivityManager
import android.net.Uri
import android.util.Log

import org.zeromq.ZMQ
import org.capnproto.MessageReader
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels

import org.capnproto.Serialize;
import ai.comma.openpilot.cereal.Log as CLog
import ai.comma.openpilot.cereal.Log.ControlsState.OpenpilotState
import android.net.wifi.WifiManager
import android.net.NetworkInfo
import android.os.*
import android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import android.view.MotionEvent
import com.android.internal.telephony.TelephonyIntents
import com.crashlytics.android.Crashlytics
import io.fabric.sdk.android.Fabric


class MainActivity : Activity(), NewDestinationReceiverDelegate, OffroadNavigationReceiverDelegate, UiLayoutReceiverDelegate, ActivityOverlayManagerDelegate {
    private val IMAGE_ALPHA_SELECTED = 255
    private val IMAGE_ALPHA_UNSELECTED = 177
    private val FRAME_SOCKET_ADDR = "tcp://127.0.0.1:8037"
    private val UILAYOUT_SOCKET_ADDR = "tcp://127.0.0.1:8060"

    val OFFROAD_APP = "ai.comma.plus.offroad/.MainActivity"
    val BLACK_APP = "ai.comma.plus.black/.MainActivity"
    val ONROAD_APP = "ai.comma.plus.onroad/.MainActivity"
    val IS_MAP_ENABLED: Boolean = false
    val DRIVING_APP = if (IS_MAP_ENABLED) ONROAD_APP else BLACK_APP
    val IS_RESPONSIVE: Boolean = true

    enum class STATE {
        HOME,
        SETTINGS,
    }

    var state: STATE = STATE.HOME
    var isPassive: Boolean = false

    var controlState: OpenpilotState? = null

    var frame: View? = null
    var sidebarIndicators: LinearLayout? = null
    var sidebarMetrics: LinearLayout? = null
    var sidebarLayout: LinearLayout? = null
    var sidebarMetricPanda: TextView? = null
    var sidebarMetricPandaEdge: ImageView? = null
    var sidebarMetricPandaBorder: LinearLayout? = null
    var sidebarMetricStorage: TextView? = null
    var sidebarMetricStorageEdge: ImageView? = null
    var sidebarMetricStorageBorder: RelativeLayout? = null
    var sidebarMetricTemp: TextView? = null
    var sidebarMetricTempEdge: ImageView? = null
    var sidebarMetricTempBorder: RelativeLayout? = null
    var sidebarCollapsed: Boolean = false
    var signalStrengthView: ImageView? = null
    var networkTypeText: TextView? = null
    var batteryLevelView: ImageView? = null

    var ctx: Context? = null
    var activityView: ActivityView? = null
    var activityViewLoaded: Boolean = false
    var activityOverlayManager: ActivityOverlayManager? = null
    var activityTouchGate: RelativeLayout? = null
    var settingsButton: ImageView? = null
    var homeButton: ImageView? = null
    var useMetric: Boolean = false

    var zmqCtx: org.zeromq.ZMQ.Context? = null
    var frameSock: org.zeromq.ZMQ.Socket? = null
    var statusSock: org.zeromq.ZMQ.Socket? = null
    var ubloxGnssSock: org.zeromq.ZMQ.Socket? = null
    var ubloxGnssPoller: org.zeromq.ZMQ.Poller? = null
    var controlStateSock: org.zeromq.ZMQ.Socket? = null
    var uiLayoutSock: org.zeromq.ZMQ.Socket? = null

    var newDestinationReceiver: NewDestinationReceiver? = null
    var offroadNavReceiver: OffroadNavigationReceiver? = null
    var uiLayoutReceiver: UiLayoutReceiver? = null

    var thermalWarningManager: ActivityOverlayManager? = null
    var networkMonitor: NetworkMonitor? = null
    var batteryMonitor: BatteryMonitor? = null
    var pandaConnectionMonitor: PandaConnectionMonitor? = null
    var lastStarted: Boolean = false
    var satelliteCount: Int = -1
    var statusThreadHandle: Thread? = null
    var controlsThreadHandle: Thread? = null
    var ubloxGnssThreadHandle: Thread? = null

    var simState: String? = null
    var lastNetworkType: String? = null

    // Colors
    var colorGreen: Int? = null;
    var colorWhite: Int? = null;
    var colorYellow: Int? = null;
    var colorRed: Int? = null;

    // Drawables
    var iconBattery: Drawable? = null;
    var iconNetwork0: Drawable? = null;
    var iconNetwork1: Drawable? = null;
    var iconNetwork2: Drawable? = null;
    var iconNetwork3: Drawable? = null;
    var iconNetwork4: Drawable? = null;
    var iconNetwork5: Drawable? = null;
    var borderEmpty: Drawable? = null;
    var borderFill: Drawable? = null;
    var gradientBlue: Drawable? = null;

    fun isNavItemSelected(item: ImageView): Boolean = item.imageAlpha == IMAGE_ALPHA_SELECTED

    fun selectNavItem(item: ImageView) {
        val scaleButtonUp = AnimationUtils.loadAnimation(this, R.anim.button_scale_up) as Animation
        item.startAnimation(scaleButtonUp)
        item.setImageAlpha(IMAGE_ALPHA_SELECTED)
    }

    fun deselectNavItem(item: ImageView) {
        if (isNavItemSelected(item)) {
            val scaleButtonDown = AnimationUtils.loadAnimation(this, R.anim.button_scale_down) as Animation
            item.startAnimation(scaleButtonDown)
            item.setImageAlpha(IMAGE_ALPHA_UNSELECTED)
        }
    }

    fun startInnerActivity(name: String, uri: Uri? = null, isLaunch: Boolean = true): Boolean {
        activityOverlayManager!!.hide()

        val intent = Intent(Intent.ACTION_MAIN)
        intent.setComponent(ComponentName.unflattenFromString(name))
        if (isLaunch) {
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
        }

        if (uri != null) {
            intent.setData(uri)
        }

        if (activityViewLoaded) {
            try {
                activityView!!.startActivity(intent)
                return true
            } catch(e: IllegalArgumentException) {
                CloudLog.exception("startInnerActivity failed", e)
                // invalid stackId
            } catch(e: OperationCanceledException) {
                CloudLog.exception("startInnerActivity failed", e)
                // from ActivityManager. "an error where the start had to be canceled.
            } catch(e: IllegalStateException) {
                CloudLog.exception("startInnerActivity failed", e)
                // Surface not yet created.
            }
        }

        return false
    }

    fun broadcastHomePress() {
        val pressIntent = Intent()
        pressIntent.action = "ai.comma.plus.HomeButtonTouchUpInside"
        sendBroadcast(pressIntent)
    }

    fun setAndSendState(state: STATE) {
        this.state = state
        val buf = ByteBuffer.allocate(1)
        buf.put(state.ordinal.toByte())
        frameSock!!.send(buf.array())
    }

    fun updateUiLayoutState(sidebarCollapsed: Boolean,
                            mapEnabled: Boolean): LogEvent {
        val log = LogEvent()
        val uiLayout = log.root.initUiLayoutState()
        uiLayout.setSidebarCollapsed(sidebarCollapsed && IS_RESPONSIVE)
        uiLayout.setMapEnabled(mapEnabled)

        val out = ByteArrayOutputStream()
        Serialize.write(Channels.newChannel(out), log.msg)
        val bytes = out.toByteArray()

        uiLayoutSock!!.send(bytes)

        return log
    }

    fun statusThread() {
        Log.w("frame", "statusThread")
        while (true) {
            val msg = statusSock!!.recv()
            val msgbuf = ByteBuffer.wrap(msg)
            var reader: MessageReader
            try {
                reader = Serialize.read(msgbuf)
            } catch (e: IOException) {
                Log.e("frame", "read")
                continue
            }

            val log = reader.getRoot(CLog.Event.factory)
            assert(log.isThermal)

            val started = log.thermal.started
            if (started != lastStarted) {
                if (started) {
                    runOnUiThread {
                        collapseSidebar()
                        frame?.setBackgroundColor(resources.getColor(R.color.drivingBlue, null))
                        frame?.background = null
                        synchronized(this) {
                            enterHomeState()
                            if (state == STATE.HOME) {
                                startInnerActivity(DRIVING_APP)
                                if (!IS_MAP_ENABLED) {
                                    hideActivityView()
                                }
                            }
                        }
                    }
                } else {
                    runOnUiThread {
                        expandSidebar()
                        frame?.background = gradientBlue
                        if (state == STATE.HOME) {
                            broadcastHomePress()
                            startInnerActivity(OFFROAD_APP)
                        }
                        activityOverlayManager!!.show(ActivityOverlayManager.OVERLAY_THERMAL_WARNING)
                        hideActivityView()
                    }
                }

                lastStarted = started
            }

            // Always update sidebar metrics
            runOnUiThread {
                updateSidebarMetrics(
                  log.thermal.freeSpace,
                  log.thermal.bat,
                  log.thermal.thermalStatus.toString());
            }

            
        }
    }

    fun ubloxGnssThread() {
        Log.w("frame", "ubloxGnssThread")
        while (true) {
            val recv = ubloxGnssPoller!!.poll(5000)
            if (recv == 0) {
                // no message from socket in last 5s
                satelliteCount = -1
                runOnUiThread {
                    updatePandaConnectionStatus()
                }
                continue
            }

            val msg = ubloxGnssSock!!.recv()
            val msgbuf = ByteBuffer.wrap(msg)
            var reader: MessageReader
            try {
                reader = Serialize.read(msgbuf)
            } catch (e: IOException) {
                Log.e("frame", "read")
                continue
            }

            val log = reader.getRoot(CLog.Event.factory)
            assert(log.isUbloxGnss)

            if (log.ubloxGnss.isMeasurementReport) {
                satelliteCount = log.ubloxGnss.measurementReport.numMeas.toInt()

                runOnUiThread {
                    updatePandaConnectionStatus()
                }
            }
        }
    }

    fun updatePandaConnectionStatus() {
        Log.i("frame", satelliteCount.toString())
        if (pandaConnectionMonitor?.isConnected == false) {
            sidebarMetricPanda?.text = "无CAN" //NO PANDA
            sidebarMetricPandaEdge?.setColorFilter(colorRed!!);
            sidebarMetricPandaBorder!!.getBackground().setAlpha(255)
        } else {
            if (satelliteCount == -1) {
              sidebarMetricPanda?.text = "CAN已链接" //CAN ACTIVE
              sidebarMetricPandaEdge?.setColorFilter(colorWhite!!);
              sidebarMetricPandaBorder!!.getBackground().setAlpha(76);
            } else if (satelliteCount < 6) {
              sidebarMetricPanda?.text = "CAN\n无GPS" //CAN \n NO GPS
              sidebarMetricPandaEdge?.setColorFilter(colorYellow!!);
              sidebarMetricPandaBorder!!.getBackground().setAlpha(255);
            } else if (satelliteCount >= 6) {
              sidebarMetricPanda?.text = "就绪" //PANDA GOOD GPS
              sidebarMetricPandaEdge?.setColorFilter(colorWhite!!);
              sidebarMetricPandaBorder!!.getBackground().setAlpha(76);
            }
        }
    }

    fun updateSidebarMetrics(freeSpace: Float, batteryTemp: Int, thermalStatus: String) {
      // Storage
      var storagePct = (1.0-freeSpace)*100;
      sidebarMetricStorage?.text = String.format("%.0f", storagePct).plus("%");
      if (storagePct < 75.0) {
        sidebarMetricStorageEdge?.setColorFilter(colorWhite!!);
        sidebarMetricStorageBorder!!.getBackground().setAlpha(76)
      } else if (storagePct >= 75.0 && storagePct < 87.0) {
        sidebarMetricStorageEdge?.setColorFilter(colorYellow!!);
        sidebarMetricStorageBorder!!.getBackground().setAlpha(255)
      } else if (storagePct >= 87.0) {
        sidebarMetricStorageEdge?.setColorFilter(colorRed!!);
        sidebarMetricStorageBorder!!.getBackground().setAlpha(255)
      }

      // Temperature
      var batteryTempC = batteryTemp/1000;
      sidebarMetricTemp?.text = Integer.toString(batteryTempC).plus("°C");
      if (thermalStatus==="GREEN") {
        sidebarMetricTempEdge?.setColorFilter(colorWhite!!);
        sidebarMetricTempBorder?.background = borderEmpty;
        sidebarMetricTempBorder!!.getBackground().setAlpha(76);
      } else if (thermalStatus==="YELLOW") {
        sidebarMetricTempEdge?.setColorFilter(colorYellow!!);
        sidebarMetricTempBorder?.background = borderEmpty;
        sidebarMetricTempBorder!!.getBackground().setAlpha(255);
      } else if (thermalStatus==="RED") {
        sidebarMetricTempEdge?.setColorFilter(colorRed!!);
        sidebarMetricTempBorder?.background = borderEmpty;
        sidebarMetricTempBorder!!.getBackground().setAlpha(255);
      } else if (thermalStatus==="DANGER") {
        sidebarMetricTempEdge?.setColorFilter(colorRed!!);
        sidebarMetricTempBorder?.background = borderFill;
        sidebarMetricTempBorder!!.getBackground().setTint(colorRed!!);
        sidebarMetricTempBorder!!.getBackground().setAlpha(255);
      }
    }

    /* fun controlsThread() {
        Log.w("frame", "controlsThread")
        var recv = 0

        while (true) {
            val msg = controlStateSock!!.recv()
            recv += 1

            if (!lastStarted || recv % 100 != 0) {
                continue
            }

            val msgbuf = ByteBuffer.wrap(msg)
            var reader: MessageReader
            try {
                reader = Serialize.read(msgbuf)
            } catch (e: IOException) {
                Log.e("frame", "read")
                continue
            }

            val log = reader.getRoot(CLog.Event.factory)
            assert(log.isControlsState)
        }
    } */

    fun enterHomeState() {
        var startApp = OFFROAD_APP
        if (lastStarted) {
            startApp = DRIVING_APP
            collapseSidebar()
        }

        if (startInnerActivity(startApp)) {
            deselectNavItem(settingsButton!!)
            setAndSendState(STATE.HOME)
        }
    }

    override fun uiLayoutOnEngagedMocked() {
        frame?.setBackgroundColor(colorGreen!!)
    }

    override fun uiLayoutOnEngagedUnmocked() {
        frame?.background = gradientBlue;
    }

    fun collapseSidebar() {
        if (!sidebarCollapsed && IS_RESPONSIVE) {
            frame!!.setPadding(60,0,0,0)
            val sidebarLayoutParams = sidebarLayout!!.getLayoutParams() as LinearLayout.LayoutParams
            sidebarLayoutParams.leftMargin = -240
            sidebarLayout?.setLayoutParams(sidebarLayoutParams)
            sidebarIndicators?.visibility = View.GONE
            sidebarMetrics?.visibility = View.GONE
            sidebarCollapsed = true
            updateUiLayoutState(true, IS_MAP_ENABLED)
        }
    }

    fun expandSidebar() {
        if (sidebarCollapsed && IS_RESPONSIVE) {
            frame!!.setPadding(300,0,0,0)
            val sidebarLayoutParams = sidebarLayout!!.getLayoutParams() as LinearLayout.LayoutParams
            sidebarLayoutParams.leftMargin = 0
            sidebarLayout?.setLayoutParams(sidebarLayoutParams)
            sidebarIndicators?.visibility = View.VISIBLE
            sidebarMetrics?.visibility = View.VISIBLE
            sidebarCollapsed = false
            updateUiLayoutState(false, IS_MAP_ENABLED)
        }
    }

    fun hideActivityView() {
        activityView?.visibility = View.INVISIBLE
    }

    fun showActivityView() {
        activityView?.visibility = View.VISIBLE
    }

    override fun onNewDestination() {
        /* startNav() */
    }

    override fun uiLayoutOnSidebarExpanded() {
        expandSidebar()
    }

    override fun uiLayoutOnSidebarCollapsed() {
        collapseSidebar()
    }

    override fun onSettingsOpenedFromOffroad() {
        setSettingsState()
        openSettings()
    }

    override fun uiLayoutOnShowStartCar() {
        hideActivityView()
        activityOverlayManager!!.show(ActivityOverlayManager.OVERLAY_START_CAR)
    }

    override fun onActivityOverlayDismissed() {
        showActivityView()
    }

    override fun onSettingsClosedFromOffroad() {
        if (lastStarted) {
            hideActivityView()
        }
        enterHomeState()
    }

    fun openSettingsInOffroad() {
        val pressIntent = Intent()
        pressIntent.action = "ai.comma.plus.SidebarSettingsTouchUpInside"
        sendBroadcast(pressIntent)
    }

    fun setSettingsState() {
        selectNavItem(settingsButton!!)

        setAndSendState(STATE.SETTINGS)
    }

    fun openSettings() {
        if (state == STATE.SETTINGS) {
            return
        }

        setSettingsState()

        openSettingsInOffroad()

        startInnerActivity(OFFROAD_APP, isLaunch = false)
        Handler().postDelayed({
            activityView?.visibility = View.VISIBLE
        }, 600)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);
        if (!BuildConfig.DEBUG) {
            Fabric.with(this, Crashlytics());
        }
        setContentView(R.layout.activity_main);

        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().permitAll().build())
        CloudLog.init(applicationContext)

        isPassive = ChffrPlusParams.readParam("Passive") == "1"
        zmqCtx = ZMQ.context(1)

        frameSock = zmqCtx!!.socket(ZMQ.PUB)
        frameSock!!.bind(FRAME_SOCKET_ADDR)

        uiLayoutSock = zmqCtx!!.socket(ZMQ.PUB)
        uiLayoutSock!!.bind(UILAYOUT_SOCKET_ADDR)

        statusSock = zmqCtx!!.socket(ZMQ.SUB)
        statusSock!!.connect("tcp://127.0.0.1:8005")
        statusSock!!.subscribe("")

        ubloxGnssSock = zmqCtx!!.socket(ZMQ.SUB)
        ubloxGnssSock!!.connect("tcp://127.0.0.1:8033")
        ubloxGnssSock!!.subscribe("")
        ubloxGnssPoller = zmqCtx!!.poller(1)
        ubloxGnssPoller!!.register(ubloxGnssSock, zmq.ZMQ.ZMQ_POLLIN)

        // Layouts
        frame = findViewById(R.id.frame) as RelativeLayout
        sidebarIndicators = findViewById(R.id.sidebarIndicators) as LinearLayout
        sidebarMetrics = findViewById(R.id.sidebarMetrics) as LinearLayout
        sidebarLayout = findViewById(R.id.sidebarInclude) as LinearLayout
        sidebarMetricPanda = findViewById(R.id.sidebarMetricPandaValue) as TextView
        sidebarMetricPandaEdge = findViewById(R.id.sidebarMetricPandaEdge) as ImageView
        sidebarMetricPandaBorder = findViewById(R.id.sidebarMetricPandaBorder) as LinearLayout
        sidebarMetricStorage = findViewById(R.id.sidebarMetricStorageValue) as TextView
        sidebarMetricStorageEdge = findViewById(R.id.sidebarMetricStorageEdge) as ImageView
        sidebarMetricStorageBorder = findViewById(R.id.sidebarMetricStorageBorder) as RelativeLayout
        sidebarMetricTemp = findViewById(R.id.sidebarMetricTempValue) as TextView
        sidebarMetricTempEdge = findViewById(R.id.sidebarMetricTempEdge) as ImageView
        sidebarMetricTempBorder = findViewById(R.id.sidebarMetricTempBorder) as RelativeLayout
        signalStrengthView = findViewById(R.id.indicator_network_signal) as ImageView
        networkTypeText = findViewById(R.id.network_type) as TextView
        batteryLevelView = findViewById(R.id.indicator_battery_icon) as ImageView
        activityView = findViewById(R.id.activity_view) as ActivityView
        activityOverlayManager = ActivityOverlayManager(findViewById(R.id.activity_mock), this)
        activityTouchGate = findViewById(R.id.activity_touch_gate) as RelativeLayout
        ctx = activityView!!.getContext() as Context

        // Colors
        colorGreen = ContextCompat.getColor(ctx, R.color.engagedGreen);
        colorWhite = ContextCompat.getColor(ctx, R.color.colorWhite);
        colorYellow = ContextCompat.getColor(ctx, R.color.warningYellow);
        colorRed = ContextCompat.getColor(ctx, R.color.dangerRed);

        // Drawables
        gradientBlue = ContextCompat.getDrawable(ctx, R.drawable.gradient_blue);
        iconNetwork0 = ContextCompat.getDrawable(ctx, R.drawable.ic_network_0);
        iconNetwork1 = ContextCompat.getDrawable(ctx, R.drawable.ic_network_1);
        iconNetwork2 = ContextCompat.getDrawable(ctx, R.drawable.ic_network_2);
        iconNetwork3 = ContextCompat.getDrawable(ctx, R.drawable.ic_network_3);
        iconNetwork4 = ContextCompat.getDrawable(ctx, R.drawable.ic_network_4);
        iconNetwork5 = ContextCompat.getDrawable(ctx, R.drawable.ic_network_5);
        borderEmpty = ContextCompat.getDrawable(ctx, R.drawable.border_white);
        borderFill = ContextCompat.getDrawable(ctx, R.drawable.border_white_fill);

        // Navigation Buttons
        homeButton = findViewById(R.id.nav_icon_home) as ImageView
        settingsButton = findViewById(R.id.nav_icon_settings) as ImageView

        // Prepare Buttons
        deselectNavItem(settingsButton!!)

        activityTouchGate!!.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_UP -> {
                    if (lastStarted && state == STATE.HOME) {
                        if (sidebarCollapsed) {
                            expandSidebar()
                        } else {
                            collapseSidebar()
                        }
                    }
                }
            }

            return@setOnTouchListener true
        }

        homeButton!!.setOnClickListener {
            if (!lastStarted) {
                broadcastHomePress()
                showActivityView()
            } else if (lastStarted && !IS_MAP_ENABLED) {
                hideActivityView()
            }
            enterHomeState()
        }

        settingsButton!!.setOnClickListener {
            openSettings()
        }

        activityView?.setCallback(object : ActivityView.ActivityViewCallback() {
            override fun onSurfaceAvailable(view: ActivityView) {
                synchronized(this@MainActivity) {
                    activityViewLoaded = true
                    startInnerActivity(OFFROAD_APP)
                }

                val isOnUiThread = Looper.myLooper() == Looper.getMainLooper()

                Log.w("plusframe", "AV onSurfaceAvailable isOnUiThread=${isOnUiThread}")
            }

            override fun onAllActivitiesComplete(view: ActivityView) {
                Log.w("plusframe", "AV onAllActivitiesComplete")
            }

            override fun onSurfaceDestroyed(view: ActivityView) {
                synchronized(this@MainActivity) {
                    activityViewLoaded = false
                }
                Log.w("plusframe", "AV onSurfaceDestroyed")
            }
        })

        newDestinationReceiver = NewDestinationReceiver(this)
        registerReceiver(newDestinationReceiver, NewDestinationReceiver.newDestIntentFilter)

        offroadNavReceiver = OffroadNavigationReceiver(this)
        registerReceiver(offroadNavReceiver, OffroadNavigationReceiver.offroadNavIntentFilter)
        networkMonitor = NetworkMonitor()
        val networkIntents = IntentFilter()
        networkIntents.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        networkIntents.addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        networkIntents.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED)

        registerReceiver(networkMonitor, networkIntents)

        uiLayoutReceiver = UiLayoutReceiver(this)
        registerReceiver(uiLayoutReceiver, UiLayoutReceiver.uiLayoutIntentFilter)

        batteryMonitor = BatteryMonitor()
        val batteryIntentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryMonitor, batteryIntentFilter)
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val status = if (batteryManager.isCharging) BatteryManager.BATTERY_STATUS_CHARGING else BatteryManager.BATTERY_STATUS_NOT_CHARGING
        onBatteryChange(batteryManager.getIntProperty(BATTERY_PROPERTY_CAPACITY), 100, status)

        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        telephonyManager.listen(CellStateListener(), PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or PhoneStateListener.LISTEN_SERVICE_STATE)

        pandaConnectionMonitor = PandaConnectionMonitor(object : PandaConnectionMonitorDelegate {
            override fun pandaConnectionChanged(isConnected: Boolean) {
                runOnUiThread {
                    updatePandaConnectionStatus()
                }
            }
        })

        statusThreadHandle = Thread(Runnable {
            statusThread()
        })
        statusThreadHandle!!.start()

        /* if (!isPassive) {
            controlsThreadHandle = Thread(Runnable {
                controlsThread()
            })
            controlsThreadHandle!!.start()
        } */

        ubloxGnssThreadHandle = Thread(Runnable {
          ubloxGnssThread()
        })
        ubloxGnssThreadHandle!!.start()

        updateUiLayoutState(false, IS_MAP_ENABLED)

        setAndSendState(STATE.HOME)
    }

    override fun onDestroy() {
        unregisterReceiver(newDestinationReceiver)
        unregisterReceiver(networkMonitor)
        unregisterReceiver(batteryMonitor)
        frameSock?.unbind(FRAME_SOCKET_ADDR)
        frameSock?.close()
        uiLayoutSock?.unbind(UILAYOUT_SOCKET_ADDR)
        uiLayoutSock?.close()

        pandaConnectionMonitor?.destroy()

        super.onDestroy()
    }


    fun getNetworkType(): String {
        val telManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val networkType = when (telManager.networkType) {
            TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_1xRTT, TelephonyManager.NETWORK_TYPE_IDEN ->
                "2G"
            TelephonyManager.NETWORK_TYPE_EVDO_0, TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_EVDO_B, TelephonyManager.NETWORK_TYPE_EHRPD, TelephonyManager.NETWORK_TYPE_HSPAP ->
                "3G"
            TelephonyManager.NETWORK_TYPE_LTE ->
                "LTE"
            else -> {
                if (simState == "ABSENT") {
                    "No SIM"
                } else {
                    CloudLog.log("No service detected. networkType ${telManager.networkType} lastNetworkType ${lastNetworkType}")
                    if (lastNetworkType != null) lastNetworkType!! else null
                }
            }
        }

        if (networkType != null) {
            lastNetworkType = networkType
            return networkType
        } else {
            return "--"
        }
    }

    fun isWifiConnected(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI && cm.activeNetworkInfo?.isConnected ?: false
    }

    fun onNetworkStateChange() {
        return onNetworkStateChange(isWifiConnected())
    }

    fun onNetworkStateChange(isWifiConnected: Boolean) {
        val networkType = if (isWifiConnected) "WiFi" else getNetworkType()
        networkTypeText?.text = networkType
    }

    fun onBatteryChange(level: Int, scale: Int, status: Int) {
        val pct = 100 * (level / (scale * 1.0))

        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val batteryPctRound = if (pct > 0) (Math.ceil(pct / 25) * 25).toInt() else 0

        val suffix = if (isCharging) "_charging" else ""
        val iconId = resources.getIdentifier("indicator_battery_${batteryPctRound}${suffix}", "drawable", packageName)
        val iconBattery = resources.getDrawable(iconId, null);
        batteryLevelView?.setImageDrawable(iconBattery);
    }

    inner class NetworkMonitor : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)

                    onNetworkStateChange(networkInfo.isConnected)
                }

                ConnectivityManager.CONNECTIVITY_ACTION -> {
                    onNetworkStateChange()
                }

                TelephonyIntents.ACTION_SIM_STATE_CHANGED -> {
                    simState = intent.getStringExtra("ss")

                    onNetworkStateChange()
                }
            }
        }
    }

    inner class BatteryMonitor : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    onBatteryChange(level, scale, status)
                }
            }
        }
    }

    inner class CellStateListener : PhoneStateListener() {
        var lastServiceState: ServiceState? = null
        override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
            super.onSignalStrengthsChanged(signalStrength)

            if (lastServiceState != null && lastServiceState?.state != ServiceState.STATE_IN_SERVICE) {
                signalStrengthView?.setImageDrawable(iconNetwork0)
                return
            }

            when (signalStrength?.level) {
                0 -> signalStrengthView?.setImageDrawable(iconNetwork1)
                1 -> signalStrengthView?.setImageDrawable(iconNetwork2)
                2 -> signalStrengthView?.setImageDrawable(iconNetwork3)
                3 -> signalStrengthView?.setImageDrawable(iconNetwork4)
                4 -> signalStrengthView?.setImageDrawable(iconNetwork5)
                else -> {
                    signalStrengthView?.setImageDrawable(iconNetwork0)
                }
            }

        }

        override fun onServiceStateChanged(serviceState: ServiceState?) {
            super.onServiceStateChanged(serviceState)

            if (serviceState?.state != ServiceState.STATE_IN_SERVICE) {
                signalStrengthView?.setImageDrawable(iconNetwork0)
            }

            onNetworkStateChange()
            lastServiceState = serviceState
        }
    }
}
