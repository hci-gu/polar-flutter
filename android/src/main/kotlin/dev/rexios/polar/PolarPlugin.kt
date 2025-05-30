package dev.rexios.polar

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle.Event
import androidx.lifecycle.LifecycleEventObserver
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.polar.androidcommunications.api.ble.model.DisInfo
import com.polar.androidcommunications.api.ble.model.gatt.client.ChargeState
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApi.PolarBleSdkFeature
import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType
import com.polar.sdk.api.PolarBleApiCallbackProvider
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.PolarH10OfflineExerciseApi.RecordingInterval
import com.polar.sdk.api.PolarH10OfflineExerciseApi.SampleType
import com.polar.sdk.api.model.LedConfig

import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarExerciseEntry
import com.polar.sdk.api.model.PolarHealthThermometerData
import com.polar.sdk.api.model.PolarHrData
import com.polar.sdk.api.model.PolarSensorSetting
import com.polar.sdk.api.model.PolarOfflineRecordingEntry
import com.polar.sdk.api.model.PolarOfflineRecordingTrigger
import com.polar.sdk.api.model.PolarOfflineRecordingTriggerMode
import com.polar.sdk.api.model.PolarOfflineRecordingData

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.embedding.engine.plugins.lifecycle.FlutterLifecycleAdapter
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.EventChannel.EventSink
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.reactivex.rxjava3.disposables.Disposable
import java.lang.reflect.Type
import java.util.Date
import java.util.UUID

fun Any?.discard() = Unit

object DateSerializer : JsonDeserializer<Date>, JsonSerializer<Date> {
    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type?,  
        context: JsonDeserializationContext?,
    ): Date = Date(json?.asJsonPrimitive?.asLong ?: 0)

    override fun serialize(
        src: Date?,
        typeOfSrc: Type?,
        context: JsonSerializationContext?,
    ): JsonElement = JsonPrimitive(src?.time)
}

private fun runOnUiThread(runnable: () -> Unit) {
    Handler(Looper.getMainLooper()).post { runnable() }
}

private val gson = GsonBuilder().registerTypeAdapter(Date::class.java, DateSerializer).create()

private var wrapperInternal: PolarWrapper? = null
private val wrapper: PolarWrapper
    get() = wrapperInternal!!

