package io.spokestack.exampleapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.CheckBox
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.spokestack.spokestack.SpeechContext
import io.spokestack.spokestack.Spokestack
import io.spokestack.spokestack.SpokestackAdapter
import io.spokestack.spokestack.nlu.NLUResult
import io.spokestack.spokestack.nlu.Slot
import io.spokestack.spokestack.tts.SynthesisRequest
import io.spokestack.spokestack.tts.TTSEvent
import io.spokestack.spokestack.util.EventTracer
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {
    private val logTag = javaClass.simpleName

    // a sentinel value we'll use to verify we have the proper permissions to use Spokestack to
    // record audio
    private val audioPermission = 1337

    // the Spokestack instance itself and a listener to receive events from its modules
    private lateinit var spokestack: Spokestack
    private val listener: SpokestackAdapter = SpokestackListener()

    private val toppings: Map<String, Int> = mapOf(
        "white sauce" to R.id.white_sauce,
        "tomato sauce" to R.id.tomato_sauce,
        "fresh mozzarella" to R.id.fresh_mozzarella,
        "dried tomatoes" to R.id.dried_tomatoes,
        "pepperoni" to R.id.pepperoni,
        "mushrooms" to R.id.mushrooms,
        "onions" to R.id.onions,
        "green peppers" to R.id.green_peppers,
        "anchovies" to R.id.anchovies
    )

    // used to automatically extract/cache files needed for NLU
    private val nluFiles: List<String> = listOf("nlu.tflite", "metadata.json", "vocab.txt")

    // simple state to manage the "conversation"
    // used to clear the most recent topping selected
    private var lastChecked: Int = -1

    // used to determine whether the mic should be reopened after a TTS response is read
    private var continueConversation = true


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        spokestack = buildSpokestack()
        checkMicPermission()
        if (checkMicPermission()) {
            spokestack.start()
            spokestack.synthesize(
                SynthesisRequest.Builder("What would you like on your pizza?").build()
            )
        }
    }

    override fun onResume() {
        super.onResume()
    }

    private fun buildSpokestack(): Spokestack {
        checkForModels()

        return Spokestack.Builder()
            .withoutWakeword()
            // NLU
            .setProperty("nlu-model-path", "$cacheDir/nlu.tflite")
            .setProperty("nlu-metadata-path", "$cacheDir/metadata.json")
            .setProperty("wordpiece-vocab-path", "$cacheDir/vocab.txt")
            .setProperty("trace-level", EventTracer.Level.DEBUG.value())
            // TTS
            .setProperty("spokestack-id", "your-client-id")
            .setProperty("spokestack-secret", "your-secret-key")
            .withAndroidContext(applicationContext)
            // make sure we receive Spokestack events
            .addListener(listener)
            .build()
    }

    private fun checkForModels() {
        if (!modelsCached()) {
            decompressModels()
        }
    }

    private fun modelsCached(): Boolean {
        return nluFiles.all {
            val filterFile = File("$cacheDir/$it")
            filterFile.exists()
        }
    }

    private fun decompressModels() {
        nluFiles.forEach(::cacheAsset)
    }

    private fun cacheAsset(fileName: String) {
        val cachedFile = File("$cacheDir/$fileName")
        val inputStream = assets.open(fileName)
        val size = inputStream.available()
        val buffer = ByteArray(size)
        inputStream.read(buffer)
        inputStream.close()
        val fos = FileOutputStream(cachedFile)
        fos.write(buffer)
        fos.close()
    }

    private fun checkMicPermission(): Boolean {
        // On API levels >= 23, users can revoke permissions at any time, and API levels >= 26
        // require the RECORD_AUDIO permission to be requested at runtime, so we'll need
        // to verify it on launch
        val recordPerm = Manifest.permission.RECORD_AUDIO
        val granted = PackageManager.PERMISSION_GRANTED
        if (ContextCompat.checkSelfPermission(this, recordPerm) == granted) {
            return true
        }
        ActivityCompat.requestPermissions(this, arrayOf(recordPerm), audioPermission)
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = PackageManager.PERMISSION_GRANTED
        // respond to the permission request's asynchronous result
        when (requestCode) {
            audioPermission -> {
                if (grantResults.isNotEmpty() && grantResults[0] == granted) {
                    // if you request permissions when, e.g., a microphone button is tapped, you
                    // may wish to call `activate()` here instead of `start()`
                    spokestack.start()
                } else {
                    Log.w(logTag, "Record permission not granted; voice control disabled!")
                }
                return
            }
            else -> {
                // do nothing
            }
        }
    }

    private fun errorToast(message: String) {
        runOnUiThread {
            val toast = Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT)
            toast.show()
        }
    }

    private fun maybeClear(transcript: String) {
        if (lastChecked != -1 && transcript.endsWith("no", ignoreCase = true)) {
            findViewById<CheckBox>(lastChecked).isChecked = false
        }
    }

    private fun setToppings(topping: Slot?) {
        val checkboxId = toppings[topping?.value]
        if (checkboxId != null) {
            findViewById<CheckBox>(checkboxId).isChecked = true
        }
    }

    inner class SpokestackListener : SpokestackAdapter() {

        override fun onEvent(event: SpeechContext.Event, context: SpeechContext) {
            when (event) {
                SpeechContext.Event.PARTIAL_RECOGNIZE ->
                    // classify partial transcripts to allow live updates to the UI
                    context.transcript?.let {
                        errorToast(it)
                        // clear the most recent topping selected if necessary
                        Log.i(logTag, it)
                        maybeClear(it)
                        spokestack.classify(it)
                    }
                SpeechContext.Event.TIMEOUT -> errorToast("ASR timeout")
                SpeechContext.Event.ERROR -> context.error?.message?.let {
                    context.error.printStackTrace()
                    errorToast("${context.error.javaClass}: $it")
                }
                else ->
                    // noop
                    return
            }
        }

        // NLU
        override fun nluResult(result: NLUResult) {
            setToppings(result.slots["topping"])
            when (result.intent) {
                "add.topping" -> {
                    // reactivate to get more toppings
                    continueConversation = true
                    spokestack.activate()
                }
                "order" -> {
                    val prompt = "Will do! It'll be there in twenty minutes or your money back!"
                    spokestack.synthesize(SynthesisRequest.Builder(prompt).build())
                    continueConversation = false
                }
                else ->
                    continueConversation = false
            }
        }

        override fun ttsEvent(event: TTSEvent) {
            when (event.type) {
                // reactivate after the prompt
                TTSEvent.Type.PLAYBACK_COMPLETE -> if (continueConversation) {
                    spokestack.activate()
                }
                else -> return
            }
        }

        override fun onTrace(level: EventTracer.Level, message: String) {
            when (level) {
                EventTracer.Level.ERROR -> Log.e(logTag, message)
                EventTracer.Level.DEBUG -> Log.d(logTag, message)
                EventTracer.Level.INFO -> Log.i(logTag, message)
                EventTracer.Level.WARN -> Log.w(logTag, message)
                else -> Log.v(logTag, message)
            }
        }
    }
}