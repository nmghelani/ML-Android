package com.shrewd.machinelearningexample.fragment;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Trace;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.shrewd.machinelearningexample.R;
import com.shrewd.machinelearningexample.databinding.FragmentClassificationBinding;
import com.shrewd.machinelearningexample.databinding.LabelBinding;
import com.shrewd.machinelearningexample.utils.ImageUtils;

import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.label.Category;
import org.tensorflow.lite.task.core.vision.ImageProcessingOptions;
import org.tensorflow.lite.task.vision.classifier.Classifications;
import org.tensorflow.lite.task.vision.classifier.ImageClassifier;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.DecimalFormat;
import java.util.List;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;

public class ClassificationFragment extends Fragment implements View.OnClickListener, ImageReader.OnImageAvailableListener {

  private static final String TAG = ClassificationFragment.class.getName();
  private static final int MAX_RESULTS = 1;
  private FragmentClassificationBinding binding;
  private static DecimalFormat df2 = new DecimalFormat("#.##");
  private AlertDialog dgPermission;
  private static final Size DESIRED_PREVIEW_SIZE = new Size(640, 480);

  @IntDef({
      ClassifyBy.CAMERA,
      ClassifyBy.GALLERY
  })
  private @interface ClassifyBy {
    int CAMERA = 0;
    int GALLERY = 1;
  }
  private int classifyBy = ClassifyBy.GALLERY;

  private byte[][] yuvBytes = new byte[3][];
  private int yRowStride;
  private int[] rgbBytes = null;
  private boolean useCamera2API;
  private boolean isProcessingFrame = false;
  private Runnable postInferenceCallback;
  private Runnable imageConverter;
  private Bitmap rgbFrameBitmap = null;
  private int cameraRotation;
  private boolean isProcessingBitmap = false;
  private CameraConnectionFragment fragment;
  private boolean isAddingBoxes = false;

  @IntDef({RequestCode.PICK_IMAGE, RequestCode.CAMERA_PERMISSION})
  @interface RequestCode {
    int PICK_IMAGE = 1001;
    int CAMERA_PERMISSION = 1002;
  }

  protected int previewWidth = 0;
  protected int previewHeight = 0;

