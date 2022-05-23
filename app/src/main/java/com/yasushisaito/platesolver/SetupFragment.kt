package com.yasushisaito.platesolver

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.View.OnFocusChangeListener
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.gson.Gson
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

// Convert the lens focal length (FX) to
// the field of view of the image
fun focalLengthToFov(lensmm: Double): Double {
    val rad = 2 * Math.atan(36.0 / (2 * lensmm))
    return rad * 180 / Math.PI
}

fun fovToFocalLength(deg: Double): Double {
    val rad = deg / (180 / Math.PI)
    return 18.0 / Math.tan(rad / 2)
}

private fun isValidFovDeg(fovDeg: Double): Boolean {
    return fovDeg > 0 && fovDeg < 90.0
}

class SetupFragment : Fragment() {
    companion object {
        const val TAG = "SetupFragment"
        const val DEFAULT_FOV_DEG = 2.0
        const val BUNDLE_KEY_FOV_DEG = "fovDeg"

        const val EVENT_MESSAGE = 0
        const val EVENT_OK = 1
        const val EVENT_ERROR = 2
        const val EVENT_SHOW_SOLUTION = 3
        const val EVENT_DEEPSKYCSV_LOADED = 4
    }

    private var fovDeg: Double = DEFAULT_FOV_DEG
    private var imageUri: Uri? = null