/** PolarPlugin */
class PolarPlugin :
    FlutterPlugin,
    MethodCallHandler,
    EventChannel.StreamHandler,
    ActivityAware {
    // Binary messenger for dynamic EventChannel registration
    private lateinit var messenger: BinaryMessenger

    // Method channel
    private lateinit var methodChannel: MethodChannel

    // Event channel
    private lateinit var eventChannel: EventChannel

    // Search channel
    private lateinit var searchChannel: EventChannel

    // Context
    private lateinit var context: Context

    // Streaming channels
    private val streamingChannels = mutableMapOf<String, StreamingChannel>()

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        messenger = flutterPluginBinding.binaryMessenger

        methodChannel = MethodChannel(flutterPluginBinding.binaryMessenger, "polar/methods")
        methodChannel.setMethodCallHandler(this)

        eventChannel = EventChannel(flutterPluginBinding.binaryMessenger, "polar/events")
        eventChannel.setStreamHandler(this)

        searchChannel = EventChannel(flutterPluginBinding.binaryMessenger, "polar/search")
        searchChannel.setStreamHandler(searchHandler)

        context = flutterPluginBinding.applicationContext
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        searchChannel.setStreamHandler(null)
        streamingChannels.values.forEach { it.dispose() }
        shutDown()
    }

    private fun initApi() {
        if (wrapperInternal == null) {
            wrapperInternal = PolarWrapper(context)
        }
    }

    override fun onMethodCall(
        call: MethodCall,
        result: Result,
    ) {
        initApi()

        when (call.method) {
            "connectToDevice" -> {
                wrapper.api.connectToDevice(call.arguments as String)
                result.success(null)
            }

            "disconnectFromDevice" -> {
                wrapper.api.disconnectFromDevice(call.arguments as String)
                result.success(null)
            }

            "getAvailableOnlineStreamDataTypes" -> getAvailableOnlineStreamDataTypes(call, result)
            "requestStreamSettings" -> requestStreamSettings(call, result)
            "createStreamingChannel" -> createStreamingChannel(call, result)
            "startRecording" -> startRecording(call, result)
            "stopRecording" -> stopRecording(call, result)
            "requestRecordingStatus" -> requestRecordingStatus(call, result)
            "listExercises" -> listExercises(call, result)
            "fetchExercise" -> fetchExercise(call, result)
            "removeExercise" -> removeExercise(call, result)
            "setLedConfig" -> setLedConfig(call, result)
            "doFactoryReset" -> doFactoryReset(call, result)
            "enableSdkMode" -> enableSdkMode(call, result)
            "disableSdkMode" -> disableSdkMode(call, result)
            "isSdkModeEnabled" -> isSdkModeEnabled(call, result)

            "setOfflineRecordingTrigger" -> setOfflineRecordingTrigger(call, result)
             "requestOfflineRecordingSettings" -> requestOfflineRecordingSettings(call, result)
             "startOfflineRecording" -> startOfflineRecording(call, result)
             "stopOfflineRecording" -> stopOfflineRecording(call, result)
             "listOfflineRecordings" -> listOfflineRecordings(call, result)
             "fetchOfflineRecording" -> fetchOfflineRecording(call, result)
             "removeOfflineRecord" -> removeOfflineRecord(call, result)
             "getDiskSpace" -> getDiskSpace(call, result)


            else -> result.notImplemented()
        }
    }

    override fun onListen(
        arguments: Any?,
        events: EventSink,
    ) {
        initApi()
        wrapper.addSink(arguments as Int, events)
    }

    override fun onCancel(arguments: Any?) {
        wrapper.removeSink(arguments as Int)
    }

    private val searchHandler =
        object : EventChannel.StreamHandler {
            private var searchSubscription: Disposable? = null

            override fun onListen(
                arguments: Any?,
                events: EventSink,
            ) {
                initApi()

                searchSubscription =
                    wrapper.api.searchForDevice().subscribe({
                        runOnUiThread { events.success(gson.toJson(it)) }
                    }, {
                        runOnUiThread {
                            events.error(it.toString(), it.message, null)
                        }
                    }, {
                        runOnUiThread { events.endOfStream() }
                    })
            }

            override fun onCancel(arguments: Any?) {
                searchSubscription?.dispose()
            }
        }

    private fun createStreamingChannel(
        call: MethodCall,
        result: Result,
    ) {
        val arguments = call.arguments as List<*>
        val name = arguments[0] as String
        val identifier = arguments[1] as String
        val feature = gson.fromJson(arguments[2] as String, PolarDeviceDataType::class.java)

        if (streamingChannels[name] == null) {
            streamingChannels[name] =
                StreamingChannel(messenger, name, wrapper.api, identifier, feature)
        }

        result.success(null)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        val lifecycle = FlutterLifecycleAdapter.getActivityLifecycle(binding)
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Event.ON_RESUME -> wrapperInternal?.api?.foregroundEntered()
                    Event.ON_DESTROY -> shutDown()
                    else -> {}
                }
            },
        )
    }

    override fun onDetachedFromActivityForConfigChanges() {}

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {}

    override fun onDetachedFromActivity() {}

    private fun shutDown() {
        if (wrapperInternal == null) return
        wrapper.shutDown()
    }

    private fun getAvailableOnlineStreamDataTypes(
        call: MethodCall,
        result: Result,
    ) {
        val identifier = call.arguments as String

        wrapper.api
            .getAvailableOnlineStreamDataTypes(identifier)
            .subscribe({
                runOnUiThread { result.success(gson.toJson(it)) }
            }, {
                runOnUiThread {
                    result.error(it.toString(), it.message, null)
                }
            })
            .discard()
    }

    private fun requestStreamSettings(
        call: MethodCall,
        result: Result,
    ) {
        val arguments = call.arguments as List<*>
        val identifier = arguments[0] as String
        val feature = gson.fromJson(arguments[1] as String, PolarDeviceDataType::class.java)

        wrapper.api
            .requestStreamSettings(identifier, feature)
            .subscribe({
                runOnUiThread { result.success(gson.toJson(it)) }
            }, {
                runOnUiThread {
                    result.error(it.toString(), it.message, null)
                }
            })
            .discard()
    }

    private fun startRecording(
        call: MethodCall,
        result: Result,
    ) {
        val arguments = call.arguments as List<*>
        val identifier = arguments[0] as String
        val exerciseId = arguments[1] as String
        val interval = gson.fromJson(arguments[2] as String, RecordingInterval::class.java)
        val sampleType = gson.fromJson(arguments[3] as String, SampleType::class.java)

        wrapper.api
            .startRecording(identifier, exerciseId, interval, sampleType)
            .subscribe({
                runOnUiThread { result.success(null) }
            }, {
                runOnUiThread {
                    result.error(it.toString(), it.message, null)
                }
            })
            .discard()
    }

    private fun stopRecording(
        call: MethodCall,
        result: Result,
    ) {
        val identifier = call.arguments as String

        wrapper.api
            .stopRecording(identifier)
            .subscribe({
                runOnUiThread { result.success(null) }
            }, {
                runOnUiThread {
                    result.error(it.toString(), it.message, null)
                }
            })
            .discard()
    }

    private fun requestRecordingStatus(
        call: MethodCall,
        result: Result,
    ) {
        val arguments = call.arguments as List<*>
        val identifier = arguments[0] as String
        val dataType = arguments[1] as String

        val dataKey = PolarDeviceDataType.ACC.ordinal

        wrapper.api
            .getOfflineRecordingStatus(identifier)
            .subscribe({
                runOnUiThread {
                    result.success(listOf(it[dataKey]))
                }
            }, {
                runOnUiThread {
                    result.error(it.toString(), it.message, null)
                }
            })
            .discard()
    }

    private fun listExercises(
        call: MethodCall,
        result: Result,
    ) {
        val identifier = call.arguments as String

        val exercises = mutableListOf<String>()
        wrapper.api
            .listExercises(identifier)
            .subscribe({
                exercises.add(gson.toJson(it))
            }, {
                runOnUiThread {
                    result.error(it.toString(), it.message, null)
                }
            }, {
                result.success(exercises)
            })
            .discard()
    }

    private fun fetchExercise(
        call: MethodCall,
        result: Result,
    ) {
        val arguments = call.arguments as List<*>
        val identifier = arguments[0] as String
        val entry = gson.fromJson(arguments[1] as String, PolarExerciseEntry::class.java)

        wrapper.api
            .fetchExercise(identifier, entry)
            .subscribe({
                result.success(gson.toJson(it))
            }, {
                runOnUiThread {
                    result.error(it.toString(), it.message, null)
                }
            })
            .discard()
    }

    private fun removeExercise(
        call: MethodCall,
        result: Result,
    ) {
        val arguments = call.arguments as List<*>
        val identifier = arguments[0] as String
        val entry = gson.fromJson(arguments[1] as String, PolarExerciseEntry::class.java)

        wrapper.api
            .removeExercise(identifier, entry)
            .subscribe({
                runOnUiThread { result.success(null) }
            }, {
                runOnUiThread {
                    result.error(it.toString(), it.message, null)
                }
            })
            .discard()
    }

    private fun setLedConfig(
        call: MethodCall,
        result: Result,
    ) {
        val arguments = call.arguments as List<*>
        val identifier = arguments[0] as String
        val config = gson.fromJson(arguments[1] as String, LedConfig::class.java)

        wrapper.api
            .setLedConfig(identifier, config)
            .subscribe({
                runOnUiThread { result.success(null) }
            }, {
                runOnUiThread {
                    result.error(it.toString(), it.message, null)
                }
            })
            .discard()
    }

    private fun doFactoryReset(
        call: MethodCall,
        result: Result,
    ) {
        val arguments = call.arguments as List<*>
        val identifier = arguments[0] as String
        val preservePairingInformation = arguments[1] as Boolean

        wrapper.api
            .doFactoryReset(identifier, preservePairingInformation)
            .subscribe({
                runOnUiThread { result.success(null) }
            }, {
                runOnUiThread {
                    result.error(it.toString(), it.message, null)
                }
            })
            .discard()
    }

    private fun enableSdkMode(
        call: MethodCall,
        result: Result,
    ) {
        val identifier = call.arguments as String
        wrapper.api
            .enableSDKMode(identifier)
            .subscribe({
                runOnUiThread { result.success(null) }
            }, {
                runOnUiThread {
                    result.error(it.toString(), it.message, null)
                }
            })
            .discard()
    }

    private fun disableSdkMode(
        call: MethodCall,
        result: Result,
    ) {
        val identifier = call.arguments as String
        wrapper.api
            .disableSDKMode(identifier)
            .subscribe({
                runOnUiThread { result.success(null) }
            }, {
                runOnUiThread {
                    result.error(it.toString(), it.message, null)
                }
            })
            .discard()
    }

    private fun isSdkModeEnabled(
        call: MethodCall,
        result: Result,
    ) {
        val identifier = call.arguments as String
        wrapper.api
            .isSDKModeEnabled(identifier)
            .subscribe({
                runOnUiThread { result.success(it) }
            }, {
                runOnUiThread {
                    result.error(it.toString(), it.message, null)
                }
            })
            .discard()
    }
    private fun requestOfflineRecordingSettings(call: MethodCall, result: Result) {
        val arguments = call.arguments as List<*>
        val identifier = arguments[0] as String
        val feature = gson.fromJson(arguments[1] as String, PolarDeviceDataType::class.java)

        wrapper.api
            .requestOfflineRecordingSettings(identifier, feature)
            .subscribe({
                runOnUiThread { result.success(gson.toJson(it)) }
            }, {
                runOnUiThread {
                    result.error(it.toString(), it.message, null)
                }
            })
            .discard()
    }

    private fun startOfflineRecording(call: MethodCall, result: Result) {
        val arguments = call.arguments as List<*>
        val identifier = arguments[0] as String
        val feature = gson.fromJson(arguments[1] as String, PolarDeviceDataType::class.java)
        val settings = gson.fromJson(arguments[2] as String, PolarSensorSetting::class.java)

        wrapper.api
            .startOfflineRecording(identifier, feature, settings)
            .subscribe({
                runOnUiThread { result.success(null) }
            }, {
                runOnUiThread {
                    result.error("ERROR_STARTING_RECORDING", it.message, null)
                }
            })
            .discard()
    }
    private fun stopOfflineRecording(call: MethodCall, result: Result) {
        val arguments = call.arguments as List<*>
        val identifier = arguments[0] as String
        val feature = gson.fromJson(arguments[1] as String, PolarDeviceDataType::class.java)

        wrapper.api
            .stopOfflineRecording(identifier, feature)
            .subscribe({
                runOnUiThread { result.success(null) }
            }, {
                runOnUiThread {
                    result.error("ERROR_STOPPING_RECORDING", it.message, null)
                }
            })
            .discard()
    }
    private fun getOfflineRecordingStatus(call: MethodCall, result: Result) {
        val arguments = call.arguments as List<*>
        val identifier = arguments[0] as String

        wrapper.api
            .getOfflineRecordingStatus(identifier)
            .subscribe({ dataTypes ->
                val dataTypeNames = dataTypes.map { it.name } // Convert to list of strings
                runOnUiThread { result.success(dataTypeNames) }
            }, { error ->
                runOnUiThread {
                    result.error("ERROR", error.message, null)
                }
            })
            .discard()
    }

    private fun listOfflineRecordings(call: MethodCall, result: Result) {
        val identifier = call.arguments as String

        wrapper.api
            .listOfflineRecordings(identifier)
            .toList()
            .subscribe({ entries ->
                runOnUiThread {
                    try {
                        // Convert the list to a JSON string
                        val jsonString = gson.toJson(entries)
                        result.success(jsonString)
                    } catch (e: Exception) {
                        result.error(
                            "JSON_ERROR",
                            "Failed to convert recordings to JSON",
                            e.message
                        )
                    }
                }
            }, { error ->
                runOnUiThread {
                    result.error(
                        "LIST_ERROR",
                        "Failed to list offline recordings",
                        error.message
                    )
                }
            })
            .discard()
    }

    private fun fetchOfflineRecording(call: MethodCall, result: Result) {
        val arguments = call.arguments as List<*>
        val identifier = arguments[0] as String
        val entry = gson.fromJson(arguments[1] as String, PolarOfflineRecordingEntry::class.java)
        
        wrapper.api
            .getOfflineRecord(identifier, entry)
            .subscribe({ recordingData ->
                println("Successfully received recording data: $recordingData")
                runOnUiThread {
                    try {
                        val dataMap = when (entry.type) {
                            PolarDeviceDataType.HR -> mapOf(
                                "type" to entry.type.ordinal,
                                "startTime" to entry.date.time,
                                "settings" to recordingData.settings,
                                "hrData" to mapOf(
                                    "samples" to (recordingData as PolarOfflineRecordingData.HrOfflineRecording).data.samples
                                )
                            )
                            PolarDeviceDataType.ACC -> mapOf(
                                "type" to entry.type.ordinal,
                                "startTime" to entry.date.time,
                                "settings" to recordingData.settings,
                                "accData" to mapOf(
                                    "samples" to (recordingData as PolarOfflineRecordingData.AccOfflineRecording).data.samples
                                )
                            )
                            PolarDeviceDataType.GYRO -> mapOf(
                                "type" to entry.type.ordinal,
                                "startTime" to entry.date.time,
                                "settings" to recordingData.settings,
                                "gyroData" to mapOf(
                                    "samples" to (recordingData as PolarOfflineRecordingData.GyroOfflineRecording).data.samples
                                )
                            )
                            else -> throw Exception("Unsupported data type: ${entry.type}")
                        }
                        
                        val jsonString = gson.toJson(dataMap)
                        println("Raw JSON output: $jsonString")
                        result.success(jsonString)
                    } catch (e: Exception) {
                        println("Error converting to JSON: ${e.message}")
                        result.error(
                            "JSON_ERROR",
                            "Failed to convert recording data to JSON",
                            e.message
                        )
                    }
                }
            }, { error ->
                println("Error fetching recording: ${error.message}")
                runOnUiThread {
                    result.error(
                        "FETCH_ERROR",
                        "Failed to fetch recording",
                        error.message
                    )
                }
            })
            .discard()
    }

      private fun removeOfflineRecord(call: MethodCall, result: Result) {
        val arguments = call.arguments as List<*>
        val identifier = arguments[0] as String
        val entry = gson.fromJson(arguments[1] as String, PolarOfflineRecordingEntry::class.java)

        wrapper.api
            .removeOfflineRecord(identifier, entry)
            .subscribe({
                runOnUiThread { result.success(null) }
            }, {
                runOnUiThread {
                    result.error(it.toString(), it.message, null)
                }
            })
            .discard()
    }

    private fun getDiskSpace(call: MethodCall, result: Result) {
        val identifier = call.arguments as String

        wrapper.api
            .getDiskSpace(identifier)
            .subscribe({
                val (freeSpace, totalSpace) = it
                runOnUiThread {
                    result.success(listOf(freeSpace, totalSpace))
                }
            }, {
                runOnUiThread {
                    result.error(it.toString(), it.message, null)
                }
            })
            .discard()
    }

    private fun setOfflineRecordingTrigger(call: MethodCall, result: Result) {
        val arguments = call.arguments as List<*>
        val identifier = arguments[0] as String
        
        // Create simple ACC settings similar to iOS implementation
        val simpleSettingsAcc = PolarSensorSetting(
            mapOf(
                PolarSensorSetting.SettingType.SAMPLE_RATE to 52,
                PolarSensorSetting.SettingType.RANGE to 8,
                PolarSensorSetting.SettingType.RESOLUTION to 16,
                PolarSensorSetting.SettingType.CHANNELS to 3
            )
        )

        // Create trigger features map
        val triggerFeatures = mapOf(
            PolarDeviceDataType.HR to null,
            PolarDeviceDataType.ACC to simpleSettingsAcc
        )

        // Create trigger with system start mode and features
        val trigger = PolarOfflineRecordingTrigger(
            PolarOfflineRecordingTriggerMode.TRIGGER_SYSTEM_START,
            triggerFeatures
        )

        wrapper.api
            .setOfflineRecordingTrigger(identifier, trigger)
            .subscribe({
                runOnUiThread { result.success(null) }
            }, {
                runOnUiThread {
                    result.error(it.toString(), it.message, null)
                }
            })
            .discard()
    }
}