  public ClassificationFragment() {
    // Required empty public constructor
  }

  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                           Bundle savedInstanceState) {
    // Inflate the layout for this fragment
    binding = FragmentClassificationBinding.inflate(inflater, container, false);
//    startBackgroundThread();

    binding.btnSelect.setOnClickListener(this);
//    binding.textureView.setSurfaceTextureListener(textureListener);

    binding.swCamera.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
      @Override
      public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (isChecked) {

          if (ActivityCompat.checkSelfPermission(getActivity(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
//            openCamera();
            toggleInputMode(ClassifyBy.CAMERA);
            setCameraFragment();
          } else {
            if (ActivityCompat.shouldShowRequestPermissionRationale(getActivity(), Manifest.permission.CAMERA)) {
              MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(getActivity());
              builder.setMessage("• The permission is needed to use camera\n"
                  + "Want to give permissions?");
              builder.setTitle("Needs of permissions");

              builder.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                  requestPermissions(new String[]{Manifest.permission.CAMERA}, RequestCode.CAMERA_PERMISSION);
                }
              });
              if (dgPermission != null && dgPermission.isShowing()) {
                dgPermission.dismiss();
              }
              dgPermission = builder.show();
              dgPermission.setCancelable(false);
            } else {
              requestPermissions(new String[]{Manifest.permission.CAMERA}, RequestCode.CAMERA_PERMISSION);
            }
          }
        } else {
          toggleInputMode(ClassifyBy.GALLERY);
//          stopBackgroundThread();
//          closeCamera();
        }
      }
    });
    toggleInputMode(ClassifyBy.GALLERY);
    return binding.getRoot();
  }

  // Returns true if the device supports the required hardware level, or better.
  private boolean isHardwareLevelSupported(
      CameraCharacteristics characteristics, int requiredLevel) {
    int deviceLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
    if (deviceLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
      return requiredLevel == deviceLevel;
    }
    // deviceLevel is not LEGACY, can use numerical sort
    return requiredLevel <= deviceLevel;
  }

  private String chooseCamera() {
    final CameraManager manager = (CameraManager) getActivity().getSystemService(Context.CAMERA_SERVICE);
    try {
      for (final String cameraId : manager.getCameraIdList()) {
        final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

        // We don't use a front facing camera in this sample.
        final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
        if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
          continue;
        }

        final StreamConfigurationMap map =
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

        if (map == null) {
          continue;
        }

        // Fallback to camera1 API for internal cameras that don't have full support.
        // This should help with legacy situations where using the camera2 API causes
        // distorted or otherwise broken previews.
        useCamera2API =
            (facing == CameraCharacteristics.LENS_FACING_EXTERNAL)
                || isHardwareLevelSupported(
                characteristics, CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL);
        Log.i(TAG, "chooseCamera: Camera API lv2?: " + useCamera2API);
        return cameraId;
      }
    } catch (CameraAccessException e) {
      Log.e(TAG, "chooseCamera: Not allowed to access camera");
    }

    return null;
  }

  protected void setCameraFragment() {
    String cameraId = chooseCamera();

    fragment = CameraConnectionFragment.newInstance(
        new CameraConnectionFragment.ConnectionCallback() {
          @Override
          public void onPreviewSizeChosen(Size size, int cameraRotation) {
            previewWidth = size.getWidth();
            previewHeight = size.getHeight();
            rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888);
            ClassificationFragment.this.cameraRotation = cameraRotation;
          }
        },
        ClassificationFragment.this,
        R.layout.fragment_camera_connection,
        DESIRED_PREVIEW_SIZE);

    fragment.setCamera(cameraId);

    getActivity().getSupportFragmentManager().beginTransaction().replace(R.id.camera_container, fragment).commit();
  }

  @Override
  public void onClick(View v) {
    if (binding == null) return;
    if (v == binding.btnSelect) {
      Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
      intent.setType("image/*");
      startActivityForResult(intent, RequestCode.PICK_IMAGE);
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    if (requestCode == RequestCode.CAMERA_PERMISSION) {
      if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
        /*if (!binding.textureView.isAvailable()) {
          binding.textureView.setSurfaceTextureListener(textureListener);
        }*/
//        openCamera();
        toggleInputMode(ClassifyBy.CAMERA);
      }
    }
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
    if (resultCode != Activity.RESULT_OK) {
      Toast.makeText(getActivity(), "Image not selected!", Toast.LENGTH_SHORT).show();
      return;
    }
    if (requestCode == RequestCode.PICK_IMAGE) {
      Uri uri = data.getData();
      processImage(uri);
    }
  }

  private void processImage(Uri uri) {
    if (binding == null) return;
    if (uri == null) {
      Toast.makeText(getActivity(), "Failed to load image!", Toast.LENGTH_SHORT).show();
      return;
    }
    binding.tvPath.setText(uri.getPath());
    binding.imageView.setImageURI(uri);
    binding.cvImage.setVisibility(View.VISIBLE);

    try {
      Bitmap bitmap;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(getActivity().getContentResolver(), uri));
      } else {
        bitmap = MediaStore.Images.Media.getBitmap(getActivity().getContentResolver(), uri);
      }

      classifyImage(bitmap);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private void classifyImage(Bitmap b) {

    final Bitmap[] bitmap = {b};
    new Handler().post(new Runnable() {
      @Override
      public void run() {
        if (bitmap[0] == null || isProcessingBitmap) {
          Log.d(TAG, "run: " + isProcessingBitmap);
          return;
        }
        isProcessingBitmap = true;

        Matrix matrix = new Matrix();
        matrix.postRotate(cameraRotation);
        bitmap[0] = bitmap[0].copy(Bitmap.Config.ARGB_8888, true);
        bitmap[0] = Bitmap.createBitmap(bitmap[0], 0, 0, bitmap[0].getWidth(), bitmap[0].getHeight(), matrix, true);
        bitmap[0] = Bitmap.createScaledBitmap(bitmap[0], 224, 224, true);
        Bitmap rotatedBitmap = bitmap[0];

        FaceDetectorOptions realTimeOpts =
            new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .enableTracking()
                .build();

        FaceDetector detector = FaceDetection.getClient(realTimeOpts);
        Task<List<Face>> result =
            detector.process(InputImage.fromBitmap(rotatedBitmap, 0))
                .addOnSuccessListener(
                    new OnSuccessListener<List<Face>>() {

                      @Override
                      public void onSuccess(List<Face> faces) {
                        if (isAddingBoxes) {
                          return;
                        }
                        isAddingBoxes = true;
                        new Handler().post(new Runnable() {
                          @Override
                          public void run() {
                            binding.boxContainer.removeAllViews();
                            if (!faces.isEmpty()) {
                              Log.d(TAG, "onSuccess: faces " + faces.get(0));
                              for (Face face : faces) {
//                              Bitmap croppedBitmap = Bitmap.createBitmap(bitmap, (int) left, (int) top, (int) right, (int) bottom);
                                Bitmap croppedBitmap;
                                Rect rectFace = face.getBoundingBox();
                                rectFace.left = Math.max(0, rectFace.left);
                                rectFace.top = Math.max(0, rectFace.top);
                                rectFace.right = Math.min(rotatedBitmap.getWidth(), rectFace.right);
                                rectFace.bottom = Math.min(rotatedBitmap.getHeight(), rectFace.bottom);
                                if (rectFace.width() <= 0 || rectFace.height() <= 0) continue;
                                croppedBitmap = Bitmap.createBitmap(rotatedBitmap, rectFace.left, rectFace.top, rectFace.width(), rectFace.height());
                                /*if (croppedBitmap.getWidth() < 224 || croppedBitmap.getHeight() < 224) {
                                  Log.e(TAG, "Failed: too small to predict" );
                                  return;
                                }*/

                                try {
                                  ImageClassifier.ImageClassifierOptions options =
                                      ImageClassifier.ImageClassifierOptions.builder().setMaxResults(MAX_RESULTS).build();
                                  ImageClassifier imageClassifier = ImageClassifier.createFromFileAndOptions(getActivity(),
                                      "model.tflite", options);

                                  TensorImage inputImage = TensorImage.fromBitmap(croppedBitmap);
                                  int width = croppedBitmap.getWidth();
                                  int height = croppedBitmap.getHeight();
                                  int cropSize = Math.min(width, height);
                                  ImageProcessingOptions imageOptions =
                                      ImageProcessingOptions.builder()
                                          .setRoi(
                                              new Rect(
                                                  /*left=*/ (width - cropSize) / 2,
                                                  /*top=*/ (height - cropSize) / 2,
                                                  /*right=*/ (width + cropSize) / 2,
                                                  /*bottom=*/ (height + cropSize) / 2))
                                          .build();

                                  List<Classifications> results = imageClassifier.classify(inputImage,
                                      imageOptions);
                                  String[] classified = getClassName(results.get(0).getCategories().get(0)).split("/");
                                  Log.d(TAG, "classifyImage: " + classified[0]);

                                  if (face.getTrackingId() == null) return;
                                  LabelBinding labelBinding = LabelBinding.inflate(getLayoutInflater());
                                  int w = (int) scaleWidth(rectFace.width(), rotatedBitmap);
                                  int h = (int) scaleHeight(rectFace.height(), rotatedBitmap);
                                  labelBinding.box.setLayoutParams(new LinearLayout.LayoutParams(w, h));
                                  labelBinding.getRoot().setTranslationX(scaleWidth(rectFace.left, rotatedBitmap));
                                  labelBinding.getRoot().setTranslationY(scaleHeight(rectFace.top, rotatedBitmap));

                                  labelBinding.tvClass.setText(classified[0]);
                                  labelBinding.tvPerc.setText(classified[1]);
                                  labelBinding.getRoot().setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                                  binding.boxContainer.addView(labelBinding.getRoot());

                                  imageClassifier.close();
                                  isProcessingBitmap = false;
                                } catch (IOException e) {

                                }
                              }
                            } else {
                              if (classifyBy == ClassifyBy.GALLERY) {
                                Toast.makeText(getActivity(), "No faces detected!", Toast.LENGTH_SHORT).show();
                              }
                            }
                            isProcessingBitmap = false;
                            isAddingBoxes = false;
                          }
                        });
                      }
                    })
                .addOnFailureListener(
                    new OnFailureListener() {
                      @Override
                      public void onFailure(@NonNull Exception e) {
                        isProcessingBitmap = false;
                        Log.e(TAG, "onFailure: " + e);
                        // Task failed with an exception
                        // ...
                      }
                    });
//        binding.imageView.setImageBitmap(bitmap);
      }

      private float scaleWidth(float pixel, Bitmap bitmapOrg) {
        if (classifyBy == ClassifyBy.CAMERA) {
          return (pixel * (float) (fragment.getTextureWidth() / bitmapOrg.getWidth()));
        } else {
          return (pixel * (float) (b.getWidth() / bitmapOrg.getWidth()));
        }
      }

      private float scaleHeight(float pixel, Bitmap bitmapOrg) {
        if (classifyBy == ClassifyBy.CAMERA) {
          return (pixel * (float) (fragment.getTextureHeight() / bitmapOrg.getHeight()));
        } else {
          return (pixel * (float) (b.getHeight() / bitmapOrg.getHeight()));
        }
      }

    });
  }

  private String getClassName(Category category) {
    int className = Integer.parseInt(category.getLabel());

    if (className == 0) {
      return "With Mask /" + df2.format(category.getScore());
    } else {
      return "Without Mask /" + df2.format(category.getScore());
    }
  }

  private void toggleInputMode(int type) {
    classifyBy = type;
    if (type == ClassifyBy.CAMERA) {
      binding.cameraContainer.setVisibility(View.VISIBLE);
      binding.imageView.setVisibility(View.GONE);
      binding.cvSelect.setVisibility(View.GONE);
    } else {
      if (fragment != null) {
        fragment.closeCamera();
        getActivity().getSupportFragmentManager().beginTransaction().detach(fragment).commit();
      }
      binding.cameraContainer.setVisibility(View.GONE);
      binding.imageView.setVisibility(View.VISIBLE);
      binding.cvSelect.setVisibility(View.VISIBLE);
    }
  }

  @Override
  public void onImageAvailable(final ImageReader reader) {
    // We need wait until we have some size from onPreviewSizeChosen
    if (previewWidth == 0 || previewHeight == 0) {
      return;
    }
    if (rgbBytes == null) {
      rgbBytes = new int[previewWidth * previewHeight];
    }
    try {
      final Image image = reader.acquireLatestImage();

      if (image == null) {
        return;
      }

      if (isProcessingFrame) {
        image.close();
        return;
      }
      isProcessingFrame = true;
      Trace.beginSection("imageAvailable");
      final Image.Plane[] planes = image.getPlanes();
      fillBytes(planes, yuvBytes);
      yRowStride = planes[0].getRowStride();
      final int uvRowStride = planes[1].getRowStride();
      final int uvPixelStride = planes[1].getPixelStride();

      ImageUtils.convertYUV420ToARGB8888(
          yuvBytes[0],
          yuvBytes[1],
          yuvBytes[2],
          previewWidth,
          previewHeight,
          yRowStride,
          uvRowStride,
          uvPixelStride,
          rgbBytes);

      image.close();
      isProcessingFrame = false;

      rgbFrameBitmap.setPixels(rgbBytes, 0, previewWidth, 0, 0, previewWidth, previewHeight);
      classifyImage(rgbFrameBitmap);

    } catch (final Exception e) {
      Log.e(TAG, "onImageAvailable: " + e.getMessage());
      Trace.endSection();
      return;
    }
    Trace.endSection();
  }

  protected void fillBytes(final Image.Plane[] planes, final byte[][] yuvBytes) {
    // Because of the variable row stride it's not possible to know in
    // advance the actual necessary dimensions of the yuv planes.
    for (int i = 0; i < planes.length; ++i) {
      final ByteBuffer buffer = planes[i].getBuffer();
      if (yuvBytes[i] == null) {
        Log.d(TAG, "fillBytes: Initializing buffer " + i + " at size " + buffer.capacity());
        yuvBytes[i] = new byte[buffer.capacity()];
      }
      buffer.get(yuvBytes[i]);
    }
  }

  /*private void openCamera() {
    CameraManager manager = (CameraManager) getActivity().getSystemService(Context.CAMERA_SERVICE);
    Log.e(TAG, "is camera open");
    try {
      cameraId = manager.getCameraIdList()[0];
      CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
      StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
      assert map != null;
      imageDimension = map.getOutputSizes(SurfaceTexture.class)[0];
      // Add permission for camera and let user grant the permission
      if (ActivityCompat.checkSelfPermission(getActivity(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
        requestPermissions(new String[]{Manifest.permission.CAMERA}, RequestCode.CAMERA_PERMISSION);
        return;
      }
      manager.openCamera(cameraId, stateCallback, null);
    } catch (CameraAccessException e) {
      e.printStackTrace();
    }
    Log.e(TAG, "openCamera X");
  }*/

  /*protected void createCameraPreview() {
    try {
      SurfaceTexture texture = binding.textureView.getSurfaceTexture();
      if (texture == null) return;
      texture.setDefaultBufferSize(imageDimension.getWidth(), imageDimension.getHeight());
      Surface surface = new Surface(texture);
      captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

      previewReader =
          ImageReader.newInstance(
              previewSize.getWidth(), previewSize.getHeight(), ImageFormat.YUV_420_888, 2);

      previewReader.setOnImageAvailableListener(imageListener, backgroundHandler);
      captureRequestBuilder.addTarget(surface);
      cameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
          //The camera is already closed
          if (null == cameraDevice) {
            return;
          }
          // When the session is ready, we start displaying the preview.
          cameraCaptureSessions = cameraCaptureSession;
          updatePreview();
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
          Toast.makeText(getActivity(), "Configuration change", Toast.LENGTH_SHORT).show();
        }
      }, null);
    } catch (CameraAccessException e) {
      e.printStackTrace();
    }
  }*/

  /*protected void updatePreview() {
    if (null == cameraDevice) {
      Log.e(TAG, "updatePreview error, return");
    }
    captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range.create(1, 10));
    try {
      cameraCaptureSessions.setRepeatingRequest(captureRequestBuilder.build(), null, mBackgroundHandler);
    } catch (CameraAccessException e) {
      e.printStackTrace();
    }
  }

  protected void startBackgroundThread() {
    mBackgroundThread = new HandlerThread("Camera Background");
    mBackgroundThread.start();
    mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
  }

  protected void stopBackgroundThread() {
    if (mBackgroundThread == null) return;
    mBackgroundThread.quitSafely();
    try {
      mBackgroundThread.join();
      mBackgroundThread = null;
      mBackgroundHandler = null;
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  private void closeCamera() {
    if (null != cameraDevice) {
      cameraDevice.close();
      cameraDevice = null;
    }
  }*/
}