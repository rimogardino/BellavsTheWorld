package com.example.bellavstheworld

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.media.Image
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.util.Rational
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import androidx.lifecycle.LifecycleOwner
import androidx.renderscript.*
import com.xxxyyy.testcamera2.ScriptC_yuv420888
import kotlinx.android.synthetic.main.activity_main.*
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.*
import java.util.concurrent.TimeUnit


private const val REQUEST_PERMISSIONS_CODE = 13

private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity(), LifecycleOwner {

    private lateinit var viewFinder: TextureView
    private lateinit var tflite: Interpreter
    private lateinit var rs: RenderScript
    private lateinit var textView: TextView
    private lateinit var imageView: ImageView
    private var result = FloatArray(0)
    private var bellaStreak = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        supportActionBar?.hide()

        setContentView(R.layout.activity_main)



        viewFinder = findViewById(R.id.view_finder)
        textView = findViewById(R.id.textView)
        imageView = findViewById(R.id.imageView2)


        if (allPermissionsGranted()) {
            view_finder.post { startCamera() }
        }
        else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_PERMISSIONS_CODE)
        }

        // Every time the provided texture view changes, recompute layout
        viewFinder.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateTransform()
            textView.text = result.toList().toString()
        }




        rs = RenderScript.create(applicationContext)

        try {
            tflite = Interpreter(loadModelFile())
        }
        catch (e: Exception) {
            Log.d(TAG, "Loading model failed")
            e.printStackTrace()
        }

    }


    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            if (allPermissionsGranted()) {
                viewFinder.post { startCamera() }
            }
            else {
                Toast.makeText(this,
                    "I can't function if you do not allow me to have the camera!!",
                     Toast.LENGTH_LONG).show()
                finish()
            }

        }
    }

    private fun startCamera() {
        val metrics = DisplayMetrics().also { viewFinder.display.getRealMetrics(it) }
        val screenAspectRatio = Rational(metrics.widthPixels, metrics.heightPixels)



        val previewConfig = PreviewConfig.Builder().apply {
            setLensFacing(CameraX.LensFacing.BACK)
            setTargetAspectRatio(screenAspectRatio)
            // setTargetResolution(Size(800,800))
            setTargetRotation(viewFinder.display.rotation)
        }.build()
        val preview = Preview(previewConfig)

        preview.onPreviewOutputUpdateListener = Preview.OnPreviewOutputUpdateListener {

            val viewFinderParent = view_finder.parent as ViewGroup
            viewFinderParent.removeView(viewFinder)
            viewFinderParent.addView(viewFinder,0)

            viewFinder.surfaceTexture = it.surfaceTexture

            updateTransform()

        }


        val analyzerConfig = ImageAnalysisConfig.Builder().apply {
            // Use a worker thread for image analysis to prevent glitches
            val analyzerThread = HandlerThread(
                "BelaAnalysis").apply { start() }
            setCallbackHandler(Handler(analyzerThread.looper))
            // In our analysis, we care more about the latest image than
            // analyzing *every* image
            setImageReaderMode(
                ImageAnalysis.ImageReaderMode.ACQUIRE_LATEST_IMAGE)
        }.build()

        // Build the image analysis use case and instantiate our analyzer
        val analyzerUseCase = ImageAnalysis(analyzerConfig).apply {
            analyzer = BellaOrNot(tflite, rs).apply {
                onFrameAnalyzed {
                    result = it

                    this@MainActivity.runOnUiThread {
                        // More detailed results
//                        val labels = listOf("Faces", "bella", "cat", "cup", "dog", "lamp", "pizza")
//                        textView.text = labels[result.max()?.let { it1 -> result.indexOf(it1) }!!]

                        val labelIndex = result.max()?.let { it1 -> result.indexOf(it1) }
                        var resultText = "Not bella :("
                        if (labelIndex == 1) {
                            val postText = if (bellaStreak == 1) "a?" else "aa"

                            resultText = "Bell" + postText.repeat(bellaStreak)
                            bellaStreak++
                        }
                        else {
                            bellaStreak = 1
                        }
                        // Display the results from the analysis
                        textView.text = resultText

                        imageView.setImageBitmap(this.rgbBitmap)
                    }
                }
            }

        }


        CameraX.bindToLifecycle(this, preview, analyzerUseCase)

    }


    private fun updateTransform() {
        val matrix = Matrix()

        // Compute the center of the view finder
        val centerX = viewFinder.width / 2f
        val centerY = viewFinder.height / 2f

        // Correct preview output to account for display rotation
        val rotationDegrees = when(viewFinder.display.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> return
        }
        matrix.postRotate(-rotationDegrees.toFloat(), centerX, centerY)



        // Finally, apply transformations to our TextureView
        viewFinder.setTransform(matrix)


    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this , it) == PackageManager.PERMISSION_GRANTED
    }


    private fun loadModelFile(): MappedByteBuffer {
        Log.d(TAG, "Loadin model")
        val fileDiscriptor = assets.openFd("bella_vs_theworld_mobnetv2_pruned.tflite")
        val inputStream = FileInputStream(fileDiscriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        Log.d(TAG, "Loaded! model")
        return fileChannel.map(FileChannel.MapMode.READ_ONLY,fileDiscriptor.startOffset,fileDiscriptor.declaredLength)
    }

    companion object {
        const val TAG = "mainactivity tag"
    }









    private class BellaOrNot(private var tfModel: Interpreter, private val rs: RenderScript): ImageAnalysis.Analyzer {
        private var lastTimeAnalysed = 0L
        private val listeners = ArrayList<(result: FloatArray) -> Unit>()
        lateinit var rgbBitmap: Bitmap

        /**
         * Used to add listeners that will be called with each analyzed image
         */
        fun onFrameAnalyzed(listener: (result: FloatArray) -> Unit) = listeners.add(listener)



        override fun analyze(image: ImageProxy, rotationDegrees: Int) {
            val currentTimeStamp = System.currentTimeMillis()
            if (currentTimeStamp - lastTimeAnalysed >= TimeUnit.MILLISECONDS.toMillis(500)) {
                val mImage = image.image

                if (mImage != null) {
                    rgbBitmap = YUV_420_888_toRGB(mImage, mImage.width, mImage.height)
                } else {
                    return
                }
                val modelScale = 128
                rgbBitmap = rgbBitmap.scale(modelScale, modelScale, false)


                // Initialize the input and output tensors for the model
                val inputBuffer = arrayOf(Array(modelScale) { Array(modelScale) { floatArrayOf(0f,0f,0f) }})
                val outputArray = Array(1) {FloatArray(7) { 0f }}

                // Init an IntArray to visualise whats being sent to the model
                //val bitmapIntArray = IntArray(modelScale*modelScale)



                /**transfer the rgb values from the bitmap to the inputbuffer
                 * Normalise the data (/255f)
                 * rotate the data, as I it's being/ not being rotated somewhere else
                 */
                for ((i,ix) in inputBuffer[0].withIndex()) {
                    for (n in ix.indices) {

                        val pixelval = rgbBitmap.getPixel(n,i)
                        //for testing: bitmapIntArray is to visualise whats being sent to the model
                        //bitmapIntArray[(n)*modelScale + (modelScale-i-1)] = pixelval
                        inputBuffer[0][n][modelScale-i-1] = floatArrayOf((Color.red(pixelval)).toFloat()/ 255f,(Color.green(pixelval)).toFloat()/ 255f,(Color.blue(pixelval)).toFloat()/ 255f)
                    }
                }


                //set the rgbBitmap = bitmapIntArray so we can access it outside of this class or smth
                //rgbBitmap = Bitmap.createBitmap(bitmapIntArray, modelScale, modelScale, Bitmap.Config.ARGB_8888)


                tfModel.run(inputBuffer, outputArray)


                // Call all listeners with new value
                listeners.forEach { it(outputArray[0]) }


                Log.d(TAG, "class is ${outputArray[0].indexOf(outputArray[0].max()!!)}  analyzed pic ${Arrays.deepToString(outputArray)}")
                lastTimeAnalysed = currentTimeStamp
            }


        }






        fun YUV_420_888_toRGB(image:Image, width:Int, height:Int): Bitmap {
            // Get the three image planes
            val planes = image.planes
            var buffer = planes[0].buffer
            val y = ByteArray(buffer.remaining())
            buffer.get(y)
            buffer = planes[1].buffer
            val u = ByteArray(buffer.remaining())
            buffer.get(u)
            buffer = planes[2].buffer
            val v = ByteArray(buffer.remaining())
            buffer.get(v)
            // get the relevant RowStrides and PixelStrides
            // (we know from documentation that PixelStride is 1 for y)
            val yRowStride = planes[0].rowStride
            val uvRowStride = planes[1].rowStride // we know from documentation that RowStride is the same for u and v.
            val uvPixelStride = planes[1].pixelStride // we know from documentation that PixelStride is the same for u and v.
            // rs creation just for demo. Create rs just once in onCreate and use it again.

            //RenderScript rs = MainActivity.rs;
            val mYuv420 = ScriptC_yuv420888(rs)
            // Y,U,V are defined as global allocations, the out-Allocation is the Bitmap.
            // Note also that uAlloc and vAlloc are 1-dimensional while yAlloc is 2-dimensional.
            val typeUcharY = Type.Builder(rs, Element.U8(rs))
            typeUcharY.setX(yRowStride).setY(height)
            val yAlloc = Allocation.createTyped(rs, typeUcharY.create())
            yAlloc.copyFrom(y)
            mYuv420._ypsIn = yAlloc
            val typeUcharUV = Type.Builder(rs, Element.U8(rs))
            // note that the size of the u's and v's are as follows:
            // ( (width/2)*PixelStride + padding ) * (height/2)
            // = (RowStride ) * (height/2)
            // but I noted that on the S7 it is 1 less...
            typeUcharUV.setX(u.size)
            val uAlloc = Allocation.createTyped(rs, typeUcharUV.create())
            uAlloc.copyFrom(u)
            mYuv420._uIn = uAlloc
            val vAlloc = Allocation.createTyped(rs, typeUcharUV.create())
            vAlloc.copyFrom(v)
            mYuv420._vIn = vAlloc
            // handover parameters
            mYuv420._picWidth = width.toLong()
            mYuv420._uvRowStride = uvRowStride.toLong()
            mYuv420._uvPixelStride = uvPixelStride.toLong()
            val outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val outAlloc = Allocation.createFromBitmap(rs, outBitmap, Allocation.MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT)
            val lo = Script.LaunchOptions()
            lo.setX(0, width) // by this we ignore the y’s padding zone, i.e. the right side of x between width and yRowStride
            lo.setY(0, height)
            mYuv420.forEach_doConvert(outAlloc, lo)
            outAlloc.copyTo(outBitmap)
            return outBitmap
        }



        companion object {
            const val TAG = "Analyzer tag"
        }


    }


}

