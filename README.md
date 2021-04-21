# Pepper Face Mask detection

This Android library will help you detect people wearing face masks or not with Pepper.

It is based on [AIZoo's FaceMaskDetection](https://github.com/AIZOOTech/FaceMaskDetection), and uses the same model, running on Pepper's tablet, using OpenCV.

This can be used to make Pepper give a personalized welcome to human depending on whether they are wearing a mask or not.

## 1. Video demonstration

See [this video](https://youtu.be/4Ll40uxssBs) (which is based on an early version of this app, the current GUI has evolved).

## 2. Getting started

### 2.1. Running the sample app

The project comes complete with a sample project. You can clone the repository, open it in Android Studio, and run this directly onto a Robot.

The sample application will just track people and indicate on the tablet whether or not they wear a mask.

Note that this application will not work on a simulated robot.

## 3. Using the library in your project

### 3.1. Add the library as a dependency

You can use Jitpack (https://jitpack.io/) to add the library as a gradle dependency.

**Step 1)** add JitPack repository to your build file:

Add it in your root build.gradle at the end of repositories:

```
allprojects {
		repositories {
			...
			maven { url 'https://jitpack.io' }
		}
	}
```

**Step 2)** Add the dependency on the face detection lib to your app build.gradle in the dependencies section:

	dependencies {
		implementation 'com.github.softbankrobotics-labs:pepper-mask-detection:master-SNAPSHOT'
	}


### 3.2. Add OpenCV libraries

The library depends on OpenCV.

You have two options to add the OpenCV libraries to your project:

#### 3.2.1. Include the OpenCV native libraries

With this method you directly add the libraries to your apk. The disadvantage of this method is that your apk will become larger.

Copy the `jniLibs` folder [app/src/main/jniLibs](app/src/main/jniLibs) (in the sample app) into your own `src/main` folder.
Android studio will automatically find and include these libraries into your apk.

Note that the sample application only contains libraries compiled for the "armeabi-v7a" architecture, which is the one used by Pepper's tablet.

You can get more versions of these libraries [here](https://pullrequest.opencv.org/buildbot/builders/3_4-contrib_pack-contrib-android).

#### 3.2.2. Use an OpenCV external APK

With this method, the opencv libraries are not installed with your apk, you need to install them separately on the robot.

To install OpenCV manager APK, connect to your robot ip (for instance 10.0.204.180) with adb and install the package you will find in the folder [opencv-apk](opencv-apk/):

```
$ adb connect 10.0.204.180:5555
$ adb install opencv-apk/OpenCV_3.4.7-dev_Manager_3.47_armeabi-v7a.apk
```

## 4. Usage

You can look at the sample activity for an example of how to use the library.

The sample activity uses Pepper's tablet camera to get images (as the framerate is slightly better), but you can change that by setting the `useTopCamera` to True at the top of MainActivity.

### 4.1. Prerequisite: load OpenCV in our Activity

In your Activity class, you need to load OpenCV in the `onCreate` method in order to be able to use the library. To do so, call `OpenCVUtils.loadOpenCV(this)`:

```kotlin

class MyActivity : AppCompatActivity(), RobotLifecycleCallbacks() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        OpenCVUtils.loadOpenCV(this)
        //...
    }
```

### 4.2. Basic usage

The detection requires two components:

 * A Camera capturer, for retrieving images; the library provides one for the top or bottom camera
 * A Detector, for processing the images and returning whether they have a mask; the library provides one using OpenCV and AIZoo's model, but you could implement your own based on another technology.

To build a detector and start detection, do this:

```kotlin
    val detector = AizooFaceMaskDetector(this)
    val capturer = BottomCameraCapturer(this, this)
    val detection = FaceMaskDetection(detector, capturer)
    detection.start { faces ->
        // Handle faces here
    }
```

Each time an image is processed, this callback will be called with a (possibly empty) list of faces detected.

The callback will be a list of `DetectedFace`, called for each image processed

```kotlin
    faces.forEach {
        when {
            (it.confidence > 0.5) && it.hasMask -> {
                // Process "someone has a mask"
            }
            (it.confidence > 0.5) && !it.hasMask -> {
                // Process "someone Doesn't have a mask"
            }
        }
    }
```

In addition to hasMask and confidence, you can also get the face's boundingBox, and the corresponding picture (which will be a square slightly larger than the actual bounding box, for better display).


Note that this API does *not* work exactly like the built-in [humansAround detection](https://developer.softbankrobotics.com/pepper-qisdk/api/perceptions/tutorials/humanawareness-human), in that for each detection a new `DetectedFace` object is returned, you cannot compare the items to the previously received list.

This means that once a human wearing a mask is in front of Pepper, this callback will be called very often - if your goal is to make Pepper give a custom welcome, you may want to add a timer to avoid giving too often, or wait before not seeing anybody (or better, not seeing anybody during e.g. one second) before allowing Pepper to give a welcome again.


### 4.3. Using the tablet camera

Pepper's tablet camera has the advantage of having a slightly better framerate, but doesn't always correspond to the angle Pepper is looking at.

Before you use it, you need to make sure you have the permissions to do that, [as explained here](https://developer.android.com/training/permissions/requesting). Once you have the permission, you can create a BottomCameraCapturer and start detecting:

```kotlin
    val capturer = BottomCameraCapturer(this, this)
    val detection = FaceMaskDetection(detector, capturer)
    detection.start { faces ->
        // Handle faces here
    }
```

### 4.4. Using the head camera

The head camera has the advantage of tracking humans Pepper sees, and looks at them from a slightly better angle, but has a less good framerate. Note that head tracking relies on Pepper's built-in human awareness to have detected the human, which will not happen as easily for people wearing face masks.

To use the head's camera, use the TopCameraCapturer, that requires a qiContext, in onRobotFocusGained:

```kotlin
    override fun onRobotFocusGained(qiContext: QiContext) {
        val detector = AizooFaceMaskDetector(this)
        val capturer = TopCameraCapturer(qiContext)
        val detection = FaceMaskDetection(detector, capturer)
        detection.start { faces ->
            // Handle faces here
        }
    }
```


## 5. Advanced usage

### 5.1. Use a different version of OpenCV

It is possible to replace the version of OpenCV contained in this project. Though the complete method on how to do it exactly is left out of this README.

### 5.2. Using your own detector

There are two ways of changing how detection works:

 * By replacing the model by another model (possibly one you trained yourself). The quality of the mask detection (and face detection) mostly depends of the quality of the model used, and it may be possible to build a better one (there are a lot of shapes and colors of face masks, as well as shapes and colors of faces, it's possible that the model built by AIZoo does not cover enough of them, or does not handle children well, etc.). 
 * By implementing your own detector class. The provided one uses OpenCV, but it's also possible to make one using e.g. [Tensorflow Lite](https://www.tensorflow.org/lite) or any other technology. You just need to subclass `FaceMaskDetector`, and implement the function `recognize`, that takes a picture, and returns a list of faces.


```kotlin
    fun recognize(picture: Bitmap): List<DetectedFace>
```


## 6. User privacy

This application handles images of user's faces. It does not store them or send them to any external server; make sure your usage complies with local regulation, such as the GDPR in Europe.

## 7. License

This project is licensed under the BSD 3-Clause "New" or "Revised" License - see the [COPYING](COPYING.md) file for details; except for the OpenCV parts - see [OPENCV_LICENSE](OPENCV_LICENSE.md).
