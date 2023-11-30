package com.farydrop.mediapipeapp.fragments

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.farydrop.mediapipeapp.HandLandmarkerHelper
import com.farydrop.mediapipeapp.MainViewModel
import com.farydrop.mediapipeapp.databinding.FragmentGalleryBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class GalleryFragment : Fragment(), HandLandmarkerHelper.LandmarkerListener {

    enum class MediaType {
        IMAGE,
        VIDEO,
        UNKNOWN
    }

    private lateinit var binding: FragmentGalleryBinding
    private lateinit var handLandmarkerHelper: HandLandmarkerHelper
    private val viewModel: MainViewModel by activityViewModels()

    /** Blocking ML operations are performed using this executor */
    private lateinit var backgroundExecutor: ScheduledExecutorService

    private val getContent =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            // Handle the returned Uri
            uri?.let { mediaUri ->
                when (val mediaType = loadMediaType(mediaUri)) {
                    MediaType.IMAGE -> runDetectionOnImage(mediaUri)
                    MediaType.VIDEO -> runDetectionOnVideo(mediaUri)
                    MediaType.UNKNOWN -> {
                        updateDisplayView(mediaType)
                        Toast.makeText(
                            requireContext(),
                            "Unsupported data type.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding =
            FragmentGalleryBinding.inflate(inflater, container, false)

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.fabGetContent.setOnClickListener {
            getContent.launch(arrayOf("image/*", "video/*"))
        }

        initBottomSheetControls()
    }

    override fun onPause() {
        binding.overlay.clear()
        if (binding.videoView.isPlaying) {
            binding.videoView.stopPlayback()
        }
        binding.videoView.visibility = View.GONE
        super.onPause()
    }

    private fun initBottomSheetControls() {
        // init bottom sheet settings
        binding.bottomSheetLayout.maxHandsValue.text =
            viewModel.currentMaxHands.toString()
        binding.bottomSheetLayout.detectionThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinHandDetectionConfidence
            )
        binding.bottomSheetLayout.trackingThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinHandTrackingConfidence
            )
        binding.bottomSheetLayout.presenceThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinHandPresenceConfidence
            )

        // When clicked, lower detection score threshold floor
        binding.bottomSheetLayout.detectionThresholdMinus.setOnClickListener {
            if (viewModel.currentMinHandDetectionConfidence >= 0.2) {
                viewModel.setMinHandDetectionConfidence(viewModel.currentMinHandDetectionConfidence - 0.1f)
                updateControlsUi()
            }
        }

        // When clicked, raise detection score threshold floor
        binding.bottomSheetLayout.detectionThresholdPlus.setOnClickListener {
            if (viewModel.currentMinHandDetectionConfidence <= 0.8) {
                viewModel.setMinHandDetectionConfidence(viewModel.currentMinHandDetectionConfidence + 0.1f)
                updateControlsUi()
            }
        }

        // When clicked, lower hand tracking score threshold floor
        binding.bottomSheetLayout.trackingThresholdMinus.setOnClickListener {
            if (viewModel.currentMinHandTrackingConfidence >= 0.2) {
                viewModel.setMinHandTrackingConfidence(
                    viewModel.currentMinHandTrackingConfidence - 0.1f
                )
                updateControlsUi()
            }
        }

        // When clicked, raise hand tracking score threshold floor
        binding.bottomSheetLayout.trackingThresholdPlus.setOnClickListener {
            if (viewModel.currentMinHandTrackingConfidence <= 0.8) {
                viewModel.setMinHandTrackingConfidence(
                    viewModel.currentMinHandTrackingConfidence + 0.1f
                )
                updateControlsUi()
            }
        }

        // When clicked, lower hand presence score threshold floor
        binding.bottomSheetLayout.presenceThresholdMinus.setOnClickListener {
            if (viewModel.currentMinHandPresenceConfidence >= 0.2) {
                viewModel.setMinHandPresenceConfidence(
                    viewModel.currentMinHandPresenceConfidence - 0.1f
                )
                updateControlsUi()
            }
        }

        // When clicked, raise hand presence score threshold floor
        binding.bottomSheetLayout.presenceThresholdPlus.setOnClickListener {
            if (viewModel.currentMinHandPresenceConfidence <= 0.8) {
                viewModel.setMinHandPresenceConfidence(
                    viewModel.currentMinHandPresenceConfidence + 0.1f
                )
                updateControlsUi()
            }
        }

        // When clicked, reduce the number of objects that can be detected at a time
        binding.bottomSheetLayout.maxHandsMinus.setOnClickListener {
            if (viewModel.currentMaxHands > 1) {
                viewModel.setMaxHands(viewModel.currentMaxHands - 1)
                updateControlsUi()
            }
        }

        // When clicked, increase the number of objects that can be detected at a time
        binding.bottomSheetLayout.maxHandsPlus.setOnClickListener {
            if (viewModel.currentMaxHands < 2) {
                viewModel.setMaxHands(viewModel.currentMaxHands + 1)
                updateControlsUi()
            }
        }

        // When clicked, change the underlying hardware used for inference. Current options are CPU
        // GPU, and NNAPI
        binding.bottomSheetLayout.spinnerDelegate.setSelection(
            viewModel.currentDelegate,
            false
        )
        binding.bottomSheetLayout.spinnerDelegate.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p0: AdapterView<*>?,
                    p1: View?,
                    p2: Int,
                    p3: Long
                ) {

                    viewModel.setDelegate(p2)
                    updateControlsUi()
                }

                override fun onNothingSelected(p0: AdapterView<*>?) {
                    /* no op */
                }
            }
    }

    // Update the values displayed in the bottom sheet. Reset detector.
    private fun updateControlsUi() {
        if (binding.videoView.isPlaying) {
            binding.videoView.stopPlayback()
        }
        binding.videoView.visibility = View.GONE
        binding.imageResult.visibility = View.GONE
        binding.overlay.clear()
        binding.bottomSheetLayout.maxHandsValue.text =
            viewModel.currentMaxHands.toString()
        binding.bottomSheetLayout.detectionThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinHandDetectionConfidence
            )
        binding.bottomSheetLayout.trackingThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinHandTrackingConfidence
            )
        binding.bottomSheetLayout.presenceThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinHandPresenceConfidence
            )

        binding.overlay.clear()
        binding.tvPlaceholder.visibility = View.VISIBLE
    }

    // Load and display the image.
    private fun runDetectionOnImage(uri: Uri) {
        setUiEnabled(false)
        backgroundExecutor = Executors.newSingleThreadScheduledExecutor()
        updateDisplayView(MediaType.IMAGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(
                requireActivity().contentResolver,
                uri
            )
            ImageDecoder.decodeBitmap(source)
        } else {
            MediaStore.Images.Media.getBitmap(
                requireActivity().contentResolver,
                uri
            )
        }
            .copy(Bitmap.Config.ARGB_8888, true)
            ?.let { bitmap ->
                binding.imageResult.setImageBitmap(bitmap)

                // Run hand landmarker on the input image
                backgroundExecutor.execute {

                    handLandmarkerHelper =
                        HandLandmarkerHelper(
                            context = requireContext(),
                            runningMode = RunningMode.IMAGE,
                            minHandDetectionConfidence = viewModel.currentMinHandDetectionConfidence,
                            minHandTrackingConfidence = viewModel.currentMinHandTrackingConfidence,
                            minHandPresenceConfidence = viewModel.currentMinHandPresenceConfidence,
                            maxNumHands = viewModel.currentMaxHands,
                            currentDelegate = viewModel.currentDelegate
                        )

                    handLandmarkerHelper.detectImage(bitmap)?.let { result ->
                        activity?.runOnUiThread {
                            binding.overlay.setResults(
                                result.results[0],
                                bitmap.height,
                                bitmap.width,
                                RunningMode.IMAGE
                            )

                            setUiEnabled(true)
                            binding.bottomSheetLayout.inferenceTimeVal.text =
                                String.format("%d ms", result.inferenceTime)
                        }
                    } ?: run { Log.e(TAG, "Error running hand landmarker.") }

                    handLandmarkerHelper.clearHandLandmarker()
                }
            }
    }

    private fun runDetectionOnVideo(uri: Uri) {
        setUiEnabled(false)
        updateDisplayView(MediaType.VIDEO)

        with(binding.videoView) {
            setVideoURI(uri)
            // mute the audio
            setOnPreparedListener { it.setVolume(0f, 0f) }
            requestFocus()
        }

        backgroundExecutor = Executors.newSingleThreadScheduledExecutor()
        backgroundExecutor.execute {

            handLandmarkerHelper =
                HandLandmarkerHelper(
                    context = requireContext(),
                    runningMode = RunningMode.VIDEO,
                    minHandDetectionConfidence = viewModel.currentMinHandDetectionConfidence,
                    minHandTrackingConfidence = viewModel.currentMinHandTrackingConfidence,
                    minHandPresenceConfidence = viewModel.currentMinHandPresenceConfidence,
                    maxNumHands = viewModel.currentMaxHands,
                    currentDelegate = viewModel.currentDelegate
                )

            activity?.runOnUiThread {
                binding.videoView.visibility = View.GONE
                binding.progress.visibility = View.VISIBLE
            }

            handLandmarkerHelper.detectVideoFile(uri, VIDEO_INTERVAL_MS)
                ?.let { resultBundle ->
                    activity?.runOnUiThread { displayVideoResult(resultBundle) }
                }
                ?: run { Log.e(TAG, "Error running hand landmarker.") }

            handLandmarkerHelper.clearHandLandmarker()
        }
    }

    // Setup and display the video.
    private fun displayVideoResult(result: HandLandmarkerHelper.ResultBundle) {

        binding.videoView.visibility = View.VISIBLE
        binding.progress.visibility = View.GONE

        binding.videoView.start()
        val videoStartTimeMs = SystemClock.uptimeMillis()

        backgroundExecutor.scheduleAtFixedRate(
            {
                activity?.runOnUiThread {
                    val videoElapsedTimeMs =
                        SystemClock.uptimeMillis() - videoStartTimeMs
                    val resultIndex =
                        videoElapsedTimeMs.div(VIDEO_INTERVAL_MS).toInt()

                    if (resultIndex >= result.results.size || binding.videoView.visibility == View.GONE) {
                        // The video playback has finished so we stop drawing bounding boxes
                        backgroundExecutor.shutdown()
                    } else {
                        binding.overlay.setResults(
                            result.results[resultIndex],
                            result.inputImageHeight,
                            result.inputImageWidth,
                            RunningMode.VIDEO
                        )

                        setUiEnabled(true)

                        binding.bottomSheetLayout.inferenceTimeVal.text =
                            String.format("%d ms", result.inferenceTime)
                    }
                }
            },
            0,
            VIDEO_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun updateDisplayView(mediaType: MediaType) {
        binding.imageResult.visibility =
            if (mediaType == MediaType.IMAGE) View.VISIBLE else View.GONE
        binding.videoView.visibility =
            if (mediaType == MediaType.VIDEO) View.VISIBLE else View.GONE
        binding.tvPlaceholder.visibility =
            if (mediaType == MediaType.UNKNOWN) View.VISIBLE else View.GONE
    }

    // Check the type of media that user selected.
    private fun loadMediaType(uri: Uri): MediaType {
        val mimeType = context?.contentResolver?.getType(uri)
        mimeType?.let {
            if (mimeType.startsWith("image")) return MediaType.IMAGE
            if (mimeType.startsWith("video")) return MediaType.VIDEO
        }

        return MediaType.UNKNOWN
    }

    private fun setUiEnabled(enabled: Boolean) {
        binding.fabGetContent.isEnabled = enabled
        binding.bottomSheetLayout.detectionThresholdMinus.isEnabled =
            enabled
        binding.bottomSheetLayout.detectionThresholdPlus.isEnabled =
            enabled
        binding.bottomSheetLayout.trackingThresholdMinus.isEnabled =
            enabled
        binding.bottomSheetLayout.trackingThresholdPlus.isEnabled =
            enabled
        binding.bottomSheetLayout.presenceThresholdMinus.isEnabled =
            enabled
        binding.bottomSheetLayout.presenceThresholdPlus.isEnabled =
            enabled
        binding.bottomSheetLayout.maxHandsPlus.isEnabled =
            enabled
        binding.bottomSheetLayout.maxHandsMinus.isEnabled =
            enabled
        binding.bottomSheetLayout.spinnerDelegate.isEnabled =
            enabled
    }

    private fun classifyingError() {
        activity?.runOnUiThread {
            binding.progress.visibility = View.GONE
            setUiEnabled(true)
            updateDisplayView(MediaType.UNKNOWN)
        }
    }

    override fun onError(error: String, errorCode: Int) {
        classifyingError()
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            if (errorCode == HandLandmarkerHelper.GPU_ERROR) {
                binding.bottomSheetLayout.spinnerDelegate.setSelection(
                    HandLandmarkerHelper.DELEGATE_CPU,
                    false
                )
            }
        }
    }

    override fun onResults(resultBundle: HandLandmarkerHelper.ResultBundle) {
        // no-op
    }

    companion object {
        private const val TAG = "GalleryFragment"

        // Value used to get frames at specific intervals for inference (e.g. every 300ms)
        private const val VIDEO_INTERVAL_MS = 300L
    }
}