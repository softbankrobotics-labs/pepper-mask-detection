package com.softbankrobotics.peppermaskdetection

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.QiSDK
import com.aldebaran.qi.sdk.RobotLifecycleCallbacks
import com.aldebaran.qi.sdk.`object`.conversation.*
import com.aldebaran.qi.sdk.builder.QiChatbotBuilder
import com.aldebaran.qi.sdk.builder.TopicBuilder
import com.aldebaran.qi.sdk.conversationalcontentlibrary.askrobotname.AskRobotNameConversationalContent
import com.aldebaran.qi.sdk.conversationalcontentlibrary.base.AbstractConversationalContent
import com.aldebaran.qi.sdk.conversationalcontentlibrary.base.ConversationalContentChatBuilder
import com.aldebaran.qi.sdk.conversationalcontentlibrary.datetime.DateTimeConversationalContent
import com.aldebaran.qi.sdk.conversationalcontentlibrary.farewell.FarewellConversationalContent
import com.aldebaran.qi.sdk.conversationalcontentlibrary.greetings.GreetingsConversationalContent
import com.aldebaran.qi.sdk.conversationalcontentlibrary.robotabilities.RobotAbilitiesConversationalContent
import com.aldebaran.qi.sdk.conversationalcontentlibrary.volumecontrol.VolumeControlConversationalContent
import com.aldebaran.qi.sdk.design.activity.RobotActivity
import com.softbankrobotics.facemaskdetection.FaceMaskDetection
import com.softbankrobotics.facemaskdetection.capturer.BottomCameraCapturer
import com.softbankrobotics.facemaskdetection.capturer.TopCameraCapturer
import com.softbankrobotics.facemaskdetection.detector.AizooFaceMaskDetector
import com.softbankrobotics.facemaskdetection.detector.FaceMaskDetector
import com.softbankrobotics.facemaskdetection.utils.OpenCVUtils
import com.softbankrobotics.facemaskdetection.utils.TAG
import kotlinx.android.synthetic.main.activity_main.*

class MainActivity : RobotActivity(), RobotLifecycleCallbacks {