    private fun newLauncher(cb: (Intent) -> Unit): ActivityResultLauncher<Intent> {
        val launch =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult? ->
                if (result?.resultCode != Activity.RESULT_OK) {
                    println("launch activity failed: $result")
                    return@registerForActivityResult
                }
                cb(result.data!!)
            }
        return launch
    }

    private val pickFileLauncher = newLauncher { intent: Intent ->
        val uri: Uri = intent.data ?: return@newLauncher
        imageUri = uri
        updateView()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        if (savedInstanceState != null) {
            fovDeg = savedInstanceState.getDouble(BUNDLE_KEY_FOV_DEG, DEFAULT_FOV_DEG)
        }
        DeepSkyCsv.registerOnSingletonLoaded { dispatchMessage(EVENT_DEEPSKYCSV_LOADED, "") }
        return inflater.inflate(R.layout.fragment_setup, container, false)
    }

    private var thisView: View? = null

    override fun onDestroyView() {
        super.onDestroyView()
        thisView = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        thisView = view
        val degEdit = view.findViewById<EditText>(R.id.text_setup_fov_deg)
        degEdit.setOnFocusChangeListener(OnFocusChangeListener { v, hasFocus ->
            if (!hasFocus) {
                var deg: Double = 0.0
                try {
                    deg = degEdit.getText().toString().toDouble()
                } catch (e: Exception) {
                }
                if (isValidFovDeg(deg)) {
                    fovDeg = deg
                }
                updateView()
            }
        })
        val focalLengthEdit = view.findViewById<EditText>(R.id.text_setup_fov_lens)
        focalLengthEdit.setOnFocusChangeListener(OnFocusChangeListener { v, hasFocus ->
            if (!hasFocus) {
                var mm: Double = 0.0
                try {
                    mm = focalLengthEdit.getText().toString().toDouble()
                } catch (e: Exception) {
                }
                if (mm > 0 || mm < 10000) {
                    val deg = focalLengthToFov(mm)
                    if (isValidFovDeg(deg)) {
                        fovDeg = deg
                    }
                }
                updateView()
            }
        })
        val pickFileButton = view.findViewById<Button>(R.id.setup_pick_file)
        pickFileButton.setOnClickListener(View.OnClickListener { view ->
            val intent = Intent()
                .setType("*/*")
                .setAction(Intent.ACTION_GET_CONTENT)
            pickFileLauncher.launch(intent)
        })
        val runButton = view.findViewById<Button>(R.id.setup_run)
        runButton.setOnClickListener(View.OnClickListener { view ->
            onRunAstap()
        })
        updateView()
    }

    private fun updateView() {
        val view: View = thisView ?: return
        val degEdit = view.findViewById<EditText>(R.id.text_setup_fov_deg)
        degEdit.setText("%.2f".format(fovDeg))

        val focalLengthEdit = view.findViewById<EditText>(R.id.text_setup_fov_lens)
        focalLengthEdit.setText("%d".format(fovToFocalLength(fovDeg).toInt()))

        val imageNameText = view.findViewById<TextView>(R.id.text_setup_imagename)
        if (imageUri != null) {
            imageNameText.setText(getUriFilename(    requireContext().contentResolver, imageUri!!))
        } else {
            imageNameText.setText("")
        }
        val runButton = view.findViewById<Button>(R.id.setup_run)
        runButton.setEnabled(isValidFovDeg(fovDeg) && imageUri != null && DeepSkyCsv.getSingleton() != null)
    }

    val eventHandler = Handler(Looper.getMainLooper()) { msg: Message ->
        println("EVENTHANDLER: what=${msg.what} msg=${msg.obj}")
        when (msg.what) {
            EVENT_MESSAGE -> {

            }
            EVENT_OK -> {

            }
            EVENT_ERROR -> {

            }
            EVENT_SHOW_SOLUTION -> startSolutionFragment(msg.obj as String)
            EVENT_DEEPSKYCSV_LOADED -> {
                Log.d(TAG, "deepskycsv loaded")
                updateView()
            }
            else -> throw Error("Invalid message $msg")
        }
        println("HANDLER $msg")
        true
    }

    private fun dispatchMessage(what: Int, message: String) {
        eventHandler.dispatchMessage(
            Message.obtain(eventHandler, what, message)
        )
    }
    private fun onRunAstap() {
        if (!isValidFovDeg(fovDeg) || imageUri == null) {
            throw Error("invalid args")
        }
        Thread(Runnable {
            try {
                val fovDeg: Double = 2.0
                    dispatchMessage(EVENT_MESSAGE, "copying file")
                val activity = requireActivity()
                val resolver = activity.contentResolver
                val ext = getUriMimeType(resolver, imageUri!!)
                val inputStream = resolver.openInputStream(imageUri!!)
                if (inputStream == null) {
                    dispatchMessage(EVENT_ERROR, "could not open $imageUri")
                    return@Runnable
                }
                val sha256 = inputStream.use {
                    inputStreamDigest(inputStream)
                }
                val imagePath = File(activity.getExternalFilesDir(null), "${sha256}.$ext")
                val solverParams = SolverParameters(imagePath.absolutePath, fovDeg)
                val solutionJsonPath =
                    File(activity.getExternalFilesDir(null), "${solverParams.hashString()}.json")
                var solution: Solution? = null
                try {
                    solution = readSolution(solutionJsonPath)
                    if (!solution.isValid()) {
                        solution = null
                    }
                } catch (e: Exception) {
                    Log.d(TAG,"readSolution: could not read cached solution in solutionJsonPath: $e: Running astap")
                }

                if (solution == null) {
                    if (!imagePath.exists()) {
                        copyUriTo(activity.contentResolver, imageUri!!, imagePath)
                        dispatchMessage(EVENT_MESSAGE, "copied file")
                    } else {
                        dispatchMessage(EVENT_MESSAGE, "file already exists; skipping copying")
                    }

                    val wcsPath = replaceExt(imagePath, ".wcs")
                    wcsPath.delete() // delete an old result file if any
                    runAstap(imagePath, fovDeg)
                    if (!wcsPath.exists()) {
                        Log.e(TAG, "runastap: file $wcsPath does not exist")
                        return@Runnable
                    }
                    FileInputStream(wcsPath).use { wcsStream ->
                        val wcs = Wcs(wcsStream)

                        // Reference pixel coordinate.
                        // Typically the middle of the image
                        val refPixel =
                            PixelCoordinate(wcs.getDouble(Wcs.CRPIX1), wcs.getDouble(Wcs.CRPIX2))
                        // The corresponding astrometric coordinate
                        val refWcs =
                            WcsCoordinate(wcs.getDouble(Wcs.CRVAL1), wcs.getDouble(Wcs.CRVAL2))
                        val dim =
                            Wcs.ImageDimension((refPixel.x * 2).toInt(), (refPixel.y * 2).toInt())
                        val pixelToWcsMatrix = Matrix22(
                            wcs.getDouble(Wcs.CD1_1), wcs.getDouble(Wcs.CD1_2),
                            wcs.getDouble(Wcs.CD2_1), wcs.getDouble(Wcs.CD2_2)
                        )
                        val wcsToPixelMatrix = pixelToWcsMatrix.invert()

                        var minRa = Double.MAX_VALUE
                        var maxRa = -Double.MAX_VALUE
                        var minDec = Double.MAX_VALUE
                        var maxDec = -Double.MAX_VALUE

                        val updateRange = fun(px: PixelCoordinate) {
                            val wcs = convertPixelToWcs(px, dim, refPixel, refWcs, pixelToWcsMatrix)
                            if (minRa > wcs.ra) minRa = wcs.ra
                            if (maxRa < wcs.ra) maxRa = wcs.ra
                            if (minDec > wcs.dec) minDec = wcs.dec
                            if (maxDec < wcs.dec) maxDec = wcs.dec
                        }
                        updateRange(PixelCoordinate(0.0, 0.0))
                        updateRange(PixelCoordinate(0.0, dim.height.toDouble()))
                        updateRange(PixelCoordinate(dim.width.toDouble(), 0.0))
                        updateRange(PixelCoordinate(dim.width.toDouble(), dim.height.toDouble()))

                        val allMatchedStars =
                            DeepSkyCsv.getSingleton()!!.findInRange(minRa, minDec, maxRa, maxDec)
                        val validMatchedStars = ArrayList<DeepSkyEntry>()
                        // Since the image rectangle may not be aligned with the wcs coordinate system,
                        // findInRange will report stars outside the image rectangle. Remove them.
                        for (m in allMatchedStars) {
                            val px =
                                convertWcsToPixel(m.wcs, dim, refPixel, refWcs, wcsToPixelMatrix)
                            if (px.x < 0.0 || px.x >= dim.width || px.y < 0.0 || px.y >= dim.height) continue
                            validMatchedStars.add(m)
                        }

                        solution = Solution(
                            params = solverParams,
                            refPixel = refPixel,
                            refWcs = refWcs,
                            imageDimension = dim,
                            pixelToWcsMatrix = pixelToWcsMatrix,
                            matchedStars = validMatchedStars
                        )
                        val js = Gson().toJson(solution)
                        Log.d(TAG, "runastap: write result to $solutionJsonPath")
                        writeFileAtomic(solutionJsonPath, js)
                    }
                }
                if (solution == null) {
                    throw Error("could not build solution")
                }
                dispatchMessage(EVENT_SHOW_SOLUTION, solutionJsonPath.absolutePath)
            } finally {
                dispatchMessage(EVENT_OK, "all done")
            }
        }).start()
    }

    private fun runAstap(imagePath: File, fovDeg: Double) {
        val cmdline = arrayOf(
            getAstapCliPath(requireContext()).path,
            "-f", imagePath.path,
            "-d", getStarDbDir(requireContext()).path,
            "-fov", fovDeg.toString(),
        )
        Log.d(TAG, "runastap: cmdline=${cmdline.contentToString()}")
        val proc: Process = Runtime.getRuntime().exec(cmdline, null, imagePath.parentFile)
        val readOutputs = fun(stream: InputStream) {
            Thread {
                val buf = ByteArray(8192)
                while (true) {
                    val len = stream.read(buf)
                    if (len < 0) break
                    System.out.write(buf, 0, len)
                }
            }.start()
        }
        readOutputs(proc.inputStream)
        readOutputs(proc.errorStream)
        val exitCode = proc.waitFor()
        if (exitCode != 0) {
            Log.d(TAG, "runastap: exitcode=$exitCode")
            return
        }
    }
    fun startSolutionFragment(solutionJsonPath: String) {
        val bundle = Bundle()
        bundle.putString(ResultFragment.BUNDLE_KEY_SOLUTION_JSON_PATH, solutionJsonPath)
        val fragment = ResultFragment()
        fragment.arguments = bundle
        val ft = parentFragmentManager.beginTransaction()
        ft.replace(R.id.content_frame, fragment)
        ft.commit()
    }
}