class PolarWrapper(
    context: Context,
    val api: PolarBleApi =
        PolarBleApiDefaultImpl.defaultImplementation(
            context,
            PolarBleSdkFeature.values().toSet(),
        ),
    private val sinks: MutableMap<Int, EventSink> = mutableMapOf(),
) : PolarBleApiCallbackProvider {
    init {
        api.setApiCallback(this)
    }

    fun addSink(
        id: Int,
        sink: EventSink,
    ) {
        sinks[id] = sink
    }

    fun removeSink(id: Int) {
        sinks.remove(id)
    }

    private fun success(
        event: String,
        data: Any?,
    ) {
        runOnUiThread { sinks.values.forEach { it.success(mapOf("event" to event, "data" to data)) } }
    }

    fun shutDown() {
        // Do not shutdown the api if other engines are still using it
        if (sinks.isNotEmpty()) return
        try {
            api.shutDown()
        } catch (e: Exception) {
            // This will throw if the API is already shut down
        }
    }

    override fun blePowerStateChanged(powered: Boolean) {
        success("blePowerStateChanged", powered)
    }

    override fun bleSdkFeatureReady(
        identifier: String,
        feature: PolarBleSdkFeature,
    ) {
        success("sdkFeatureReady", listOf(identifier, feature.name))
    }

    override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
        success("deviceConnected", gson.toJson(polarDeviceInfo))
    }

    override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
        success("deviceConnecting", gson.toJson(polarDeviceInfo))
    }

    override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
        success(
            "deviceDisconnected",
            // The second argument is the `pairingError` field on iOS
            // Since Android doesn't implement that, always send false
            listOf(gson.toJson(polarDeviceInfo), false),
        )
    }

    override fun disInformationReceived(
        identifier: String,
        uuid: UUID,
        value: String,
    ) {
        success("disInformationReceived", listOf(identifier, uuid.toString(), value))
    }

    override fun disInformationReceived(
        identifier: String,
        disInfo: DisInfo,
    ) {
        success("disInformationReceived", listOf(identifier, disInfo.key, disInfo.value))
    }

    override fun batteryLevelReceived(
        identifier: String,
        level: Int,
    ) {
        success("batteryLevelReceived", listOf(identifier, level))
    }

    override fun batteryChargingStatusReceived(
        identifier: String,
        chargingStatus: ChargeState,
    ) {
        success("batteryChargingStatusReceived", listOf(identifier, chargingStatus.name))
    }

    override fun htsNotificationReceived(
        identifier: String,
        data: PolarHealthThermometerData,
    ) {
        // Do nothing
    }

    @Deprecated("", replaceWith = ReplaceWith(""))
    override fun hrNotificationReceived(
        identifier: String,
        data: PolarHrData.PolarHrSample,
    ) {
        // Do nothing
    }
}