    private var qiChatbot: QiChatbot? = null
    private var chatFuture: Future<Void>? = null
    private val useTopCamera = true
    private var shouldBeRecognizing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //setSpeechBarDisplayStrategy(SpeechBarDisplayStrategy.IMMERSIVE)
        setContentView(R.layout.activity_main)
        clearFaces()
        if (useTopCamera || cameraPermissionAlreadyGranted()) {
            // No need to request permission
            QiSDK.register(this, this)
        } else {
            // First launch, needs to ask permission
            requestPermissionForCamera()
        }
    }

    public override fun onPause() {
        super.onPause()
        Log.i(TAG, "onPause")
        shouldBeRecognizing = false
        detectionFuture?.requestCancellation()
        detectionFuture = null
    }

    public override fun onResume() {
        super.onResume()
        OpenCVUtils.loadOpenCV(this)
        clearFaces()
        shouldBeRecognizing = true
        startDetecting()
    }

    override fun onDestroy() {
        super.onDestroy()
        detectionFuture?.requestCancellation()
        QiSDK.unregister(this)
    }

    private var detection: FaceMaskDetection? = null
    private var detectionFuture: Future<Unit>? = null

    /**********************
     * Android permissions
     **********************/

    private val CAMERA_PERMISSION_REQUEST_CODE = 1

    private fun requestPermissionForCamera() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
            Toast.makeText(
                this,
                R.string.permissions_needed,
                Toast.LENGTH_LONG
            ).show()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun cameraPermissionAlreadyGranted(): Boolean {
        val resultCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        return resultCamera == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            var cameraPermissionGranted = true

            for (grantResult in grantResults) {
                cameraPermissionGranted = cameraPermissionGranted and
                        (grantResult == PackageManager.PERMISSION_GRANTED)
            }
            if (cameraPermissionGranted) {
                QiSDK.register(this, this)
            } else {
                Toast.makeText(
                    this,
                    R.string.permissions_needed,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**********************
     * Display logic
     **********************/

    inner class FacesForDisplay(rawFaces: List<FaceMaskDetector.DetectedFace>) {
        // Choose the "main" focused faced, which is either the biggest or, when there are a lot of
        // people, the one in the middle.
        val mainFace: FaceMaskDetector.DetectedFace? = when {
            rawFaces.size >= 5 -> rawFaces[2]
            rawFaces.size == 4 -> rawFaces.subList(1, 3).maxBy { it.size() }
            else -> rawFaces.maxBy { it.size() }
        }
        private val mainFaceIndex = rawFaces.indexOf(mainFace)

        // Set the other faces relatively
        val leftFarFace: FaceMaskDetector.DetectedFace? = rawFaces.getOrNull(mainFaceIndex - 2)
        val leftNearFace: FaceMaskDetector.DetectedFace? = rawFaces.getOrNull(mainFaceIndex - 1)
        val rightNearFace: FaceMaskDetector.DetectedFace? = rawFaces.getOrNull(mainFaceIndex + 1)
        val rightFarFace: FaceMaskDetector.DetectedFace? = rawFaces.getOrNull(mainFaceIndex + 2)
    }

    private fun setFaces(faces: List<FaceMaskDetector.DetectedFace>) {
        val facesForDisplay = FacesForDisplay(faces)
        runOnUiThread {
            setFace(big_card, facesForDisplay.mainFace, false)
            setFace(little_card_1, facesForDisplay.leftFarFace)
            setFace(little_card_2, facesForDisplay.leftNearFace)
            setFace(little_card_3, facesForDisplay.rightNearFace)
            setFace(little_card_4, facesForDisplay.rightFarFace)
        }
    }

    private fun clearFaces() {
        runOnUiThread {
            big_card.visibility = View.INVISIBLE
            little_card_1.visibility = View.INVISIBLE
            little_card_2.visibility = View.INVISIBLE
            little_card_3.visibility = View.INVISIBLE
            little_card_4.visibility = View.INVISIBLE
        }
    }

    private fun setFace(
        card: View,
        face: FaceMaskDetector.DetectedFace?,
        hideIfEmpty: Boolean = true
    ) {
        if (hideIfEmpty && face == null) {
            card.visibility = View.INVISIBLE
        } else {
            card.visibility = View.VISIBLE
            val photo = card.findViewById<ImageView>(R.id.photo)
            val circle = card.findViewById<ImageView>(R.id.circle)
            val label = card.findViewById<TextView>(R.id.label)
            if (face != null) {
                photo.visibility = View.VISIBLE
                photo.setImageBitmap(face.picture)
                val color = if (face.hasMask) {
                    resources.getColor(R.color.colorMaskDetected, null)
                } else {
                    resources.getColor(R.color.colorNoMaskDetected, null)
                }
                circle.setColorFilter(color)
                label.text =
                    resources.getString(if (face.hasMask) R.string.mask else R.string.no_mask)
            } else {
                photo.visibility = View.INVISIBLE
                circle.setColorFilter(resources.getColor(R.color.colorNobody, null))
                label.text = ""
            }
        }
    }


    /**********************
     * Greetings
     **********************/

    var engaged = false
    var sawNobodyCount = 0

    private fun jumpToBookmark(bookmark: Bookmark?) {
        bookmark?.let {
            qiChatbot?.async()?.goToBookmark(
                it,
                AutonomousReactionImportance.HIGH,
                AutonomousReactionValidity.DELAYABLE
            )
        }
    }

    var lastSawWithoutMask = false
    var annoyance = 0

    var lastMentionedPeopleNum = 0
    var worthMentioningPeopleCounter = 0

    private fun updateState(seesWithMask: Boolean, seesWithoutMask: Boolean, numPeople: Int) {
        val seesSomebody = seesWithMask || seesWithoutMask
        Log.w(
            TAG,
            "DBG updating state wMask=${seesWithMask} noMask=${seesWithoutMask} engaged=${engaged}"
        )

        if (engaged) {
            // Update status when already engaged
            if (seesSomebody) {
                sawNobodyCount = 0 // Stay engaged
                // See if they put on a mask
                var saidSomething = false

                // See if it's worth mentioning people putting on masks or taking them off
                if (seesWithoutMask == lastSawWithoutMask) {
                    annoyance = 0
                } else {
                    // Something changed !
                    annoyance += 1
                    if (annoyance >= 2) {
                        annoyance = 0
                        lastSawWithoutMask = seesWithoutMask
                        saidSomething = true
                        if (seesWithoutMask) {
                            if (seesWithMask) {
                                jumpToBookmark(newWithoutMaskBookmark)
                            } else {
                                jumpToBookmark(tookOffMaskBookmark)
                            }
                        } else {
                            jumpToBookmark(putOnMaskBookmark)
                        }
                        lastMentionedPeopleNum = numPeople
                        worthMentioningPeopleCounter = 0
                    }
                }

                // See if it's worth mentioning a lot of people
                if (numPeople > lastMentionedPeopleNum) {
                    if (worthMentioningPeopleCounter > 2) {
                        lastMentionedPeopleNum = numPeople
                        worthMentioningPeopleCounter = 0
                        jumpToBookmark(manyPeopleBookmark)
                    } else {
                        worthMentioningPeopleCounter += 1
                    }
                } else {
                    lastMentionedPeopleNum = numPeople
                    worthMentioningPeopleCounter = 0
                }

            } else {
                sawNobodyCount += 1
                if (sawNobodyCount > 2) {
                    engaged = false
                    //chat?.removeAllOnStartedListeners()
                    //chatFuture?.cancel(false)
                    chatFuture = null
                    lastSawWithoutMask = false
                    annoyance = 0
                }
            }
        } else if (seesSomebody) {
            engaged = true
            chat?.let { chat ->
                if (seesWithoutMask) {
                    jumpToBookmark(noMaskBookmark)
                } else {
                    jumpToBookmark(maskBookmark)
                }
                lastSawWithoutMask = seesWithoutMask
                annoyance = 0
            }
        }
    }


    /**********************
     * Robot Lifecycle
     **********************/


    private fun startDetecting() {
        detectionFuture = detection?.start { faces ->
            // Filter and sort the faces so that they're left to right and certain enough
            val sortedFaces = faces
                .filter { (it.confidence > 0.5) }
                .sortedBy { -it.bb.left }
            Log.i(TAG, "Filtered faces ${faces.size}, ->  ${sortedFaces.size}")
            setFaces(sortedFaces)
            // Now update the logic
            val seesWithMask = sortedFaces.any { it.hasMask }
            val seesWithoutMask = sortedFaces.any { !it.hasMask }
            val numPeople = sortedFaces.size
            updateState(seesWithMask, seesWithoutMask, numPeople)
        }
        detectionFuture?.thenConsume {
            Log.i(
                TAG,
                "Detection future has finished: success=${it.isSuccess}, cancelled=${it.isCancelled}"
            )
            if (shouldBeRecognizing) {
                Log.w(TAG, "Stopped, but it shouldn't have - starting it again")
                startDetecting()
            }
        }
    }

    var chat: Chat? = null
    var maskBookmark: Bookmark? = null
    var noMaskBookmark: Bookmark? = null
    var tookOffMaskBookmark: Bookmark? = null
    var putOnMaskBookmark: Bookmark? = null
    var newWithoutMaskBookmark: Bookmark? = null
    var manyPeopleBookmark: Bookmark? = null

    override fun onRobotFocusGained(qiContext: QiContext) {
        Log.i(TAG, "onRobotFocusGained")
        if (chat == null) {
            val topic = TopicBuilder.with(qiContext).withResource(R.raw.chat).build()
            val qiChatbot = QiChatbotBuilder.with(qiContext).withTopic(topic).build()
            this.qiChatbot = qiChatbot
            maskBookmark = topic.bookmarks["GREETING_MASK"]
            noMaskBookmark = topic.bookmarks["GREETING_NO_MASK"]
            tookOffMaskBookmark = topic.bookmarks["TOOK_OFF_MASK"]
            putOnMaskBookmark = topic.bookmarks["PUT_ON_MASK"]
            newWithoutMaskBookmark = topic.bookmarks["NEW_WITHOUT_MASK"]
            manyPeopleBookmark = topic.bookmarks["MANY_PEOPLE"]

            val conversationalContents: List<AbstractConversationalContent> = listOf(
                GreetingsConversationalContent(),
                FarewellConversationalContent(),
                AskRobotNameConversationalContent(),
                DateTimeConversationalContent(),
                RobotAbilitiesConversationalContent(),
                VolumeControlConversationalContent()
            )

            chat = ConversationalContentChatBuilder.with(qiContext)
                .withChatbot(qiChatbot)
                .withConversationalContents(conversationalContents)
                .build()
            chat?.listeningBodyLanguage = BodyLanguageOption.DISABLED
        }

        Log.i(TAG, "Initialised chat")

        val capturer = if (useTopCamera) {
            TopCameraCapturer(qiContext)
        } else {
            BottomCameraCapturer(this, this)
        }
        val detector = AizooFaceMaskDetector(this)
        detection = FaceMaskDetection(detector, capturer)
        shouldBeRecognizing = true
        startDetecting()
        Log.i(TAG, "Starting chat")
        chatFuture = chat?.async()?.run()
    }

    override fun onRobotFocusLost() {
        Log.w(TAG, "Robot focus lost")
        detectionFuture?.cancel(true)
    }

    override fun onRobotFocusRefused(reason: String?) {
        Log.e(TAG, "Robot focus refused because $reason")
    }
}