class StreamingChannel(
    messenger: BinaryMessenger,
    name: String,
    private val api: PolarBleApi,
    private val identifier: String,
    private val feature: PolarDeviceDataType,
    private val channel: EventChannel = EventChannel(messenger, name),
) : EventChannel.StreamHandler {
    private var subscription: Disposable? = null

    init {
        channel.setStreamHandler(this)
    }

    override fun onListen(
        arguments: Any?,
        events: EventSink,
    ) {
        // Will be null for some features
        val settings = gson.fromJson(arguments as String, PolarSensorSetting::class.java)

        val stream =
            when (feature) {
                PolarDeviceDataType.HR -> api.startHrStreaming(identifier)
                PolarDeviceDataType.ECG -> api.startEcgStreaming(identifier, settings)
                PolarDeviceDataType.ACC -> api.startAccStreaming(identifier, settings)
                PolarDeviceDataType.PPG -> api.startPpgStreaming(identifier, settings)
                PolarDeviceDataType.PPI -> api.startPpiStreaming(identifier)
                PolarDeviceDataType.GYRO -> api.startGyroStreaming(identifier, settings)
                PolarDeviceDataType.MAGNETOMETER ->
                    api.startMagnetometerStreaming(
                        identifier,
                        settings,
                    )

                PolarDeviceDataType.TEMPERATURE ->
                    api.startTemperatureStreaming(
                        identifier,
                        settings,
                    )
                PolarDeviceDataType.PRESSURE -> api.startPressureStreaming(identifier, settings)
                PolarDeviceDataType.SKIN_TEMPERATURE -> api.startSkinTemperatureStreaming(identifier, settings)
                PolarDeviceDataType.LOCATION -> api.startLocationStreaming(identifier, settings)
            }

        subscription =
            stream.subscribe({
                runOnUiThread { events.success(gson.toJson(it)) }
            }, {
                runOnUiThread {
                    events.error(it.toString(), it.message, null)
                }
            }, {
                runOnUiThread { events.endOfStream() }
            })
    }

    override fun onCancel(arguments: Any?) {
        subscription?.dispose()
    }

    fun dispose() {
        subscription?.dispose()
        channel.setStreamHandler(null)
    }
}
